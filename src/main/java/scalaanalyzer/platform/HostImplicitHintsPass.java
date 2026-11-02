package scalaanalyzer.platform;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import org.jetbrains.plugins.scala.annotator.hints.Text;
import org.jetbrains.plugins.scala.codeInsight.hints.ScalaHintsSettings;
import org.jetbrains.plugins.scala.codeInsight.hints.methodChains.AlignedHintTemplate;
import org.jetbrains.plugins.scala.codeInsight.implicits.ImplicitHintsPass;
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile;
import scala.Tuple2;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import java.util.List;

/**
 * Bridges Scala-private hint APIs that are public in plugin bytecode.
 *
 * Keep the bridge in Java: Scala callers cannot access the pass and template accessors directly.
 */
public final class HostImplicitHintsPass {
  /**
   * A method-chain hint extracted from the plugin's aligned template.
   *
   * @param offset end offset of the expression receiving the hint
   * @param parts plugin-rendered hint text
   */
  public record ChainHint(int offset, Seq<Text> parts) {}
  /**
   * Hints belonging to one method chain.
   *
   * @param hints hints collected for the chain
   * @param outer outer PSI expression used to position the group
   */
  public record MethodChain(List<ChainHint> hints, PsiElement outer) {}

  /**
   * Creates the plugin's implicit-hints pass for a request editor.
   *
   * @param editor request-local editor
   * @param file Scala source being queried
   * @param settings hint settings for this pass
   * @return a pass whose collection must run before hints are read
   */
  public static TextEditorHighlightingPass create(Editor editor, ScalaFile file, ScalaHintsSettings settings) {
    return new ImplicitHintsPass(editor, file, settings, false);
  }

  /**
   * Reads collected method-chain hints from the plugin pass.
   *
   * Run the pass's collection first. Unexpected plugin data shapes fail explicitly.
   *
   * @param pass pass returned by {@link #create}
   * @return collected hint groups in plugin order
   */
  public static List<MethodChain> methodChains(TextEditorHighlightingPass pass) {
    if (!(pass instanceof ImplicitHintsPass hintsPass)) {
      throw new IllegalArgumentException("not the plugin's hints pass: " + pass);
    }
    // The public accessor's generic signature is erased because its template type is Scala-private.
    Seq<?> groups = hintsPass.org$jetbrains$plugins$scala$codeInsight$hints$methodChains$ScalaMethodChainInlayHintsPass$$collectedHintTemplates();
    return CollectionConverters.asJava(groups).stream().map(HostImplicitHintsPass::methodChain).toList();
  }

  /** Validates and converts a plugin group with its outer PSI expression. */
  private static MethodChain methodChain(Object value) {
    if (!(value instanceof Tuple2<?, ?> group) || !(group._1() instanceof Seq<?> templates) ||
        !(group._2() instanceof PsiElement outer)) {
      throw new IllegalStateException("not the plugin's method-chain group: " + value);
    }
    List<ChainHint> hints = CollectionConverters.asJava(templates).stream().map(HostImplicitHintsPass::chainHint).toList();
    return new MethodChain(hints, outer);
  }

  /** Validates and converts an aligned plugin hint template. */
  private static ChainHint chainHint(Object value) {
    if (!(value instanceof AlignedHintTemplate hint)) {
      throw new IllegalStateException("not the plugin's method-chain template: " + value);
    }
    return new ChainHint(hint.endOffset(), hint.textParts());
  }
}
