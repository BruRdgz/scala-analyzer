package scalapsihost.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.{GlobalSearchScope, PsiShortNamesCache}
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.{PsiClass, PsiElement, PsiFile, PsiNamedElement}
import com.intellij.usageView.UsageInfo
import com.intellij.util.CommonProcessors
import org.jetbrains.plugins.scala.extensions.PsiNamedElementExt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.findUsages.factory.ScalaFindUsagesHandlerFactory
import org.jetbrains.plugins.scala.gotoclass.{ScalaGoToClassContributor, ScalaGoToSymbolContributor}

import scalapsihost.ModuleModel
import scalapsihost.platform.RequestEditor

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Finds project usages, implementations and symbols using the plugin's search handlers.
 *
 * Searches use the project's source scope (see [[ModuleModel.sourcesScope]]). Callers hold the host's read lock.
 */
object ProjectSearch {
  /**
   * A named PSI declaration returned by a go-to contributor.
   *
   * @param element matched declaration
   * @param container containing package or class extracted from the contributor's qualified name
   */
  final case class FoundSymbol(element: PsiNamedElement, container: Option[String])

  /**
   * Caps symbol results at the best matches an editor can usefully display.
   *
   * The cap applies to matched items and bounds further item lookups as the user narrows the query.
   */
  val MaxSymbols = 100

  /**
   * Resolves the symbol referenced or declared at an editor position.
   *
   * @param file source file being queried
   * @param offset caret offset in UTF-16 code units
   * @return the find-usages target, or none when no symbol is available
   */
  def usageTarget(file: PsiFile, offset: Int): Option[PsiElement] =
    targetAt(file, offset, TargetElementUtil.getInstance.getReferenceSearchFlags)

  /** Resolves the editor target without accepting lookup items, since the host has no completion lookup UI. */
  private def targetAt(file: PsiFile, offset: Int, flags: Int): Option[PsiElement] =
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, offset)) { editor =>
      Option(TargetElementUtil.getInstance.findTargetElement(editor.editor, flags & ~TargetElementUtil.LOOKUP_ITEM_ACCEPTED, offset))
    }

  /**
   * Collects usages with the plugin handler's default search options.
   *
   * Both primary and secondary elements contribute usages. Unsupported targets, such as Java declarations,
   * produce no results.
   *
   * @param target declaration accepted by the Scala find-usages handler
   * @return usages reported by the handler
   */
  def usages(target: PsiElement): Seq[UsageInfo] = {
    val factory = new ScalaFindUsagesHandlerFactory(target.getProject)
    if (!factory.canFindUsages(target)) Seq.empty
    else {
      val handler = factory.createFindUsagesHandler(target, false)
      val collected = new CommonProcessors.CollectProcessor[UsageInfo]
      for (element <- handler.getPrimaryElements ++ handler.getSecondaryElements)
        handler.processElementUsages(element, collected, handler.getFindUsagesOptions)
      collected.getResults.asScala.toSeq
    }
  }

  /**
   * Finds implementations and overriders for an editor position.
   *
   * From a reference, the target itself is included when the plugin requests it or no implementation is found.
   *
   * @param file source file being queried
   * @param offset caret offset in UTF-16 code units
   * @return matching project implementations, possibly including the target itself
   */
  def implementations(file: PsiFile, offset: Int): Seq[PsiElement] = {
    val util = TargetElementUtil.getInstance
    val flags = util.getDefinitionSearchFlags
    targetAt(file, offset, flags).toSeq.flatMap { source =>
      val scope = GlobalSearchScope.projectScope(file.getProject)
      val others = DefinitionsScopedSearch.search(source, scope, /* checkDeep = */ true).findAll().asScala.toSeq
      val onReference = targetAt(file, offset, flags & ~TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED).isEmpty
      if (onReference && util.includeSelfInGotoImplementation(source)) source +: others
      else if (onReference && others.isEmpty) Seq(source)
      else others
    }
  }

  /**
   * Finds project symbols matching an editor query.
   *
   * Matching follows the IDE's camel-hump and word-start rules. Dotted queries match the final name and the whole
   * qualified name; blank queries match nothing. Results are capped at [[MaxSymbols]] after resolving items.
   *
   * @param project project whose source symbols should be searched
   * @param query name or qualified-name pattern
   * @return matches ranked by name relevance, with lexical ordering for equal name scores
   */
  def symbols(project: Project, query: String): Seq[FoundSymbol] =
    if (query.isBlank) Seq.empty
    else {
      val symbolContributor = new ScalaGoToSymbolContributor
      val contributors = Seq(new ScalaGoToClassContributor, symbolContributor)
      // Most classes are visible to Java, so not in the contributors' indexes: the IDE's class contributor lists them from
      // the short-names cache, which is read here directly (the platform's contributor needs more of the IDE than the host has).
      val shortNames = PsiShortNamesCache.getInstance(project)
      val scope = GlobalSearchScope.projectScope(project)
      val nameMatcher = NameUtil.buildMatcher("*" + query.substring(query.lastIndexOf('.') + 1)).build()
      val qualifiedMatcher = NameUtil.buildMatcher("*" + query).build()
      val names = (contributors.flatMap(_.getNames(project, false)) ++ shortNames.getAllClassNames).distinct
        .filter(nameMatcher.matches).sortBy(name => (-nameMatcher.matchingDegree(name), name))
      // A name can belong to library symbols only, so the cap applies to the items found, not to the names tried.
      names.iterator
        .flatMap(name => contributors.flatMap(_.getItemsByName(name, query, project, false)) ++ shortNames.getClassesByName(name, scope))
        .collect { case named: PsiNamedElement => named }.distinct
        .map { named =>
          val qualified = named match {
            case clazz: PsiClass if !clazz.isInstanceOf[ScNamedElement] => clazz.getQualifiedName
            case _ => symbolContributor.getQualifiedName(named)
          }
          named -> qualified
        }
        .filter((_, qualified) => !query.contains('.') || qualified != null && qualifiedMatcher.matches(qualified))
        .take(MaxSymbols)
        .map((named, qualified) => FoundSymbol(named, container(qualified, named.name)))
        .toSeq
    }

  /** The enclosing package or class encoded in the contributor's qualified name. */
  private def container(qualifiedName: String, name: String): Option[String] =
    Option(qualifiedName).filter(_.endsWith("." + name)).map(_.stripSuffix("." + name))
}
