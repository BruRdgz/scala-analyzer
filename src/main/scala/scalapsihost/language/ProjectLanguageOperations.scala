package scalapsihost.language

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.java.JavaDocumentationProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{PsiClass, PsiDocumentManager, PsiElement, PsiEnumConstant, PsiField, PsiFile, PsiJavaFile, PsiMethod, PsiNameIdentifierOwner, PsiNamedElement, PsiRecordComponent, SyntaxTraverser}
import org.jetbrains.plugins.scala.extensions.PsiNamedElementExt
import org.jetbrains.plugins.scala.editor.documentationProvider.{ScalaDocQuickInfoGenerator, ScalaDocumentationProvider}
import org.jetbrains.plugins.scala.highlighter.DefaultHighlighter as H
import org.jetbrains.plugins.scala.lang.folding.ScalaFoldingBuilder
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScBlock
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScEnumCase, ScExtension, ScFunction, ScTypeAlias, ScVariable}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScClassParameter
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScEnum, ScObject, ScTrait, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.structureView.element.Element

import scalapsihost.{LineColumn, ScalaPsiHost}
import scalapsihost.build.{CompileSpec, CompilerDiagnostic, DocumentCompiler, ScalafmtFormatting}
import scalapsihost.platform.{HostLog, LibraryFile, SourceFile}

