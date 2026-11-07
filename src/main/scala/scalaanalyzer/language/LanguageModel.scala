package scalaanalyzer.language

import scalaanalyzer.{LineColumn, LineRange}

import java.nio.file.Path

/** Diagnostic severity without a dependency on either the IDE or LSP severity enums. */
enum ProblemSeverity { case Error, Warning, Information }

/** Symbol categories returned by workspace navigation operations. */
enum WorkspaceSymbolKind {
  case EnumMember, Enum, Interface, Module, Class, Constructor, Function, Method, Field, Property, Namespace, Variable,
    Constant, Object
}

/**
 * A source problem reported by the Scala plugin or compiler.
 *
 * @param severity protocol-neutral diagnostic level
 * @param start zero-based start line and UTF-16 column
 * @param end exclusive end line and UTF-16 column
 * @param message diagnostic text
 * @param source producer label, such as `scala-plugin` or `scalac`
 * @param inspectionId stable inspection code, when supplied by the producer
 * @param deprecated whether the highlighted symbol is deprecated
 * @param unnecessary whether the producer marks the highlighted code as unnecessary
 */
final case class FileProblem(
  severity: ProblemSeverity,
  start: LineColumn,
  end: LineColumn,
  message: String,
  source: String,
  inspectionId: Option[String] = None,
  deprecated: Boolean = false,
  unnecessary: Boolean = false,
)

/**
 * A symbol occurrence within one document.
 *
 * @param range start and exclusive end positions in zero-based lines and UTF-16 columns
 * @param write whether the plugin classifies the occurrence as a write
 */
final case class Occurrence(range: LineRange, write: Boolean)

/**
 * A range in a source file.
 *
 * @param path source path, including a materialized library source when navigating outside the project
 * @param range start and exclusive end positions in zero-based lines and UTF-16 columns
 */
final case class SourceSpan(path: Path, range: LineRange)

/**
 * A project symbol found by name.
 *
 * @param name symbol name
 * @param kind declaration category
 * @param container containing package or class when available
 * @param location source range of the symbol's name
 */
final case class WorkspaceSymbolMatch(
  name: String,
  kind: WorkspaceSymbolKind,
  container: Option[String],
  location: SourceSpan,
)

/**
 * A replacement within a document snapshot.
 *
 * @param start zero-based start line and UTF-16 column
 * @param end exclusive end line and UTF-16 column
 * @param text replacement text, empty for a deletion
 */
final case class TextChange(start: LineColumn, end: LineColumn, text: String)

/**
 * Text changes for one project file.
 *
 * @param path file to edit
 * @param changes replacements expressed against the same document snapshot
 */
final case class FileChanges(path: Path, changes: Seq[TextChange])

/**
 * Describes the availability and kind of a previewed editor action.
 *
 * @param title user-facing action name
 * @param kind hierarchical action category, such as `quickfix` or `refactor.rewrite`
 * @param disabled reason the action cannot be applied, or none when available
 */
final case class CodeActionResult(title: String, kind: String, disabled: Option[String])

/**
 * A completion candidate detached from plugin lookup objects.
 *
 * Retain its lookup, prefix and identity when requesting an insertion follow-up against the same snapshot.
 *
 * @param label presentation text
 * @param tail additional context shown after the label
 * @param typeText rendered result type when available
 * @param kind completion category
 * @param deprecated whether the candidate is deprecated
 * @param lookup text inserted for the candidate
 * @param prefixText document prefix used to obtain the candidate
 * @param hasHandler whether plugin insertion logic can produce additional edits
 * @param identity opaque key used to find the same candidate during insertion replay
 */
final case class CompletionCandidate(
  label: String,
  tail: Option[String],
  typeText: Option[String],
  kind: Completion.Kind,
  deprecated: Boolean,
  lookup: String,
  prefixText: String,
  hasHandler: Boolean,
  identity: String,
)

/**
 * A completion candidate with its replacement range.
 *
 * @param suggestion candidate presentation and insertion identity
 * @param start zero-based start line and UTF-16 column of the prefix
 * @param end exclusive end of the prefix
 */
final case class CompletionChoice(suggestion: CompletionCandidate, start: LineColumn, end: LineColumn)

/**
 * Additional effects of a completion candidate's insert handler.
 *
 * @param changes replacements against the document after the lookup text has been inserted
 * @param caret final zero-based caret line and UTF-16 column
 */
final case class CompletionFollowUp(changes: Seq[TextChange], caret: LineColumn)

/**
 * An inline hint at a document position.
 *
 * @param at zero-based line and UTF-16 column at which to show the hint
 * @param text rendered hint label
 */
final case class InlineHint(at: LineColumn, text: String)

