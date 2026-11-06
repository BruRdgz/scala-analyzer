package scalaanalyzer.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.ide.hierarchy.{HierarchyBrowserScopes, HierarchyTreeStructure}
import com.intellij.ide.hierarchy.call.{CallHierarchyNodeDescriptor, JavaCallHierarchyProvider}
import com.intellij.ide.hierarchy.`type`.{SubtypesHierarchyTreeStructure, SupertypesHierarchyTreeStructure, TypeHierarchyNodeDescriptor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.{GlobalSearchScope, PsiShortNamesCache}
import com.intellij.psi.search.searches.{DefinitionsScopedSearch, OverridingMethodsSearch}
import com.intellij.psi.{PsiAnonymousClass, PsiClass, PsiClassOwner, PsiElement, PsiFile, PsiMethod, PsiMethodCallExpression, PsiNamedElement, PsiNewExpression, PsiSyntheticClass}
import com.intellij.usageView.UsageInfo
import com.intellij.util.CommonProcessors
import org.jetbrains.plugins.scala.extensions.PsiNamedElementExt
import org.jetbrains.plugins.scala.findUsages.factory.ScalaFindUsagesHandlerFactory
import org.jetbrains.plugins.scala.gotoclass.{ScalaGoToClassContributor, ScalaGoToSymbolContributor}
import org.jetbrains.plugins.scala.hierarchy.{ScalaCalleeMethodsTreeStructure, ScalaCallerMethodsTreeStructure}
import org.jetbrains.plugins.scala.lang.psi.api.base.{Constructor, ScReference}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScFunctionDefinition, ScTypeAliasDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.types.ScTypeExt

import scalaanalyzer.ModuleModel
import scalaanalyzer.platform.RequestEditor

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Finds usages, implementations, hierarchy edges and symbols using the plugin and platform searches.
 *
 * Usages and implementations use the project's source scope (see [[ModuleModel.sourcesScope]]). Hierarchies use the
 * IDE's "Production" scope: its default "All" is a named scope, which needs the IDE's scope managers. The host marks
 * no test sources, so this still covers the whole project. Callers hold the host's read lock.
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

  /**
   * Finds the method whose calls the IDE's call hierarchy shows for an editor position.
   *
   * That is the target itself, or the method declaring it (a local or a parameter selects its method). The plugin's
   * call trees take only methods.
   *
   * @param file source file being queried
   * @param offset caret offset in UTF-16 code units
   * @return the method, or none when no symbol is available or the hierarchy element is not a method
   */
  def callTarget(file: PsiFile, offset: Int): Option[PsiMethod] =
    usageTarget(file, offset)
      .map(JavaCallHierarchyProvider.getElementForCallHierarchy)
      .collect { case method: PsiMethod => method }

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
   * Finds the class whose type hierarchy an editor position selects.
   *
   * Mirrors ScalaTypeHierarchyProvider and its Java enclosing-class fallback. The provider accepts lookup items,
   * which requires the IDE's LookupManager; the host has no lookup UI, so it uses the same flags without that bit.
   *
   * @param file source file being queried
   * @param offset caret offset in UTF-16 code units
   * @return the target class (a type alias selects its aliased class), else the nearest enclosing class that is
   *         neither anonymous nor synthetic, or the only class of an enclosing class owner; none when there is none
   */
  def typeTarget(file: PsiFile, offset: Int): Option[PsiClass] = {
    val target = targetAt(
      file, offset, TargetElementUtil.ELEMENT_NAME_ACCEPTED | TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)
    val scalaTarget = target.flatMap {
      case clazz: PsiClass => Some(clazz)
      case Constructor.ofClass(clazz) => Some(clazz)
      case alias: ScTypeAliasDefinition => alias.aliasedType.toOption.flatMap(_.extractClass)
      case _ => None
    }

    scalaTarget.orElse {
      Iterator.iterate(Option(file.findElementAt(offset)))(_.flatMap(e => Option(e.getParent)))
        .takeWhile(_.nonEmpty)
        .flatten
        .collectFirst {
          case clazz: PsiClass if !clazz.isInstanceOf[PsiAnonymousClass] && !clazz.isInstanceOf[PsiSyntheticClass] => clazz
          case owner: PsiClassOwner if owner.getClasses.length == 1 => owner.getClasses.head
        }
    }
  }

  /**
   * Finds the callers of a method in the project scope, grouped as the plugin's caller hierarchy groups them.
   *
   * @param method method whose callers are wanted
   * @return each calling declaration with its call references as a file and a range in that file's coordinates,
   *         distinct per declaration; callers without an enclosing declaration are dropped
   */
  def incomingCalls(method: PsiMethod): Seq[(PsiElement, Seq[(PsiFile, TextRange)])] = {
    val tree = new ScalaCallerMethodsTreeStructure(method.getProject, method, HierarchyBrowserScopes.SCOPE_PROJECT)
    tree.getChildElements(tree.getBaseDescriptor).toSeq.collect { case descriptor: CallHierarchyNodeDescriptor =>
      descriptor.getEnclosingElement -> descriptor.getReferences.asScala.toSeq.map { reference =>
        val element = reference.getElement
        element.getContainingFile -> reference.getRangeInElement.shiftRight(element.getTextRange.getStartOffset)
      }.distinct
    }.filter(_._1 != null)
  }

  /**
   * Finds the methods a method calls, mirroring the plugin's callee visitor, which drops the call sites LSP needs.
   *
   * The plugin's callee scope filter (the project scope) decides which callees are kept. Overriders of `method`
   * are included as callees even when the body does not call them.
   *
   * @param method method whose body is scanned; a bodiless Scala declaration yields no calls
   * @return each callee in first-encounter order with its distinct call-site ranges in the document of `method`'s
   *         navigation element; empty for a callee reached only as an overrider
   */
  def outgoingCalls(method: PsiMethod): Seq[(PsiMethod, Seq[TextRange])] = {
    class CalleeScope extends ScalaCalleeMethodsTreeStructure(method.getProject, method, HierarchyBrowserScopes.SCOPE_PROJECT) {
      def accepts(called: PsiMethod): Boolean = isInScope(method.getContainingClass, called, HierarchyBrowserScopes.SCOPE_PROJECT)
    }
    val scope = new CalleeScope
    val calls = mutable.LinkedHashMap.empty[PsiMethod, Vector[TextRange]]

    /** Records the resolved method call or constructor call at each node of the subtree, in document order. */
    def visit(element: PsiElement): Unit = if (element != null) {
      val target = element match {
        case reference: ScReference => Option(reference.resolve()).collect { case called: PsiMethod => called }
        case call: PsiMethodCallExpression =>
          Option(call.getMethodExpression.resolve()).collect { case called: PsiMethod => called }
        case creation: PsiNewExpression => Option(creation.resolveConstructor())
        case _ => None
      }
      target.filter(scope.accepts).foreach { called =>
        val range = element match {
          case reference: ScReference => reference.getRangeInElement.shiftRight(element.getTextRange.getStartOffset)
          case call: PsiMethodCallExpression =>
            val reference = call.getMethodExpression
            reference.getRangeInElement.shiftRight(reference.getTextRange.getStartOffset)
          case _ => element.getTextRange
        }
        calls.update(called, calls.getOrElse(called, Vector.empty) :+ range)
      }
      element.getChildren.foreach(visit)
    }

    // Call-site ranges belong to the navigation file shown by LSP, including attached library source.
    val bodyMethod = method.getNavigationElement match {
      case navigation: PsiMethod => navigation
      case _ => method
    }

    bodyMethod match {
      case function: ScFunctionDefinition => function.body.foreach(visit)
      case _: ScFunction =>
      case _ => visit(bodyMethod.getBody)
    }

    // LSP permits an empty list: an override edge has no call site in the caller's document.
    OverridingMethodsSearch.search(method, method.getUseScope, true)
      .findAll()
      .asScala
      .filter(scope.accepts)
      .foreach(called => calls.getOrElseUpdate(called, Vector.empty))

    calls.toSeq.map((called, ranges) => called -> ranges.distinct)
  }

  /**
   * Finds the direct subtypes or supertypes of a class from the platform hierarchy, without presentation updates.
   *
   * @param clazz class whose hierarchy edges are wanted
   * @param subtypes `true` for direct subtypes in the project scope, `false` for direct supertypes
   * @return the hierarchy's child classes, in tree order
   */
  def typeChildren(clazz: PsiClass, subtypes: Boolean): Seq[PsiClass] = {
    val tree: HierarchyTreeStructure =
      if (subtypes) new SubtypesHierarchyTreeStructure(clazz.getProject, clazz, HierarchyBrowserScopes.SCOPE_PROJECT)
      else new SupertypesHierarchyTreeStructure(clazz.getProject, clazz)
    tree.getChildElements(tree.getBaseDescriptor).toSeq.collect {
      case descriptor: TypeHierarchyNodeDescriptor => descriptor.getPsiClass
    }.collect { case child: PsiClass => child }
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
      // Most classes are visible to Java, so not in the contributors' indexes: the IDE's class contributor lists
      // them from the short-names cache, which is read here directly (the platform's contributor needs more of the
      // IDE than the host has).
      val shortNames = PsiShortNamesCache.getInstance(project)
      val scope = GlobalSearchScope.projectScope(project)
      val nameMatcher = NameUtil.buildMatcher("*" + query.substring(query.lastIndexOf('.') + 1)).build()
      val qualifiedMatcher = NameUtil.buildMatcher("*" + query).build()
      val names = (contributors.flatMap(_.getNames(project, false)) ++ shortNames.getAllClassNames)
        .distinct
        .filter(nameMatcher.matches)
        .sortBy(name => (-nameMatcher.matchingDegree(name), name))

      // A name can belong to library symbols only, so the cap applies to the items found, not to the names tried.
      names.iterator
        .flatMap { name =>
          contributors.flatMap(_.getItemsByName(name, query, project, false)) ++ shortNames.getClassesByName(name, scope)
        }
        .collect { case named: PsiNamedElement => named }
        .distinct
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
