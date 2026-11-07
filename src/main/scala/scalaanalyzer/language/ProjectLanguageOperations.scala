package scalaanalyzer.language

import com.intellij.codeInsight.highlighting.{JavaReadWriteAccessDetector, ReadWriteAccessDetector}
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.java.JavaDocumentationProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{
  JavaPsiFacade, PsiClass, PsiCodeBlock, PsiComment, PsiDocumentManager, PsiElement, PsiEnumConstant, PsiField, PsiFile,
  PsiImportList, PsiJavaFile, PsiMethod, PsiNameIdentifierOwner, PsiNamedElement, PsiRecordComponent, PsiVariable,
  SyntaxTraverser,
}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.{PsiTreeUtil, PsiTypesUtil}
import org.jetbrains.plugins.scala.annotator.gutter.ScalaGotoTypeDeclarationProvider
import org.jetbrains.plugins.scala.editor.documentationProvider.{ScalaDocQuickInfoGenerator, ScalaDocumentationProvider}
import org.jetbrains.plugins.scala.extensions.PsiNamedElementExt
import org.jetbrains.plugins.scala.highlighter.DefaultHighlighter as H
import org.jetbrains.plugins.scala.highlighter.readWriteAccess.ScalaReadWriteAccessDetector
import org.jetbrains.plugins.scala.lang.folding.ScalaFoldingBuilder
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScBlock
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScEnumCase, ScExtension, ScFunction, ScTypeAlias, ScVariable}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScClassParameter
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScEnum, ScObject, ScTrait, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.light.ScFunctionWrapper
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.lang.scaladoc.parser.ScalaDocElementTypes
import org.jetbrains.plugins.scala.structureView.element.Element

import scalaanalyzer.{LineColumn, LineRange, ScalaPsiHost}
import scalaanalyzer.build.{CompileSpec, CompilerDiagnostic, DocumentCompiler, ScalafmtFormatting}
import scalaanalyzer.platform.{HostLog, LibraryFile, SourceFile}

import java.nio.file.{Files, Path}
import java.util.List as JavaList

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * PSI-backed navigation, document operations and language results for a project session.
 *
 * Positions use zero-based lines and UTF-16 columns. The owning session's callers serialize document operations.
 * Queries read PSI under the host read lock, either directly or inside the host operation they call, so an edit
 * that starts meanwhile cancels them with `ProcessCanceledException`; `change` is that edit. A `path` is resolved
 * by `file`, supplied by the session, and this class adds no registration check of its own. Only Scala sources
 * have every feature; each method documents what it does for Java and for other files.
 *
 * @param host process-scoped PSI host that provides the platform operations and the read and write locks
 * @param root workspace directory used as the boundary for formatter-configuration lookup
 * @param documentCompiler compiles the current buffer of a Scala 3 source for compiler diagnostics
 * @param scalafmt formats whole Scala files when a scalafmt configuration is found
 * @param file resolves a registered project-source path to its PSI; callers must protect subsequent PSI access
 */
