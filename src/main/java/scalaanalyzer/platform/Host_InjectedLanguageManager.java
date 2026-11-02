package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_InjectedLanguageManager extends com.intellij.lang.injection.InjectedLanguageManager {
  private final Dispatch d;
  public Host_InjectedLanguageManager(Dispatch d) { super(); this.d = d; }
  @Override public void dropFileCaches(com.intellij.psi.PsiFile a0) { d.call("InjectedLanguageManager.dropFileCaches", new Object[]{a0}); }
  @Override public void enumerateEx(com.intellij.psi.PsiElement a0, com.intellij.psi.PsiFile a1, boolean a2, com.intellij.psi.PsiLanguageInjectionHost.InjectedPsiVisitor a3) { d.call("InjectedLanguageManager.enumerateEx", new Object[]{a0, a1, a2, a3}); }
  @Override public void enumerate(com.intellij.psi.PsiElement a0, com.intellij.psi.PsiLanguageInjectionHost.InjectedPsiVisitor a1) { d.call("InjectedLanguageManager.enumerate", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiElement findInjectedElementAt(com.intellij.psi.PsiFile a0, int a1) { return (com.intellij.psi.PsiElement) d.call("InjectedLanguageManager.findInjectedElementAt", new Object[]{a0, a1}); }
  @Override public com.intellij.injected.editor.DocumentWindow freezeWindow(com.intellij.injected.editor.DocumentWindow a0) { return (com.intellij.injected.editor.DocumentWindow) d.call("InjectedLanguageManager.freezeWindow", new Object[]{a0}); }
  @Override public java.util.List getCachedInjectedDocumentsInRange(com.intellij.psi.PsiFile a0, com.intellij.openapi.util.TextRange a1) { return (java.util.List) d.call("InjectedLanguageManager.getCachedInjectedDocumentsInRange", new Object[]{a0, a1}); }
  @Override public java.util.List getInjectedPsiFiles(com.intellij.psi.PsiElement a0) { return (java.util.List) d.call("InjectedLanguageManager.getInjectedPsiFiles", new Object[]{a0}); }
  @Override public com.intellij.psi.PsiLanguageInjectionHost getInjectionHost(com.intellij.psi.FileViewProvider a0) { return (com.intellij.psi.PsiLanguageInjectionHost) d.call("InjectedLanguageManager.getInjectionHost", new Object[]{a0}); }
  @Override public com.intellij.psi.PsiLanguageInjectionHost getInjectionHost(com.intellij.psi.PsiElement a0) { return (com.intellij.psi.PsiLanguageInjectionHost) d.call("InjectedLanguageManager.getInjectionHost", new Object[]{a0}); }
  @Override public java.util.List getNonEditableFragments(com.intellij.injected.editor.DocumentWindow a0) { return (java.util.List) d.call("InjectedLanguageManager.getNonEditableFragments", new Object[]{a0}); }
  @Override public com.intellij.psi.PsiFile getTopLevelFile(com.intellij.psi.PsiElement a0) { return (com.intellij.psi.PsiFile) d.call("InjectedLanguageManager.getTopLevelFile", new Object[]{a0}); }
  @Override public java.lang.String getUnescapedText(com.intellij.psi.PsiElement a0) { return (java.lang.String) d.call("InjectedLanguageManager.getUnescapedText", new Object[]{a0}); }
  @Override public com.intellij.openapi.util.TextRange injectedToHost(com.intellij.psi.PsiElement a0, com.intellij.openapi.util.TextRange a1) { return (com.intellij.openapi.util.TextRange) d.call("InjectedLanguageManager.injectedToHost", new Object[]{a0, a1}); }
  @Override public int injectedToHost(com.intellij.psi.PsiElement a0, int a1, boolean a2) { return (Integer) d.call("InjectedLanguageManager.injectedToHost", new Object[]{a0, a1, a2}); }
  @Override public int injectedToHost(com.intellij.psi.PsiElement a0, int a1) { return (Integer) d.call("InjectedLanguageManager.injectedToHost", new Object[]{a0, a1}); }
  @Override public java.util.List intersectWithAllEditableFragments(com.intellij.psi.PsiFile a0, com.intellij.openapi.util.TextRange a1) { return (java.util.List) d.call("InjectedLanguageManager.intersectWithAllEditableFragments", new Object[]{a0, a1}); }
  @Override public boolean isFrankensteinInjection(com.intellij.psi.PsiElement a0) { return (Boolean) d.call("InjectedLanguageManager.isFrankensteinInjection", new Object[]{a0}); }
  @Override public boolean isInjectedViewProvider(com.intellij.psi.FileViewProvider a0) { return (Boolean) d.call("InjectedLanguageManager.isInjectedViewProvider", new Object[]{a0}); }
  @Override public boolean mightHaveInjectedFragmentAtOffset(com.intellij.openapi.editor.Document a0, int a1) { return (Boolean) d.call("InjectedLanguageManager.mightHaveInjectedFragmentAtOffset", new Object[]{a0, a1}); }
  @Override public void registerMultiHostInjector(com.intellij.lang.injection.MultiHostInjector a0, com.intellij.openapi.Disposable a1) { d.call("InjectedLanguageManager.registerMultiHostInjector", new Object[]{a0, a1}); }
  @Override public boolean shouldInspectionsBeLenient(com.intellij.psi.PsiElement a0) { return (Boolean) d.call("InjectedLanguageManager.shouldInspectionsBeLenient", new Object[]{a0}); }
}
