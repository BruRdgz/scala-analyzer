package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_HighlightingLevelManager extends com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager {
  private final Dispatch d;
  public Host_HighlightingLevelManager(Dispatch d) { super(); this.d = d; }
  @Override public boolean runEssentialHighlightingOnly(com.intellij.psi.PsiElement a0) { return (Boolean) d.call("HighlightingLevelManager.runEssentialHighlightingOnly", new Object[]{a0}); }
  @Override public boolean shouldHighlight(com.intellij.psi.PsiElement a0) { return (Boolean) d.call("HighlightingLevelManager.shouldHighlight", new Object[]{a0}); }
  @Override public boolean shouldInspect(com.intellij.psi.PsiElement a0) { return (Boolean) d.call("HighlightingLevelManager.shouldInspect", new Object[]{a0}); }
}
