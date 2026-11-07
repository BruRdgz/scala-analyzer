package scalaanalyzer.lsp

import org.eclipse.lsp4j.{SemanticTokenModifiers, SemanticTokenTypes, SemanticTokensLegend}

import scalaanalyzer.LineColumn
import scalaanalyzer.language.{SemanticToken, SemanticTokenKind, SemanticTokenModifier}

import scala.jdk.CollectionConverters.*

/** Maps protocol-neutral semantic tokens to the LSP legend and relative integer encoding. */
object SemanticColoring {
  /** Token type names in legend order: a type's position here is its index in the encoded tokens. */
  private val types = Seq(
    SemanticTokenTypes.Namespace, SemanticTokenTypes.Type, SemanticTokenTypes.Class, SemanticTokenTypes.Enum,
    SemanticTokenTypes.Interface, SemanticTokenTypes.TypeParameter, SemanticTokenTypes.Parameter,
    SemanticTokenTypes.Variable, SemanticTokenTypes.Property, SemanticTokenTypes.EnumMember,
    SemanticTokenTypes.Function, SemanticTokenTypes.Method, SemanticTokenTypes.Keyword, SemanticTokenTypes.Decorator,
  )

  /** Token modifier names in legend order: a modifier's position here is its bit in the encoded modifier mask. */
  private val modifiers = Seq(
    SemanticTokenModifiers.Readonly, SemanticTokenModifiers.Abstract, SemanticTokenModifiers.Declaration,
  )

  /**
   * The token-type and modifier ordering used by [[encode]].
   *
   * Advertise this legend unchanged for the lifetime of the server.
   */
  val legend: SemanticTokensLegend = new SemanticTokensLegend(types.asJava, modifiers.asJava)

  /** The LSP token type name that each language token kind is reported as. */
  private val kindType: Map[SemanticTokenKind, String] = Map(
    SemanticTokenKind.Namespace -> SemanticTokenTypes.Namespace,
    SemanticTokenKind.Type -> SemanticTokenTypes.Type,
    SemanticTokenKind.Class -> SemanticTokenTypes.Class,
    SemanticTokenKind.Enum -> SemanticTokenTypes.Enum,
    SemanticTokenKind.Interface -> SemanticTokenTypes.Interface,
    SemanticTokenKind.TypeParameter -> SemanticTokenTypes.TypeParameter,
    SemanticTokenKind.Parameter -> SemanticTokenTypes.Parameter,
    SemanticTokenKind.Variable -> SemanticTokenTypes.Variable,
    SemanticTokenKind.Property -> SemanticTokenTypes.Property,
    SemanticTokenKind.EnumMember -> SemanticTokenTypes.EnumMember,
    SemanticTokenKind.Function -> SemanticTokenTypes.Function,
    SemanticTokenKind.Method -> SemanticTokenTypes.Method,
    SemanticTokenKind.Keyword -> SemanticTokenTypes.Keyword,
    SemanticTokenKind.Decorator -> SemanticTokenTypes.Decorator,
  )

  /** The LSP modifier name that each language token modifier is reported as. */
  private val modifierName: Map[SemanticTokenModifier, String] = Map(
    SemanticTokenModifier.Readonly -> SemanticTokenModifiers.Readonly,
    SemanticTokenModifier.Abstract -> SemanticTokenModifiers.Abstract,
    SemanticTokenModifier.Declaration -> SemanticTokenModifiers.Declaration,
  )

  /**
   * Encodes semantic tokens as LSP delta tuples.
   *
   * Tokens are sorted by position. Tokens that start at the same position keep the last input token, whatever their
   * lengths; later overlapping ranges are dropped. Input positions and lengths use UTF-16 code units and each token must fit within one line. An empty input
   * encodes to an empty sequence.
   *
   * @param tokens tokens expressed in the language model's categories
   * @return flattened delta-line, delta-column, length, type-index and modifier-bit tuples: five integers per kept
   *         token, in position order
   */
  def encode(tokens: Seq[SemanticToken]): Seq[Int] = {
    // One token per start makes the sort key unique, so the order of `values` cannot decide which token survives.
    val lastPerStart = tokens.groupMapReduce(_.start)(identity)((_, later) => later).values
    val ordered = lastPerStart.toSeq.sortBy(token => (token.start.line, token.start.column))
    val kept = ordered.foldLeft(Vector.empty[SemanticToken]) { (acc, next) =>
      acc.lastOption match {
        case Some(last) if last.start.line == next.start.line && next.start.column < last.start.column + last.length => acc
        case _ => acc :+ next
      }
    }

    kept.zipWithIndex.flatMap { (token, i) =>
      val previous = if (i == 0) LineColumn(0, 0) else kept(i - 1).start
      val deltaLine = token.start.line - previous.line
      val deltaColumn = if (deltaLine == 0) token.start.column - previous.column else token.start.column
      val modifierBits = token.modifiers.map(m => 1 << modifiers.indexOf(modifierName(m))).sum
      Seq(deltaLine, deltaColumn, token.length, types.indexOf(kindType(token.kind)), modifierBits)
    }
  }
}