private[scalaanalyzer] final class ProjectLanguageOperations(
  host: ScalaPsiHost,
  root: Path,
  documentCompiler: DocumentCompiler,
  scalafmt: ScalafmtFormatting,
  file: Path => PsiFile,
) {
  /** A declaration's file and the position of its name; library declarations point at a materialized file. */
  private final case class SourceLocation(path: Path, position: LineColumn)

  /** How the plugin's highlighting key is shown as a semantic token. */
  private final case class TokenStyle(kind: SemanticTokenKind, modifiers: Set[SemanticTokenModifier])

  /** What `problems` reads from a Scala file in one read: the plugin's problems, compiler mode and buffer text. */
  private final case class PluginReading(problems: Seq[FileProblem], compilerHighlighted: Boolean, text: String)

  /** Gets the in-memory document owned by the platform for `file`. */
  private def document(file: PsiFile): Document = PsiDocumentManager.getInstance(host.project).getDocument(file)

  /** Converts a workspace line/UTF-16 column to a document offset, clamping positions past a line's end. */
  private def offset(document: Document, position: LineColumn): Int =
    if (position.line >= document.getLineCount) document.getTextLength
    else
      math.min(document.getLineStartOffset(position.line) + position.column, document.getLineEndOffset(position.line))

  /** Converts a document offset to its zero-based line and UTF-16 column. */
  private def position(document: Document, offset: Int): LineColumn = {
    val line = document.getLineNumber(offset)
    LineColumn(line, offset - document.getLineStartOffset(line))
  }

  /**
   * Reads the current in-memory document text, including changes not yet saved to disk.
   *
   * @param path registered project-source path
   * @return the text of the file's PSI
   */
  def text(path: Path): String = host.read(file(path).getText)

  /**
   * Collects the completion suggestions at a position, best first. Scala and Java use their language contributors.
   *
   * Collecting suggestions inserts nothing; a chosen suggestion's handler runs through `insertCompletion`.
   *
   * @param path registered project-source path
   * @param at caret position
   * @return the suggestions in the order the host returns them, with `incomplete` as the host reports it; empty and
   *         complete for a file that is neither Scala nor Java
   */
  def completion(path: Path, at: LineColumn): CompletionChoices = file(path) match {
    case sourceFile: (ScalaFile | PsiJavaFile) =>
      host.read {
        val sourceDocument = document(sourceFile)
        val suggestions = host.completion(sourceFile, offset(sourceDocument, at))
        val choices = suggestions.items.map { suggestion =>
          val candidate = CompletionCandidate(suggestion.label, suggestion.tail, suggestion.typeText, suggestion.kind,
            suggestion.deprecated, suggestion.lookup, suggestion.prefixText, suggestion.hasHandler, suggestion.identity)
          CompletionChoice(
            candidate,
            position(sourceDocument, suggestion.prefix.getStartOffset),
            position(sourceDocument, suggestion.prefix.getEndOffset))
        }

        CompletionChoices(choices, suggestions.incomplete)
      }
    case _ => CompletionChoices(Seq.empty, incomplete = false)
  }

  /**
   * Collects the plugin's inline hints within a range. Only Scala files have them.
   *
   * @param path registered project-source path
   * @param start range start
   * @param end range end
   * @return the hints whose offset lies within the range, in the order the host returns them; empty for a file that
   *         is not Scala
   */
  def inlayHints(path: Path, start: LineColumn, end: LineColumn): Seq[InlineHint] = file(path) match {
    case sourceFile: ScalaFile => host.read {
      val sourceDocument = document(sourceFile)
      val range = new TextRange(offset(sourceDocument, start), offset(sourceDocument, end))

      host.inlayHints(sourceFile)
        .filter(hint => range.contains(hint.offset))
        .map(hint => InlineHint(position(sourceDocument, hint.offset), hint.text))
    }
    case _ => Seq.empty
  }

  /**
   * Finds the runnable test selections of a source, each with the BSP target of its owning module.
   *
   * @param path registered project-source path
   * @return the lenses of a Scala file whose module has a BSP target; empty for any other file, so non-build files
   *         produce no runnable selection
   */
  def testLenses(path: Path): Seq[TestLens] = host.read {
    file(path) match {
      case sourceFile: ScalaFile =>
        host.modules.ofFile(sourceFile.getVirtualFile).toSeq.flatMap { module =>
          module.spec.identity.toSeq.flatMap { target =>
            val sourceDocument = document(sourceFile)

            TestLenses.in(sourceFile, module.module).map { lens =>
              val range = LineRange(
                position(sourceDocument, lens.range.getStartOffset),
                position(sourceDocument, lens.range.getEndOffset))

              TestLens(range, target, lens.framework, lens.suite, lens.testName)
            }
          }
        }
      case _ => Seq.empty
    }
  }

  /**
   * Looks up the signatures of the call around a position. Only Scala files have them.
   *
   * @param path registered project-source path
   * @param at caret position
   * @return the signatures and the active one as the host reports them; `None` when the host finds no call, and for
   *         a file that is not Scala
   */
  def signatures(path: Path, at: LineColumn): Option[SignatureChoices] = file(path) match {
    case sourceFile: ScalaFile =>
      host.read(host.parameterInfo(sourceFile, offset(document(sourceFile), at))).map { found =>
        SignatureChoices(
          found.items.map { signature =>
            SignatureInfo(signature.text, signature.current.map(r => OffsetRange(r.getStartOffset, r.getEndOffset)),
              signature.applicable, signature.deprecated)
          },
          found.active)
      }
    case _ => None
  }

  /**
   * Builds each position's Extend Selection chain. An unexpandable caret still has its empty range, as LSP requires.
   *
   * @param path registered project-source path
   * @param positions carets, in the desired result order
   * @return one chain per caret, in the order of `positions`, each of ranges smallest first; empty for a file that is
   *         not Scala
   */
  def selectionRanges(path: Path, positions: Seq[LineColumn]): Seq[Seq[LineRange]] = file(path) match {
    case sourceFile: ScalaFile => host.read {
      val sourceDocument = document(sourceFile)

      positions.map { at =>
        val cursor = offset(sourceDocument, at)
        val ranges = host.selectionRanges(sourceFile, cursor)
        val chain = if (ranges.isEmpty) Seq(new TextRange(cursor, cursor)) else ranges

        chain.map(r => LineRange(position(sourceDocument, r.getStartOffset), position(sourceDocument, r.getEndOffset)))
      }
    }
    case _ => Seq.empty
  }

  /**
   * Previews the insertion follow-up of a chosen completion suggestion. Scala and Java files have one.
   *
   * The editor has already replaced the typed prefix with the chosen lookup text.
   *
   * @param path registered project-source path
   * @param start start of the inserted lookup text
   * @param prefixText original typed prefix that the lookup text replaced
   * @param lookup lookup text already inserted by the editor
   * @param identity identity of the chosen suggestion, as `completion` returned it
   * @return the changes still to apply and the caret position, in the text that applying them produces; `None` when
   *         the host reports no follow-up, and for a file that is neither Scala nor Java
   */
  def insertCompletion(path: Path, start: LineColumn, prefixText: String, lookup: String, identity: String)
      : Option[CompletionFollowUp] =
    file(path) match {
      case sourceFile: (ScalaFile | PsiJavaFile) =>
        val startOffset = host.read(offset(document(sourceFile), start))

        host.insertCompletion(sourceFile, startOffset, prefixText, lookup, identity).map { inserted =>
          val changes = host.read(inserted.result.edits.map(textChange(document(sourceFile), _)))
          val caret = StringUtil.offsetToLineColumn(inserted.result.text, inserted.caret)

          CompletionFollowUp(changes, LineColumn(caret.line, caret.column))
        }
      case _ => None
    }

  /**
   * Computes the changes that format a range of the file (all of it without one). Only Scala files are formatted.
   *
   * The buffer is unchanged. A whole-file request uses scalafmt when a configuration is found for the path; every
   * other request uses the host's IntelliJ formatter. Formatter failures propagate.
   *
   * @param path registered project-source path
   * @param range start and end of the range to format, or `None` to format the whole file
   * @param indentSize indentation size of the IntelliJ formatter; scalafmt ignores it
   * @param useTabs whether the IntelliJ formatter indents with tabs; scalafmt ignores it
   * @return the changes against the current buffer; empty for a file that is not Scala
   */
  def formatting(path: Path, range: Option[LineRange], indentSize: Int, useTabs: Boolean): Seq[TextChange] =
    file(path) match {
      case sourceFile: ScalaFile =>
        val selected = host.read(range.map { r =>
          new TextRange(offset(document(sourceFile), r.start), offset(document(sourceFile), r.end))
        })
        // The plugin defaults to the IntelliJ formatter for range requests even when scalafmt is selected.
        val config = if (range.isEmpty) ScalafmtFormatting.configFor(root, path) else None

        val edits = config match {
          case Some(configFile) =>
            val before = host.read(sourceFile.getText)
            ChangeEngine.diff(before, scalafmt.format(configFile, path, before))
          case None => host.formatting(sourceFile, selected, indentSize, useTabs)
        }

        host.read(edits.map(textChange(document(sourceFile), _)))
      case _ => Seq.empty
    }

  /** Converts a PSI text edit's document offsets to a workspace text change. */
  private def textChange(document: Document, edit: ChangeEngine.Edit): TextChange =
    TextChange(position(document, edit.range.getStartOffset), position(document, edit.range.getEndOffset), edit.text)

  /**
   * Applies an editor change to a source buffer, which updates its PSI and indexes without saving to disk.
   *
   * Does not take the read lock: the host's edit takes the write lock and cancels queries running meanwhile.
   *
   * @param path registered project-source path
   * @param range start and end of the text to replace, or `None` to replace the complete document
   * @param replacement text that takes the place of the range
   */
  def change(path: Path, range: Option[LineRange], replacement: String): Unit = {
    val sourceFile = file(path)
    // Only this thread edits documents, so reading one needs no lock; and an edit must not be cancelled as the
    // queries a newer edit cancels are.
    val sourceDocument = document(sourceFile)
    val (start, end) = range.fold((0, sourceDocument.getTextLength)) { r =>
      (offset(sourceDocument, r.start), offset(sourceDocument, r.end))
    }

    host.edit(sourceFile, start, end, replacement)
  }

  /**
   * Collects the current PSI problems and the compiler diagnostics available for the source's language.
   *
   * A Scala file has the plugin's problems of at least weak-warning severity. When the host takes its errors from
   * the build's compiler, the compiler's problems follow: a Scala 3 `spec` compiles the current buffer, anything
   * else reuses `lastBuildProblems`. A Java file has the platform's syntax and semantic problems, without javac.
   *
   * @param path registered project-source path
   * @param spec compile settings of the source's module, if it has any
   * @param lastBuildProblems the last build's compiler diagnostics for this source
   * @return the plugin's problems, labelled `scala-plugin` or `java-plugin`, then the compiler's, labelled `scalac`;
   *         empty for a file that is neither Scala nor Java
   */
  def problems(path: Path, spec: Option[CompileSpec], lastBuildProblems: Seq[CompilerDiagnostic]): Seq[FileProblem] =
    file(path) match {
      case scalaFile: ScalaFile =>
        val reading = host.read {
          val sourceDocument = document(scalaFile)
          val plugin = host.diagnostics(scalaFile)
            .filter(_.severity.compareTo(HighlightSeverity.WEAK_WARNING) >= 0)
            .map { p =>
              FileProblem(problemSeverity(p.severity), position(sourceDocument, p.range.getStartOffset),
                position(sourceDocument, p.range.getEndOffset), p.message, "scala-plugin", p.inspectionId,
                p.deprecated, p.unnecessary)
            }

          PluginReading(plugin, host.isCompilerHighlighted(scalaFile), sourceDocument.getText)
        }

        val fromCompiler =
          if (!reading.compilerHighlighted) Seq.empty
          else {
            val reported = spec match {
              case Some(compileSpec) if compileSpec.scalaVersion.startsWith("3.") =>
                documentCompiler.compile(compileSpec, path.getFileName.toString, reading.text)
              case _ => lastBuildProblems
            }

            reported.map(c => FileProblem(problemSeverity(c.severity), c.start, c.end, c.message, "scalac"))
          }

        reading.problems ++ fromCompiler
      case javaFile: PsiJavaFile => host.read {
        val sourceDocument = document(javaFile)

        host.diagnostics(javaFile).map { problem =>
          FileProblem(problemSeverity(problem.severity), position(sourceDocument, problem.range.getStartOffset),
            position(sourceDocument, problem.range.getEndOffset), problem.message, "java-plugin")
        }
      }
      case _ => Seq.empty
    }

  /**
   * Renders a referenced or declared symbol with its language's signature and documentation.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position
   * @return Markdown with the signature in a fenced block, followed by the documentation when it can be read; `None`
   *         when no symbol is found or the plugin has no quick information for it
   */
  def hover(path: Path, at: LineColumn): Option[String] = host.read {
    val sourceFile = file(path)
    val atOffset = offset(document(sourceFile), at)
    // The referenced symbol, or the one declared at `at` when the cursor is on a declaration's name.
    val subject = Option(sourceFile.findReferenceAt(atOffset)) match {
      case Some(reference) => Option(reference.resolve()).map(_ -> reference.getElement)
      case None => declarationNamedAt(sourceFile, atOffset).map(declaration => declaration -> declaration)
    }

    for {
      (target, original) <- subject
      java = target.getNavigationElement.getContainingFile.isInstanceOf[PsiJavaFile]
      html <- if (java) Option(JavaDocumentationProvider.getQuickNavigationInfoInner(target, original))
              else ScalaDocQuickInfoGenerator.getQuickNavigateInfo(target, original)
    } yield {
      val signature = StringUtil.unescapeXmlEntities(StringUtil.removeHtmlTags(html, true)).trim
      val language = if (java) "java" else "scala"

      (s"```$language\n$signature\n```" +: documentation(target, original).toSeq).mkString("\n\n")
    }
  }

  /** Reads documentation through navigation to attached sources, retaining the signature if documentation fails. */
  private def documentation(target: PsiElement, original: PsiElement): Option[String] =
    try {
      val declaration = target.getNavigationElement
      val java = declaration.getContainingFile.isInstanceOf[PsiJavaFile]
      // The host supplies source attachments, not external Javadoc locations from IDE module extensions.
      val html = if (java) JavaDocumentationProvider.generateExternalJavadoc(declaration, null: JavaList[String])
                 else new ScalaDocumentationProvider().generateDoc(declaration, original)

      Option(html).flatMap(text => QuickDoc.toMarkdown(text, if (java) "java" else "scala"))
    }
    catch {
      // A linkage error: the generator reached an IDE class the host does not ship; the signature is still worth
      // showing.
      case e @ (NonFatal(_) | _: LinkageError) =>
        HostLog.warn(s"no documentation for ${target.getText.take(40)}", e)
        None
    }

  /** Finds a declaration whose name identifier contains `at`, when there is no reference at that offset. */
  private def declarationNamedAt(file: PsiFile, at: Int): Option[PsiNameIdentifierOwner] =
    Option(file.findElementAt(at)).flatMap { leaf =>
      Option(PsiTreeUtil.getParentOfType(leaf, classOf[PsiNameIdentifierOwner]))
        .filter(owner => owner.getNameIdentifier != null && owner.getNameIdentifier.getTextRange.contains(at))
    }

  /**
   * Locates the declaration referenced at a position.
   *
   * A library declaration is located in its attached source or decompiled text, materialized as a read-only file
   * under the host's state directory.
   *
   * @param path registered project-source path
   * @param at reference position
   * @return the declaration's file and the position of its name; `None` when nothing resolves there or the
   *         declaration is in neither a project source nor a library file
   */
  def definition(path: Path, at: LineColumn): Option[(Path, LineColumn)] = host.read {
    val sourceFile = file(path)

    for {
      reference <- Option(sourceFile.findReferenceAt(offset(document(sourceFile), at)))
      target <- Option(reference.resolve())
      location <- sourceLocation(target)
    } yield location.path -> location.position
  }

  /**
   * Locates the type declarations of the symbol referenced or declared at a position.
   *
   * Library declarations are located as `definition` locates them.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position
   * @return the declaration locations, distinct and in the order the type lookup finds them; empty when the symbol
   *         has no type declaration to navigate to
   */
  def typeDefinition(path: Path, at: LineColumn): Seq[(Path, LineColumn)] = host.read {
    val sourceFile = file(path)
    val atOffset = offset(document(sourceFile), at)
    val symbol = Option(sourceFile.findReferenceAt(atOffset))
      .flatMap(r => Option(r.resolve()))
      .orElse(declarationNamedAt(sourceFile, atOffset))

    val types = if (sourceFile.isInstanceOf[PsiJavaFile]) symbol.toSeq.flatMap {
      case clazz: PsiClass => Seq(clazz)
      case variable: PsiVariable => Option(PsiTypesUtil.getPsiClass(variable.getType.getDeepComponentType)).toSeq
      case method: PsiMethod if method.isConstructor => Option(method.getContainingClass).toSeq
      case method: PsiMethod =>
        Option(method.getReturnType).flatMap(t => Option(PsiTypesUtil.getPsiClass(t.getDeepComponentType))).toSeq
      case _ => Seq.empty
    } else symbol.flatMap { s =>
      Option(new ScalaGotoTypeDeclarationProvider().getSymbolTypeDeclarations(s))
    }.toSeq.flatten

    types.flatMap(sourceLocation).distinct.map(location => location.path -> location.position)
  }

  /** Where a declaration's name is in its file; an empty range at its start for one without a name identifier. */
  private def nameRange(declaration: PsiElement): TextRange = declaration match {
    case named: PsiNameIdentifierOwner if named.getNameIdentifier != null => named.getNameIdentifier.getTextRange
    case other => TextRange.from(other.getTextOffset, 0)
  }

  /** `range` of `file` as a span, if the file is a project source. */
  private def sourceSpan(file: PsiFile, range: TextRange): Option[SourceSpan] =
    file.getViewProvider.getVirtualFile match {
      case source: SourceFile =>
        val sourceDocument = document(file)

        Some(SourceSpan(
          source.path,
          LineRange(position(sourceDocument, range.getStartOffset), position(sourceDocument, range.getEndOffset))))
      case _ => None
    }

  /** The source-file range of a declaration's name, if that declaration belongs to a project source. */
  private def nameSpan(declaration: PsiElement): Option[SourceSpan] =
    Option(declaration.getContainingFile).flatMap(sourceSpan(_, nameRange(declaration)))

  /** Locates a declaration's navigation target, materializing attached or decompiled library text when needed. */
  private def sourceLocation(declaration: PsiElement): Option[SourceLocation] = {
    val target = declaration.getNavigationElement
    val nameOffset = nameRange(target).getStartOffset

    for {
      targetFile <- Option(target.getContainingFile)
      location <- targetFile.getViewProvider.getVirtualFile match {
        case source: SourceFile => Some(SourceLocation(source.path, position(document(targetFile), nameOffset)))
        case library: LibraryFile =>
          val text = targetFile.getText
          val at = StringUtil.offsetToLineColumn(text, nameOffset)

          Some(SourceLocation(libraryFileOnDisk(library, targetFile, text), LineColumn(at.line, at.column)))
        case _ => None
      }
    } yield location
  }

  /** Materializes library source or decompiled text as a read-only file under its root's state directory. */
  private def libraryFileOnDisk(file: LibraryFile, psi: PsiFile, text: String): Path = synchronized {
    // Serialized because concurrent requests may materialize the same file.
    val rootName = file.root.getName
    val rootBase = rootName.substring(rootName.lastIndexOf('/') + 1)
    val rootDirectory = s"$rootBase-${Integer.toHexString(rootName.hashCode)}"
    val entry = if (file.isLibrarySource) file.entry else {
      val extension = if (psi.isInstanceOf[ScalaFile]) ".scala" else ".java"
      file.entry.substring(0, file.entry.lastIndexOf('.')) + extension
    }
    val onDisk = host.stateDir.resolve("library-files").resolve(rootDirectory).resolve(entry)

    if (!Files.exists(onDisk) || Files.readString(onDisk) != text) {
      Files.createDirectories(onDisk.getParent)
      onDisk.toFile.setWritable(true)
      Files.writeString(onDisk, text)
      onDisk.toFile.setReadOnly()
    }

    onDisk
  }

  /**
   * Finds a symbol's usages across project sources, with its declaration's name if requested.
   *
   * Results follow the plugin's text-based find-usages support; usages that need compiler indexes are not found.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position
   * @param includeDeclaration whether to include the declaration's name when it belongs to a project source
   * @return the distinct spans, the declaration first; empty when the position has no usage target
   */
  def references(path: Path, at: LineColumn, includeDeclaration: Boolean): Seq[SourceSpan] = host.read {
    val sourceFile = file(path)

    ProjectSearch.usageTarget(sourceFile, offset(document(sourceFile), at)).toSeq.flatMap { target =>
      val usages = ProjectSearch.usages(target)
        .flatMap(usage => sourceSpan(usage.getFile, TextRange.create(usage.getNavigationRange)))
      val declaration = if (includeDeclaration) nameSpan(target.getNavigationElement) else None

      (declaration.toSeq ++ usages).distinct
    }
  }

  /**
   * Previews the plugin's applicable actions for a range, with their edits against the current document.
   *
   * The buffer is unchanged. Organize imports is included only when `only` requests its source-action kind or an
   * ancestor kind. Only Scala files have actions.
   *
   * @param path registered project-source path
   * @param start selection start
   * @param end selection end; equal to `start` for a caret request
   * @param only action kinds to accept, with their descendants; empty accepts every contextual fix and refactoring
   * @return each accepted action paired with its edits; empty for a file that is not Scala
   */
  def codeActions(path: Path, start: LineColumn, end: LineColumn, only: Seq[String] = Seq.empty)
      : Seq[(CodeActionResult, Seq[TextChange])] = host.read {
    file(path) match {
      case sourceFile: ScalaFile =>
        val sourceDocument = document(sourceFile)

        /** Whether `kind` is a requested kind or a descendant of one; every kind when none was requested. */
        def accepts(kind: String): Boolean = only.isEmpty || only.exists(k => kind == k || kind.startsWith(k + "."))

        val contextual =
          if (accepts("quickfix") || accepts("refactor.rewrite"))
            host.codeActions(sourceFile, new TextRange(offset(sourceDocument, start), offset(sourceDocument, end)))
              .filter(choice => accepts(choice.kind))
          else Seq.empty
        val source =
          if (only.nonEmpty && accepts("source.organizeImports")) Seq(host.organizeImports(sourceFile))
          else Seq.empty
        val choices = contextual ++ source

        choices.map { choice =>
          CodeActionResult(choice.title, choice.kind, choice.disabled) ->
            choice.edits.map(textChange(sourceDocument, _))
        }
      case _ => Seq.empty
    }
  }

  /**
   * Validates the rename target and locates its name.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position
   * @return start and end of the name to rename
   * @throws Rename.Refused when the position has no renameable project-source target or no name
   */
  def prepareRename(path: Path, at: LineColumn): LineRange = {
    val sourceFile = file(path)
    val cursor = host.read(offset(document(sourceFile), at))

    host.renameTarget(sourceFile, cursor)

    host.read {
      val reference = Option(sourceFile.findReferenceAt(cursor))
        .map(r => r.getRangeInElement.shiftRight(r.getElement.getTextRange.getStartOffset))
      val name = reference.orElse(declarationNamedAt(sourceFile, cursor).map(_.getNameIdentifier.getTextRange))
        .getOrElse(throw new Rename.Refused(Seq("There is no name to rename here.")))

      LineRange(position(document(sourceFile), name.getStartOffset), position(document(sourceFile), name.getEndOffset))
    }
  }

  /**
   * Previews the project-wide edits that rename a symbol, without retaining source changes.
   *
   * The host restores the files' original texts before this returns; the caller applies the edits and reports the
   * resulting text through `change`. The host's rename cancels queries running meanwhile.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position
   * @param newName replacement name, validated by the plugin's rename processors
   * @return the edits of each affected project-source file; files outside the project sources are left out
   * @throws Rename.Refused when the target, the name or a conflict is refused
   */
  def rename(path: Path, at: LineColumn, newName: String): Seq[FileChanges] = {
    val sourceFile = file(path)
    val edits = host.rename(sourceFile, host.read(offset(document(sourceFile), at)), newName)

    host.read(edits.flatMap { (changed, fileEdits) =>
      changed.getViewProvider.getVirtualFile match {
        case source: SourceFile => Some(FileChanges(source.path, fileEdits.map(textChange(document(changed), _))))
        case _ => None
      }
    })
  }

  /**
   * Locates the implementations and overriders of the symbol at a position, in project sources only.
   *
   * @param path registered project-source path
   * @param at symbol position
   * @return the spans of the declarations' names, distinct and in the order the search finds them
   */
  def implementations(path: Path, at: LineColumn): Seq[SourceSpan] = host.read {
    val sourceFile = file(path)

    ProjectSearch.implementations(sourceFile, offset(document(sourceFile), at)).flatMap(nameSpan).distinct
  }

  /** A hierarchy declaration located by the same navigation/materialization path as definition. */
  private def hierarchyItem(element: PsiNamedElement, origin: Path): Option[HierarchyItem] = {
    val navigation = element.getNavigationElement
    val targetName = navigation match {
      case named: PsiNamedElement => named.getName
      case _ => element.getName
    }

    for {
      location <- if (navigation.getTextOffset < 0) None else sourceLocation(element)
      targetFile <- Option(navigation.getContainingFile)
      target <- targetFile.getViewProvider.getVirtualFile match {
        case source: SourceFile => Some(HierarchyTarget(source.path.toUri.toString,
          nameRange(navigation).getStartOffset, targetName, None))
        case _: LibraryFile =>
          val owner = element match {
            case clazz: PsiClass => Some(clazz)
            case method: PsiMethod => Option(method.getContainingClass)
            case _ => None
          }

          owner.flatMap(c => Option(c.getQualifiedName)).map(q =>
            HierarchyTarget(origin.toUri.toString, nameRange(navigation).getStartOffset, targetName, Some(q)))
        case _ => None
      }
    } yield {
      HierarchyItem(element.getName, workspaceSymbolKind(element),
        SourceSpan(location.path, fileRange(targetFile, navigation.getTextRange)),
        fileRange(targetFile, nameRange(navigation)), target)
    }
  }

  /** Converts ranges in either a source buffer or library PSI, which need not have a document. */
  private def fileRange(file: PsiFile, range: TextRange): LineRange = {
    val text = file.getText

    /** Converts an offset of `text` to its zero-based line and UTF-16 column. */
    def at(offset: Int): LineColumn = {
      val position = StringUtil.offsetToLineColumn(text, offset)
      LineColumn(position.line, position.column)
    }

    LineRange(at(range.getStartOffset), at(range.getEndOffset))
  }

  /** Re-resolves request data to a class or method, or `None` when its name or offset no longer matches. */
  private def hierarchyElement(path: Path, target: HierarchyTarget, types: Boolean): Option[PsiNamedElement] = {
    val source = file(path)

    val element = target.libraryClass match {
      case Some(qualified) =>
        val scope = host.modules.resolveScope(source.getViewProvider.getVirtualFile)
          .getOrElse(GlobalSearchScope.allScope(host.project))
        Option(JavaPsiFacade.getInstance(host.project).findClass(qualified, scope)).flatMap { clazz =>
          if (types) Some(clazz)
          else {
            // Java-facing Scala wrappers have the same location but are not the Scala search target.
            val methods = (clazz.getMethods ++ clazz.getConstructors).toSeq.map {
              case wrapper: ScFunctionWrapper => wrapper.delegate
              case method => method
            }.distinct
            // Keep binary PSI for search identity; attached-source PSI supplies only the location.
            val matching = methods.filter { method =>
              val navigation = method.getNavigationElement
              method.getName == target.name && navigation.getTextOffset >= 0 &&
                nameRange(navigation).getStartOffset == target.offset
            }
            matching match {
              case Seq(method) => Some(method)
              case _ => None
            }
          }
        }
      case None if target.offset >= 0 && target.offset < source.getTextLength =>
        if (types) ProjectSearch.typeTarget(source, target.offset)
        else ProjectSearch.callTarget(source, target.offset)
      case _ => None
    }
    element.filter(e => e.getName == target.name && nameRange(e.getNavigationElement).getStartOffset == target.offset)
  }

  /**
   * Prepares the IDE's call hierarchy method or type hierarchy class at a position.
   *
   * @param path registered project-source path
   * @param at position of the method or class
   * @param types `true` for the type hierarchy's class, `false` for the call hierarchy's method
   * @return the item for the target, locatable in a project source or a navigable library; empty when the position
   *         has no target or the target cannot be located
   */
  def prepareHierarchy(path: Path, at: LineColumn, types: Boolean): Seq[HierarchyItem] = host.read {
    val source = file(path)
    val cursor = offset(document(source), at)
    val target = if (types) ProjectSearch.typeTarget(source, cursor)
      else ProjectSearch.callTarget(source, cursor)

    target.toSeq.flatMap(hierarchyItem(_, path))
  }

  /**
   * Resolves a call-hierarchy item and finds its call edges, with project callers and the plugin's default callee
   * scope.
   *
   * @param path registered project-source path the request started from
   * @param target item to expand, as `prepareHierarchy` returned it
   * @param incoming `true` for the callers of the item, `false` for the methods it calls
   * @return one edge per caller or callee, with the call sites in the caller's document for incoming edges; empty
   *         when the target no longer resolves to a method
   */
  def hierarchyCalls(path: Path, target: HierarchyTarget, incoming: Boolean): Seq[HierarchyCall] = host.read {
    hierarchyElement(path, target, types = false).toSeq.collect { case method: PsiMethod => method }.flatMap { method =>
      if (incoming) ProjectSearch.incomingCalls(method).flatMap { (caller, references) =>
        for {
          named <- Option(caller).collect { case declaration: PsiNamedElement => declaration }
          _ <- nameSpan(named)
          item <- hierarchyItem(named, path)
        } yield HierarchyCall(item, references.flatMap((source, range) => sourceSpan(source, range).map(_.range)))
      }
      else ProjectSearch.outgoingCalls(method).flatMap { (callee, ranges) =>
        hierarchyItem(callee, path).map { item =>
          HierarchyCall(item, ranges.map(fileRange(method.getNavigationElement.getContainingFile, _)))
        }
      }
    }
  }

  /**
   * Resolves a type-hierarchy item and finds its direct superclasses or project-source subclasses, including
   * navigable library supertypes.
   *
   * @param path registered project-source path the request started from
   * @param target item to expand, as `prepareHierarchy` returned it
   * @param subtypes `true` for the subclasses, `false` for the supertypes
   * @return the items found; empty when the target no longer resolves to a class
   */
  def hierarchyTypes(path: Path, target: HierarchyTarget, subtypes: Boolean): Seq[HierarchyItem] = host.read {
    hierarchyElement(path, target, types = true).toSeq.collect { case clazz: PsiClass => clazz }
      .flatMap(ProjectSearch.typeChildren(_, subtypes)).filter(c => !subtypes || nameSpan(c).nonEmpty)
      .flatMap(hierarchyItem(_, path))
  }

  /**
   * Searches the project's symbols by name. Symbols outside the project sources are left out.
   *
   * @param query symbol-name query for the plugin's name matching
   * @return the matches, best first
   */
  def workspaceSymbols(query: String): Seq[WorkspaceSymbolMatch] = host.read {
    ProjectSearch.symbols(host.project, query).flatMap { found =>
      nameSpan(found.element).map { span =>
        WorkspaceSymbolMatch(found.element.name, workspaceSymbolKind(found.element), found.container, span)
      }
    }
  }

  /**
   * Finds a symbol's declaration and references within one document, classifying writes with the plugin's detector.
   *
   * @param path registered project-source path
   * @param at referenced or declared symbol position
   * @return the distinct occurrences, the declaration first when it is in this document; empty when no named symbol
   *         is at the position
   */
  def highlights(path: Path, at: LineColumn): Seq[Occurrence] = host.read {
    val sourceFile = file(path)
    val sourceDocument = document(sourceFile)
    val atOffset = offset(sourceDocument, at)
    val target = Option(sourceFile.findReferenceAt(atOffset)) match {
      case Some(reference) => Option(reference.resolve())
      case None => declarationNamedAt(sourceFile, atOffset)
    }

    target.collect { case named: PsiNamedElement if named.getName != null => named }.toSeq.flatMap { named =>
      val access: ReadWriteAccessDetector = sourceFile match {
        case _: PsiJavaFile => new JavaReadWriteAccessDetector
        case _ => new ScalaReadWriteAccessDetector
      }

      /** Converts a PSI range within this document to workspace coordinates. */
      def span(range: TextRange): LineRange =
        LineRange(position(sourceDocument, range.getStartOffset), position(sourceDocument, range.getEndOffset))

      val declaration = named.getNavigationElement match {
        case owner: PsiNameIdentifierOwner
            if owner.getContainingFile == sourceFile && owner.getNameIdentifier != null =>
          Seq(Occurrence(span(owner.getNameIdentifier.getTextRange), write = access.isDeclarationWriteAccess(owner)))
        case _ => Seq.empty
      }
      // Only references whose text names the symbol can refer to it (the IDE's search also looks for the name's
      // occurrences first); resolving every reference of the file would be the whole highlighting pass.
      val references = SyntaxTraverser.psiTraverser(sourceFile).asScala.toSeq
        .flatMap(_.getReferences.toSeq)
        .filter { reference =>
          val text = reference.getRangeInElement.substring(reference.getElement.getText)
          ScalaNamesUtil.equivalent(text, named.getName) && reference.isReferenceTo(named)
        }

      declaration ++ references.map { reference =>
        val write = access.getReferenceAccess(named, reference) == ReadWriteAccessDetector.Access.Write
        val range = reference.getRangeInElement.shiftRight(reference.getElement.getTextRange.getStartOffset)

        Occurrence(span(range), write)
      }
    }.distinct
  }

  /**
   * Builds the file's declaration outline from its language's structure view, without inherited members.
   *
   * @param path registered project-source path
   * @return the top-level symbols with their nested declarations as children; empty for a file that is neither
   *         Scala nor Java
   */
  def outline(path: Path): Seq[OutlineSymbol] = host.read {
    file(path) match {
      case scalaFile: ScalaFile =>
        val sourceDocument = document(scalaFile)

        /** Converts a PSI range within this document to workspace coordinates. */
        def span(range: TextRange): LineRange =
          LineRange(position(sourceDocument, range.getStartOffset), position(sourceDocument, range.getEndOffset))

        /** Flattens unnamed initializer blocks and retains nested named declarations. */
        def symbols(node: TreeElement): Seq[OutlineSymbol] = node match {
          case element: Element =>
            val children = element.getChildren.toSeq.flatMap(symbols)

            element.element match {
              // An initializer block has no name: its declarations are shown under the enclosing one.
              case _: ScBlock => children
              case psi =>
                val text = element.getPresentation.getPresentableText
                val (name, detail) = psi match {
                  case named: ScNamedElement if text.startsWith(named.name) =>
                    named.name -> text.drop(named.name.length)
                  case _ => text -> ""
                }
                // A val's or var's binding is the element; its definition is the whole statement.
                val whole = psi match {
                  case named: ScNamedElement => named.nameContext
                  case other => other
                }
                val nameRange = psi match {
                  case named: ScNamedElement => named.nameId.getTextRange
                  case other => TextRange.from(other.getTextOffset, 0)
                }

                Seq(OutlineSymbol(name, detail, workspaceSymbolKind(psi), span(whole.getTextRange), span(nameRange),
                  children))
            }
          case _ => Seq.empty
        }

        Element(() => scalaFile).getChildren.toSeq.flatMap(symbols)
      case javaFile: PsiJavaFile =>
        val sourceDocument = document(javaFile)

        /** Converts a PSI range within this document to workspace coordinates. */
        def span(range: TextRange): LineRange =
          LineRange(position(sourceDocument, range.getStartOffset), position(sourceDocument, range.getEndOffset))

        /** Builds a named declaration's symbol with its members in source order; an unnamed one yields its members. */
        def symbols(psi: PsiNameIdentifierOwner): Seq[OutlineSymbol] = {
          val members = psi match {
            case clazz: PsiClass =>
              (clazz.getChildren.toSeq.collect {
                case member: PsiClass => member
                case member: PsiMethod => member
                case member: PsiField => member
              } ++ clazz.getRecordComponents).sortBy(_.getTextOffset)
            case _ => Seq.empty
          }
          val children = members.flatMap(symbols)

          if (psi.getName == null || psi.getNameIdentifier == null) children
          else Seq(OutlineSymbol(psi.getName, "", workspaceSymbolKind(psi), span(psi.getTextRange),
            span(psi.getNameIdentifier.getTextRange), children))
        }

        javaFile.getClasses.toSeq.flatMap(symbols)
      case _ => Seq.empty
    }
  }

  /**
   * Computes the file's folding regions, leaving a closing-bracket line visible.
   *
   * @param path registered project-source path
   * @return the distinct multiline regions, each with its first and last folded line; empty for a file that is
   *         neither Scala nor Java
   */
  def foldingRanges(path: Path): Seq[Fold] = host.read {
    file(path) match {
      case scalaFile: ScalaFile =>
        val sourceDocument = document(scalaFile)
        val text = sourceDocument.getCharsSequence

        new ScalaFoldingBuilder()
          .buildFoldRegions(scalaFile, sourceDocument, /* quick = */ false)
          .toSeq
          .flatMap { descriptor =>
            val range = descriptor.getRange
            val startLine = sourceDocument.getLineNumber(range.getStartOffset)
            val lastLine = sourceDocument.getLineNumber(range.getEndOffset)
            val closes = range.getLength > 0 && "})]".contains(text.charAt(range.getEndOffset - 1))
            val endLine = if (closes && lastLine > startLine) lastLine - 1 else lastLine
            val node = descriptor.getElement
            val kind = node.getElementType match {
              case t if ScalaTokenTypes.COMMENTS_TOKEN_SET.contains(t) =>
                if (CustomFoldingBuilder.isCustomRegionElement(node.getPsi)) Fold.Region else Fold.Comment
              case t if t == ScalaDocElementTypes.SCALA_DOC_COMMENT => Fold.Comment
              case t if t == ScalaElementType.ImportStatement => Fold.Imports
              case _ => Fold.Code
            }

            Option.when(endLine > startLine)(Fold(startLine, endLine, kind))
          }
          .distinct
      case javaFile: PsiJavaFile =>
        val sourceDocument = document(javaFile)

        SyntaxTraverser.psiTraverser(javaFile).asScala.toSeq.flatMap { element =>
          ProgressManager.checkCanceled()

          val region = element match {
            case clazz: PsiClass =>
              for {
                left <- Option(clazz.getLBrace)
                right <- Option(clazz.getRBrace)
              } yield (new TextRange(left.getTextRange.getStartOffset, right.getTextRange.getEndOffset), Fold.Code)
            case block: PsiCodeBlock => Some(block.getTextRange -> Fold.Code)
            case comment: PsiComment => Some(comment.getTextRange -> Fold.Comment)
            case imports: PsiImportList => Some(imports.getTextRange -> Fold.Imports)
            case _ => None
          }

          region.flatMap { (range, kind) =>
            val startLine = sourceDocument.getLineNumber(range.getStartOffset)
            val lastLine = sourceDocument.getLineNumber(range.getEndOffset)
            val endLine = if (kind == Fold.Code && lastLine > startLine) lastLine - 1 else lastLine

            Option.when(endLine > startLine)(Fold(startLine, endLine, kind))
          }
        }.distinct
      case _ => Seq.empty
    }
  }

  /**
   * Classifies the file's identifiers and soft keywords as the IDE colors them (see [[ScalaPsiHost.textAttributes]]).
   *
   * Only single-line classifications that this class maps to a token kind are returned.
   *
   * @param path registered project-source path
   * @return the tokens with their start position, length, kind and modifiers, in the order the host returns the
   *         attributes; empty for a file that is not Scala
   */
  def semanticTokens(path: Path): Seq[SemanticToken] = file(path) match {
    case scalaFile: ScalaFile =>
      host.read {
        val sourceDocument = document(scalaFile)

        host.textAttributes(scalaFile).flatMap { (range, key) =>
          if (range.isEmpty ||
              sourceDocument.getLineNumber(range.getStartOffset) != sourceDocument.getLineNumber(range.getEndOffset))
            None
          else semanticToken(key).map { style =>
            SemanticToken(position(sourceDocument, range.getStartOffset), range.getLength, style.kind, style.modifiers)
          }
        }
      }
    case _ => Seq.empty
  }

  /** Maps platform diagnostic levels to the language model's severity. */
  private def problemSeverity(severity: HighlightSeverity): ProblemSeverity =
    if (severity.compareTo(HighlightSeverity.ERROR) >= 0) ProblemSeverity.Error
    else if (severity.compareTo(HighlightSeverity.WARNING) >= 0) ProblemSeverity.Warning
    else ProblemSeverity.Information

  /** Classifies Scala and Java declarations for workspace symbols, outlines and hierarchy items. */
  private def workspaceSymbolKind(declaration: PsiElement): WorkspaceSymbolKind = declaration match {
    case _: ScEnumCase => WorkspaceSymbolKind.EnumMember
    case _: ScEnum => WorkspaceSymbolKind.Enum
    case _: ScTrait => WorkspaceSymbolKind.Interface
    case _: ScObject => WorkspaceSymbolKind.Module
    case _: ScTypeDefinition => WorkspaceSymbolKind.Class
    case function: ScFunction if function.isConstructor => WorkspaceSymbolKind.Constructor
    case function: ScFunction =>
      if (function.containingClass == null) WorkspaceSymbolKind.Function else WorkspaceSymbolKind.Method
    case _: ScClassParameter => WorkspaceSymbolKind.Property
    case _: ScTypeAlias => WorkspaceSymbolKind.Class
    case _: ScExtension => WorkspaceSymbolKind.Namespace
    case named: ScNamedElement => named.nameContext match {
      case _: ScVariable => WorkspaceSymbolKind.Variable
      case _ => WorkspaceSymbolKind.Constant
    }
    case _: PsiEnumConstant => WorkspaceSymbolKind.EnumMember
    case clazz: PsiClass if clazz.isEnum => WorkspaceSymbolKind.Enum
    case clazz: PsiClass if clazz.isInterface => WorkspaceSymbolKind.Interface
    case _: PsiClass => WorkspaceSymbolKind.Class
    case method: PsiMethod if method.isConstructor => WorkspaceSymbolKind.Constructor
    case _: PsiMethod => WorkspaceSymbolKind.Method
    case _: PsiRecordComponent => WorkspaceSymbolKind.Property
    case _: PsiField => WorkspaceSymbolKind.Field
    case _ => WorkspaceSymbolKind.Object
  }

  /** Maps plugin highlighting keys to protocol-neutral token categories and modifiers. */
  private def semanticToken(key: TextAttributesKey): Option[TokenStyle] = {
    import SemanticTokenKind.*
    import SemanticTokenModifier.*
    key match {
      case H.KEYWORD => Some(TokenStyle(Keyword, Set.empty))
      case H.PACKAGE => Some(TokenStyle(Namespace, Set.empty))
      case H.CLASS => Some(TokenStyle(Class, Set.empty))
      case H.ABSTRACT_CLASS => Some(TokenStyle(Class, Set(Abstract)))
      case H.OBJECT => Some(TokenStyle(Class, Set(Readonly)))
      case H.TRAIT => Some(TokenStyle(Interface, Set.empty))
      case H.ENUM => Some(TokenStyle(Enum, Set.empty))
      case H.ENUM_SINGLETON_CASE | H.ENUM_CLASS_CASE => Some(TokenStyle(EnumMember, Set.empty))
      case H.TYPEPARAM => Some(TokenStyle(TypeParameter, Set.empty))
      case H.TYPE_ALIAS => Some(TokenStyle(Type, Set.empty))
      case H.LOCAL_VALUES | H.LOCAL_LAZY | H.VALUES | H.LAZY | H.PATTERN | H.GENERATOR | H.GIVEN =>
        Some(TokenStyle(Variable, Set(Readonly)))
      case H.LOCAL_VARIABLES | H.VARIABLES => Some(TokenStyle(Variable, Set.empty))
      case H.CASE_CLASS_FIELD => Some(TokenStyle(Property, Set(Readonly)))
      case H.CASE_CLASS_VAR_FIELD => Some(TokenStyle(Property, Set.empty))
      case H.PARAMETER | H.PARAMETER_OF_ANONIMOUS_FUNCTION | H.NAMED_ARGUMENT => Some(TokenStyle(Parameter, Set.empty))
      case H.METHOD_CALL | H.OBJECT_METHOD_CALL => Some(TokenStyle(Method, Set.empty))
      case H.LOCAL_METHOD_CALL => Some(TokenStyle(Function, Set.empty))
      case H.METHOD_DECLARATION => Some(TokenStyle(Method, Set(Declaration)))
      case H.ANNOTATION => Some(TokenStyle(Decorator, Set.empty))
      case H.ANNOTATION_ATTRIBUTE => Some(TokenStyle(Property, Set.empty))
      case _ => None
    }
  }
}