/**
 * A runnable test selection with its BSP target and source anchor.
 *
 * @param range start and exclusive end positions in zero-based lines and UTF-16 columns
 * @param target BSP target identity of the source's module
 * @param framework runner framework id: scalatest, specs2, utest or munit
 * @param suite fully qualified suite class name
 * @param testName plugin runner selection, absent when running the whole suite
 */
final case class TestLens(
  range: LineRange,
  target: String,
  framework: String,
  suite: String,
  testName: Option[String],
)

/**
 * Ranked completion choices for one request.
 *
 * @param items candidates in plugin relevance order
 * @param incomplete whether additional suggestions were omitted from the bounded result
 */
final case class CompletionChoices(items: Seq[CompletionChoice], incomplete: Boolean)

/**
 * A range in rendered signature text.
 *
 * @param start inclusive UTF-16 offset
 * @param end exclusive UTF-16 offset
 */
final case class OffsetRange(start: Int, end: Int)

/**
 * One rendered call signature.
 *
 * @param text signature label
 * @param current range of the active parameter in the label, when known
 * @param applicable whether the plugin considers this overload applicable
 * @param deprecated whether the overload is deprecated
 */
final case class SignatureInfo(text: String, current: Option[OffsetRange], applicable: Boolean, deprecated: Boolean)

/**
 * Rendered overloads for the current call.
 *
 * @param items signatures in plugin order
 * @param active zero-based active overload index, when known
 */
final case class SignatureChoices(items: Seq[SignatureInfo], active: Option[Int])

/**
 * A declaration in a file outline.
 *
 * All ranges use zero-based lines and UTF-16 columns, with exclusive ends.
 *
 * @param name declaration name
 * @param detail signature text following the name
 * @param kind declaration category
 * @param range complete declaration range
 * @param nameRange range selecting the declaration's name
 * @param children nested declarations in structure-view order
 */
final case class OutlineSymbol(
  name: String,
  detail: String,
  kind: WorkspaceSymbolKind,
  range: LineRange,
  nameRange: LineRange,
  children: Seq[OutlineSymbol],
)

/**
 * A line-based folding region.
 *
 * @param startLine zero-based first line of the region
 * @param endLine zero-based last line, inclusive
 * @param kind folding category
 */
final case class Fold(startLine: Int, endLine: Int, kind: Fold.Kind)

/** Folding categories shared by language operations and protocol mapping. */
object Fold {
  /** The reason a document region can be folded. */
  enum Kind { case Code, Comment, Imports, Region }
  export Kind.*
}

/** A semantic category understood by the workspace layer, independent of the LSP token-number encoding. */
enum SemanticTokenKind {
  case Namespace, Type, Class, Enum, Interface, TypeParameter, Parameter, Variable, Property, EnumMember,
    Function, Method, Keyword, Decorator
}

/** Semantic properties that refine a token's category. */
enum SemanticTokenModifier { case Readonly, Abstract, Declaration }

/**
 * A semantic token contained within one document line.
 *
 * @param start zero-based start line and UTF-16 column
 * @param length token length in UTF-16 code units
 * @param kind semantic category
 * @param modifiers additional declaration or mutability properties
 */
final case class SemanticToken(
  start: LineColumn,
  length: Int,
  kind: SemanticTokenKind,
  modifiers: Set[SemanticTokenModifier],
)

/**
 * A hierarchy identity without retained PSI. Project items use their source URI and name offset; library items
 * use the qualified owner class and declaration offset. `uri` remains the originating project document for reads.
 * `name` rejects stale offsets that now select a different declaration.
 *
 * @param uri project source URI, or originating project document for a library declaration
 * @param offset declaration name offset in its navigation PSI file, in UTF-16 code units
 * @param name declaration name used to reject stale offsets
 * @param libraryClass qualified class name for library classes and method owners, absent for project sources
 */
final case class HierarchyTarget(uri: String, offset: Int, name: String, libraryClass: Option[String])

/**
 * A declaration and its full and name ranges, shared by call and type hierarchies.
 *
 * @param name declaration name
 * @param kind declaration category
 * @param location declaration range in a project or materialized library file
 * @param selectionRange declaration name range in the same file
 * @param target identity used to resolve subsequent requests
 */
final case class HierarchyItem(
  name: String,
  kind: WorkspaceSymbolKind,
  location: SourceSpan,
  selectionRange: LineRange,
  target: HierarchyTarget,
)

/**
 * A caller or callee with call-site ranges in the caller's document.
 *
 * @param item caller for incoming edges, callee for outgoing edges
 * @param ranges call-site ranges; empty for an override edge without a call site
 */
final case class HierarchyCall(item: HierarchyItem, ranges: Seq[LineRange])
