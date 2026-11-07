package scalaanalyzer.lsp

import scalaanalyzer.LineColumn
import scalaanalyzer.language.{SemanticToken, SemanticTokenKind, SemanticTokenModifier}

/** The encoding of semantic tokens: which token survives when several share a start, and the delta positions. */
class SemanticColoringTest extends munit.FunSuite {
  private def token(line: Int, column: Int, length: Int, kind: SemanticTokenKind, modifiers: SemanticTokenModifier*) =
    SemanticToken(LineColumn(line, column), length, kind, modifiers.toSet)

  private val variable = token(0, 4, 3, SemanticTokenKind.Variable, SemanticTokenModifier.Readonly)
  private val method = token(0, 4, 5, SemanticTokenKind.Method)

  // Legend indices: Class = 2, Variable = 7, Method = 11, Keyword = 12; Readonly is bit 0.
  private val cases = Seq(
    "the last of two tokens with one start wins, even when it is longer" -> (Seq(variable, method), Seq(0, 4, 5, 11, 0)),
    "the last of two tokens with one start wins, even when it is shorter" -> (Seq(method, variable), Seq(0, 4, 3, 7, 1)),
    "the last of two equal ranges wins" ->
      (Seq(variable, variable.copy(kind = SemanticTokenKind.Method)), Seq(0, 4, 3, 11, 1)),
    "an overlapping token is dropped and the next line starts from column zero" ->
      (
        Seq(
          token(0, 0, 5, SemanticTokenKind.Class),
          token(0, 2, 1, SemanticTokenKind.Method),
          token(1, 1, 2, SemanticTokenKind.Keyword),
        ),
        Seq(0, 0, 5, 2, 0, 1, 1, 2, 12, 0),
      ),
    "no tokens encode to nothing" -> (Seq.empty, Seq.empty),
  )

  for ((name, (tokens, encoded)) <- cases)
    test(name)(assertEquals(SemanticColoring.encode(tokens), encoded))
}