import java.nio.file.{Files, Path}
import java.util.{List as JavaList}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** PSI-backed navigation, document operations and language results for a project session. */
private[scalapsihost] final class ProjectLanguageOperations(
  host: ScalaPsiHost,
  root: Path,
  documentCompiler: DocumentCompiler,
  scalafmt: ScalafmtFormatting,
  file: Path => PsiFile,
) {
  /** Gets the in-memory document owned by the platform for `file`. */
  private def document(file: PsiFile): Document = PsiDocumentManager.getInstance(host.project).getDocument(file)

  /** Converts a workspace line/UTF-16 column to a document offset, clamping positions past a line's end. */
  private def offset(document: Document, position: LineColumn): Int =
    if (position.line >= document.getLineCount) document.getTextLength
    else math.min(document.getLineStartOffset(position.line) + position.column, document.getLineEndOffset(position.line))

  /** Converts a document offset to its zero-based line and UTF-16 column. */
  private def position(document: Document, offset: Int): LineColumn = {
    val line = document.getLineNumber(offset)
    LineColumn(line, offset - document.getLineStartOffset(line))
  }

  /** The current in-memory document text, including changes not yet saved to disk. */
  def text(path: Path): String = host.read(file(path).getText)

  /** The completion suggestions at `at`, best first. Only Scala files complete. */
  def completion(path: Path, at: LineColumn): CompletionChoices = file(path) match {
    case sourceFile: ScalaFile =>
      host.read {
        val sourceDocument = document(sourceFile)
        val suggestions = host.completion(sourceFile, offset(sourceDocument, at))
        val choices = suggestions.items.map { suggestion =>
          val candidate = CompletionCandidate(suggestion.label, suggestion.tail, suggestion.typeText, suggestion.kind,
            suggestion.deprecated, suggestion.lookup, suggestion.prefixText, suggestion.hasHandler, suggestion.identity)
          CompletionChoice(candidate, position(sourceDocument, suggestion.prefix.getStartOffset), position(sourceDocument, suggestion.prefix.getEndOffset))
        }
        CompletionChoices(choices, suggestions.incomplete)
      }
    case _ => CompletionChoices(Seq.empty, incomplete = false)
  }

  /** The plugin's inline hints within the requested range. Only Scala files have them. */
  def inlayHints(path: Path, start: LineColumn, end: LineColumn): Seq[InlineHint] = file(path) match {
    case sourceFile: ScalaFile => host.read {
      val sourceDocument = document(sourceFile)
      val range = new TextRange(offset(sourceDocument, start), offset(sourceDocument, end))
      host.inlayHints(sourceFile).filter(hint => range.contains(hint.offset)).map(hint => InlineHint(position(sourceDocument, hint.offset), hint.text))
    }
    case _ => Seq.empty
  }

  /** The signatures of the call around `at`. Only Scala files have them. */
  def signatures(path: Path, at: LineColumn): Option[SignatureChoices] = file(path) match {
    case sourceFile: ScalaFile => host.read(host.parameterInfo(sourceFile, offset(document(sourceFile), at))).map { found =>
      SignatureChoices(found.items.map { signature =>
        SignatureInfo(signature.text, signature.current.map(r => OffsetRange(r.getStartOffset, r.getEndOffset)),
          signature.applicable, signature.deprecated)
      }, found.active)
    }
    case _ => None
  }

  /** Each position's Extend Selection chain. An unexpandable caret still has its empty range, as LSP requires. */
  def selectionRanges(path: Path, positions: Seq[LineColumn]): Seq[Seq[(LineColumn, LineColumn)]] = file(path) match {
    case sourceFile: ScalaFile => host.read {
      val sourceDocument = document(sourceFile)
      positions.map { at =>
        val cursor = offset(sourceDocument, at)
        val ranges = host.selectionRanges(sourceFile, cursor)
        val chain = if (ranges.isEmpty) Seq(new TextRange(cursor, cursor)) else ranges
        chain.map(r => position(sourceDocument, r.getStartOffset) -> position(sourceDocument, r.getEndOffset))
      }
    }
    case _ => Seq.empty
  }

  /** Previews insertion follow-up after the editor has replaced the typed prefix with the chosen lookup text. */
  def insertCompletion(path: Path, start: LineColumn, prefixText: String, lookup: String, identity: String)
      : Option[CompletionFollowUp] =
    file(path) match {
      case sourceFile: ScalaFile =>
        val startOffset = host.read(offset(document(sourceFile), start))
        host.insertCompletion(sourceFile, startOffset, prefixText, lookup, identity).map { inserted =>
          val changes = host.read(inserted.result.edits.map(textChange(document(sourceFile), _)))
          val caret = StringUtil.offsetToLineColumn(inserted.result.text, inserted.caret)
          CompletionFollowUp(changes, LineColumn(caret.line, caret.column))
        }
      case _ => None
    }

  /** The changes that format `range` of the file (all of it without one). Only Scala files are formatted. */
  def formatting(path: Path, range: Option[(LineColumn, LineColumn)], indentSize: Int, useTabs: Boolean): Seq[TextChange] =
    file(path) match {
      case sourceFile: ScalaFile =>
        val selected = host.read(range.map { (start, end) =>
          new TextRange(offset(document(sourceFile), start), offset(document(sourceFile), end))
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

  /** Commits a range replacement, or replaces the complete document when no range is supplied. */
  def change(path: Path, range: Option[(LineColumn, LineColumn)], replacement: String): Unit = {
    val sourceFile = file(path)
    // Only this thread edits documents, so reading one needs no lock; and an edit must not be cancelled as the
    // queries a newer edit cancels are.
    val sourceDocument = document(sourceFile)
    val (start, end) = range.fold((0, sourceDocument.getTextLength))((from, to) => (offset(sourceDocument, from), offset(sourceDocument, to)))
    host.edit(sourceFile, start, end, replacement)
  }

  /** Combines plugin diagnostics with current-text Scala 3 diagnostics or the build's latest compiler problems. */
  def problems(path: Path, spec: Option[CompileSpec], lastBuildProblems: Seq[CompilerDiagnostic]): Seq[FileProblem] =
    file(path) match {
      case scalaFile: ScalaFile =>
        val (fromPlugin, compilerHighlighted, text) = host.read {
          val sourceDocument = document(scalaFile)
          val plugin = host.diagnostics(scalaFile).filter(_.severity.compareTo(HighlightSeverity.WEAK_WARNING) >= 0).map { p =>
            FileProblem(problemSeverity(p.severity), position(sourceDocument, p.range.getStartOffset),
              position(sourceDocument, p.range.getEndOffset), p.message, "scala-plugin")
          }
          (plugin, host.isCompilerHighlighted(scalaFile), sourceDocument.getText)
        }
        val fromCompiler =
          if (!compilerHighlighted) Seq.empty
          else {
            val reported = spec match {
              case Some(compileSpec) if compileSpec.scalaVersion.startsWith("3.") =>
                documentCompiler.compile(compileSpec, path.getFileName.toString, text)
              case _ => lastBuildProblems
            }
            reported.map(c => FileProblem(problemSeverity(c.severity), c.start, c.end, c.message, "scalac"))
          }
        fromPlugin ++ fromCompiler
      case javaFile: PsiJavaFile => host.read {
        val sourceDocument = document(javaFile)
        host.syntaxErrors(javaFile).map { problem =>
          FileProblem(problemSeverity(problem.severity), position(sourceDocument, problem.range.getStartOffset),
            position(sourceDocument, problem.range.getEndOffset), problem.message, "java-parser")
        }
      }
      case _ => Seq.empty
    }

  /** Renders a referenced or declared symbol with its language's signature and documentation. */
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
      // A linkage error: the generator reached an IDE class the host does not ship; the signature is still worth showing.
      case e @ (NonFatal(_) | _: LinkageError) =>
        HostLog.warn(s"no documentation for ${target.getText.take(40)}", e)
        None
    }

  /** Finds a declaration whose name identifier contains `at`, when there is no reference at that offset. */
  private def declarationNamedAt(file: PsiFile, at: Int): Option[PsiNameIdentifierOwner] =
    Option(file.findElementAt(at)).flatMap { leaf =>
      Option(com.intellij.psi.util.PsiTreeUtil.getParentOfType(leaf, classOf[PsiNameIdentifierOwner]))
        .filter(owner => owner.getNameIdentifier != null && owner.getNameIdentifier.getTextRange.contains(at))
    }

  /** Finds a referenced declaration in project sources or a materialized library file. */
  def definition(path: Path, at: LineColumn): Option[(Path, LineColumn)] = host.read {
    val sourceFile = file(path)
    for {
      reference <- Option(sourceFile.findReferenceAt(offset(document(sourceFile), at)))
      target <- Option(reference.resolve())
      location <- sourceLocation(target)
    } yield location
  }

  /** Finds type declarations in project sources or materialized library files. */
  def typeDefinition(path: Path, at: LineColumn): Seq[(Path, LineColumn)] = host.read {
    val sourceFile = file(path)
    val atOffset = offset(document(sourceFile), at)
    val symbol = Option(sourceFile.findReferenceAt(atOffset)).flatMap(r => Option(r.resolve())).orElse(declarationNamedAt(sourceFile, atOffset))
    val types = symbol.flatMap(s => Option(new org.jetbrains.plugins.scala.annotator.gutter.ScalaGotoTypeDeclarationProvider().getSymbolTypeDeclarations(s)))
    types.toSeq.flatten.flatMap(sourceLocation)
  }

  /** Where a declaration's name is in its file; an empty range at its start for one without a name identifier. */
  private def nameRange(declaration: PsiElement): TextRange = declaration match {
    case named: PsiNameIdentifierOwner if named.getNameIdentifier != null => named.getNameIdentifier.getTextRange
    case other => TextRange.from(other.getTextOffset, 0)
  }

  /** `range` of `file` as a span, if the file is a project source. */
  private def sourceSpan(file: PsiFile, range: TextRange): Option[SourceSpan] = file.getViewProvider.getVirtualFile match {
    case source: SourceFile =>
      val sourceDocument = document(file)
      Some(SourceSpan(source.path, position(sourceDocument, range.getStartOffset) -> position(sourceDocument, range.getEndOffset)))
    case _ => None
  }

  /** The source-file range of a declaration's name, if that declaration belongs to a project source. */
  private def nameSpan(declaration: PsiElement): Option[SourceSpan] =
    Option(declaration.getContainingFile).flatMap(sourceSpan(_, nameRange(declaration)))

  /** Locates a declaration's navigation target, materializing attached or decompiled library text when needed. */
  private def sourceLocation(declaration: PsiElement): Option[(Path, LineColumn)] = {
    val target = declaration.getNavigationElement
    val nameOffset = nameRange(target).getStartOffset
    for {
      targetFile <- Option(target.getContainingFile)
      location <- targetFile.getViewProvider.getVirtualFile match {
        case source: SourceFile => Some(source.path -> position(document(targetFile), nameOffset))
        case library: LibraryFile =>
          val text = targetFile.getText
          val at = StringUtil.offsetToLineColumn(text, nameOffset)
          Some(libraryFileOnDisk(library, targetFile, text) -> LineColumn(at.line, at.column))
        case _ => None
      }
    } yield location
  }

  /** Materializes library source or decompiled text under its root-specific state directory as a read-only file. */
  private def libraryFileOnDisk(file: LibraryFile, psi: PsiFile, text: String): Path = {
    val rootName = file.root.getName
    val rootDirectory = s"${rootName.substring(rootName.lastIndexOf('/') + 1)}-${Integer.toHexString(rootName.hashCode)}"
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

  /** Collects project-source usages and optionally the declaration's name, omitting duplicate spans. */
  def references(path: Path, at: LineColumn, includeDeclaration: Boolean): Seq[SourceSpan] = host.read {
    val sourceFile = file(path)
    ProjectSearch.usageTarget(sourceFile, offset(document(sourceFile), at)).toSeq.flatMap { target =>
      val usages = ProjectSearch.usages(target).flatMap(usage => sourceSpan(usage.getFile, TextRange.create(usage.getNavigationRange)))
      val declaration = if (includeDeclaration) nameSpan(target.getNavigationElement) else None
      (declaration.toSeq ++ usages).distinct
    }
  }

  /** Applicable plugin actions, with edits against the current document. */
  def codeActions(path: Path, start: LineColumn, end: LineColumn, only: Seq[String] = Seq.empty)
      : Seq[(CodeActionResult, Seq[TextChange])] = host.read {
    file(path) match {
      case sourceFile: ScalaFile =>
        val sourceDocument = document(sourceFile)
        /** Includes requested action kinds and their descendants. */
        def accepts(kind: String): Boolean = only.isEmpty || only.exists(k => kind == k || kind.startsWith(k + "."))
        val contextual =
          if (accepts("quickfix") || accepts("refactor.rewrite"))
            host.codeActions(sourceFile, new TextRange(offset(sourceDocument, start), offset(sourceDocument, end)))
              .filter(choice => accepts(choice.kind))
          else Seq.empty
        val source = if (only.nonEmpty && accepts("source.organizeImports")) Seq(host.organizeImports(sourceFile)) else Seq.empty
        val choices = contextual ++ source
        choices.map { choice =>
          CodeActionResult(choice.title, choice.kind, choice.disabled) -> choice.edits.map(textChange(sourceDocument, _))
        }
      case _ => Seq.empty
    }
  }

  /** Validates the rename target and returns its name range, throwing Rename.Refused when unavailable. */
  def prepareRename(path: Path, at: LineColumn): (LineColumn, LineColumn) = {
    val sourceFile = file(path)
    val cursor = host.read(offset(document(sourceFile), at))
    host.renameTarget(sourceFile, cursor)
    host.read {
      val reference = Option(sourceFile.findReferenceAt(cursor)).map(r => r.getRangeInElement.shiftRight(r.getElement.getTextRange.getStartOffset))
      val name = reference.orElse(declarationNamedAt(sourceFile, cursor).map(_.getNameIdentifier.getTextRange))
        .getOrElse(throw new Rename.Refused(Seq("There is no name to rename here.")))
      position(document(sourceFile), name.getStartOffset) -> position(document(sourceFile), name.getEndOffset)
    }
  }

  /** Previews project-wide rename edits without retaining source changes, refusing invalid names or conflicts. */
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

  /** The names of the implementations and overriders of the symbol at `at` (project sources only). */
  def implementations(path: Path, at: LineColumn): Seq[SourceSpan] = host.read {
    val sourceFile = file(path)
    ProjectSearch.implementations(sourceFile, offset(document(sourceFile), at)).flatMap(nameSpan).distinct
  }

  /** The project's symbols whose names match `query`, best match first. */
  def workspaceSymbols(query: String): Seq[WorkspaceSymbolMatch] = host.read {
    ProjectSearch.symbols(host.project, query).flatMap { found =>
      nameSpan(found.element).map(WorkspaceSymbolMatch(found.element.name, workspaceSymbolKind(found.element), found.container, _))
    }
  }

  /** Finds this document's declaration and references under the caret, classifying writes with the plugin detector. */
  def highlights(path: Path, at: LineColumn): Seq[Occurrence] = host.read {
    val sourceFile = file(path)
    val sourceDocument = document(sourceFile)
    val atOffset = offset(sourceDocument, at)
    val target = Option(sourceFile.findReferenceAt(atOffset)) match {
      case Some(reference) => Option(reference.resolve())
      case None => declarationNamedAt(sourceFile, atOffset)
    }
    target.collect { case named: PsiNamedElement if named.getName != null => named }.toSeq.flatMap { named =>
      val access = new org.jetbrains.plugins.scala.highlighter.readWriteAccess.ScalaReadWriteAccessDetector
      /** Converts a PSI range within this document to workspace coordinates. */
      def span(range: TextRange): (LineColumn, LineColumn) =
        position(sourceDocument, range.getStartOffset) -> position(sourceDocument, range.getEndOffset)
      val declaration = named.getNavigationElement match {
        case owner: PsiNameIdentifierOwner if owner.getContainingFile == sourceFile && owner.getNameIdentifier != null =>
          Seq(Occurrence(span(owner.getNameIdentifier.getTextRange), write = access.isDeclarationWriteAccess(owner)))
        case _ => Seq.empty
      }
      // Only references whose text names the symbol can refer to it (the IDE's search also looks for the name's
      // occurrences first); resolving every reference of the file would be the whole highlighting pass.
      val references = SyntaxTraverser.psiTraverser(sourceFile).asScala.toSeq.flatMap(_.getReferences.toSeq).filter { reference =>
        val text = reference.getRangeInElement.substring(reference.getElement.getText)
        ScalaNamesUtil.equivalent(text, named.getName) && reference.isReferenceTo(named)
      }
      declaration ++ references.map { reference =>
        val write = access.getReferenceAccess(named, reference) == ReadWriteAccessDetector.Access.Write
        Occurrence(span(reference.getRangeInElement.shiftRight(reference.getElement.getTextRange.getStartOffset)), write)
      }
    }.distinct
  }

  /** The file's declarations from its language's structure view, without inherited members. */
  def outline(path: Path): Seq[OutlineSymbol] = host.read {
    file(path) match {
      case scalaFile: ScalaFile =>
        val sourceDocument = document(scalaFile)
        /** Converts a PSI range within this document to workspace coordinates. */
        def span(range: TextRange): (LineColumn, LineColumn) =
          position(sourceDocument, range.getStartOffset) -> position(sourceDocument, range.getEndOffset)
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
                  case named: ScNamedElement if text.startsWith(named.name) => named.name -> text.drop(named.name.length)
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
                Seq(OutlineSymbol(name, detail, workspaceSymbolKind(psi), span(whole.getTextRange), span(nameRange), children))
            }
          case _ => Seq.empty
        }
        Element(() => scalaFile).getChildren.toSeq.flatMap(symbols)
      case javaFile: PsiJavaFile =>
        val sourceDocument = document(javaFile)
        def span(range: TextRange): (LineColumn, LineColumn) =
          position(sourceDocument, range.getStartOffset) -> position(sourceDocument, range.getEndOffset)
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

  /** Computes plugin folding regions while leaving a trailing closing-bracket line visible. */
  def foldingRanges(path: Path): Seq[Fold] = host.read {
    file(path) match {
      case scalaFile: ScalaFile =>
        val sourceDocument = document(scalaFile)
        val text = sourceDocument.getCharsSequence
        new ScalaFoldingBuilder().buildFoldRegions(scalaFile, sourceDocument, /* quick = */ false).toSeq.flatMap { descriptor =>
          val range = descriptor.getRange
          val startLine = sourceDocument.getLineNumber(range.getStartOffset)
          val lastLine = sourceDocument.getLineNumber(range.getEndOffset)
          val closes = range.getLength > 0 && "})]".contains(text.charAt(range.getEndOffset - 1))
          val endLine = if (closes && lastLine > startLine) lastLine - 1 else lastLine
          val node = descriptor.getElement
          val kind = node.getElementType match {
            case t if org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes.COMMENTS_TOKEN_SET.contains(t) =>
              if (com.intellij.lang.folding.CustomFoldingBuilder.isCustomRegionElement(node.getPsi)) Fold.Region else Fold.Comment
            case t if t == org.jetbrains.plugins.scala.lang.scaladoc.parser.ScalaDocElementTypes.SCALA_DOC_COMMENT => Fold.Comment
            case t if t == org.jetbrains.plugins.scala.lang.parser.ScalaElementType.ImportStatement => Fold.Imports
            case _ => Fold.Code
          }
          Option.when(endLine > startLine)(Fold(startLine, endLine, kind))
        }.distinct
      case _ => Seq.empty
    }
  }

  /** The file's identifiers and soft keywords colored as the IDE colors them (see [[ScalaPsiHost.textAttributes]]). */
  def semanticTokens(path: Path): Seq[SemanticToken] = file(path) match {
    case scalaFile: ScalaFile =>
      host.read {
        val sourceDocument = document(scalaFile)
        host.textAttributes(scalaFile).flatMap { (range, key) =>
          if (range.isEmpty || sourceDocument.getLineNumber(range.getStartOffset) != sourceDocument.getLineNumber(range.getEndOffset)) None
          else semanticToken(key).map { (kind, modifiers) =>
            SemanticToken(position(sourceDocument, range.getStartOffset), range.getLength, kind, modifiers)
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

  /** Classifies Scala declarations for workspace symbols and document outlines. */
  private def workspaceSymbolKind(declaration: PsiElement): WorkspaceSymbolKind = declaration match {
    case _: ScEnumCase => WorkspaceSymbolKind.EnumMember
    case _: ScEnum => WorkspaceSymbolKind.Enum
    case _: ScTrait => WorkspaceSymbolKind.Interface
    case _: ScObject => WorkspaceSymbolKind.Module
    case _: ScTypeDefinition => WorkspaceSymbolKind.Class
    case function: ScFunction if function.isConstructor => WorkspaceSymbolKind.Constructor
    case function: ScFunction => if (function.containingClass == null) WorkspaceSymbolKind.Function else WorkspaceSymbolKind.Method
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
  private def semanticToken(key: TextAttributesKey): Option[(SemanticTokenKind, Set[SemanticTokenModifier])] = {
    import SemanticTokenKind.*
    import SemanticTokenModifier.*
    key match {
      case H.KEYWORD => Some(Keyword -> Set.empty)
      case H.PACKAGE => Some(Namespace -> Set.empty)
      case H.CLASS => Some(Class -> Set.empty)
      case H.ABSTRACT_CLASS => Some(Class -> Set(Abstract))
      case H.OBJECT => Some(Class -> Set(Readonly))
      case H.TRAIT => Some(Interface -> Set.empty)
      case H.ENUM => Some(Enum -> Set.empty)
      case H.ENUM_SINGLETON_CASE | H.ENUM_CLASS_CASE => Some(EnumMember -> Set.empty)
      case H.TYPEPARAM => Some(TypeParameter -> Set.empty)
      case H.TYPE_ALIAS => Some(Type -> Set.empty)
      case H.LOCAL_VALUES | H.LOCAL_LAZY | H.VALUES | H.LAZY | H.PATTERN | H.GENERATOR | H.GIVEN => Some(Variable -> Set(Readonly))
      case H.LOCAL_VARIABLES | H.VARIABLES => Some(Variable -> Set.empty)
      case H.CASE_CLASS_FIELD => Some(Property -> Set(Readonly))
      case H.CASE_CLASS_VAR_FIELD => Some(Property -> Set.empty)
      case H.PARAMETER | H.PARAMETER_OF_ANONIMOUS_FUNCTION | H.NAMED_ARGUMENT => Some(Parameter -> Set.empty)
      case H.METHOD_CALL | H.OBJECT_METHOD_CALL => Some(Method -> Set.empty)
      case H.LOCAL_METHOD_CALL => Some(Function -> Set.empty)
      case H.METHOD_DECLARATION => Some(Method -> Set(Declaration))
      case H.ANNOTATION => Some(Decorator -> Set.empty)
      case H.ANNOTATION_ATTRIBUTE => Some(Property -> Set.empty)
      case _ => None
    }
  }

}
