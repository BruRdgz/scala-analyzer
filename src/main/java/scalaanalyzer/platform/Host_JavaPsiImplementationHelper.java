package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_JavaPsiImplementationHelper extends com.intellij.psi.impl.JavaPsiImplementationHelper {
  private final Dispatch d;
  public Host_JavaPsiImplementationHelper(Dispatch d) { super(); this.d = d; }
  @Override public com.intellij.psi.PsiElement getClsFileNavigationElement(com.intellij.psi.PsiJavaFile a0) { return (com.intellij.psi.PsiElement) d.call("JavaPsiImplementationHelper.getClsFileNavigationElement", new Object[]{a0}); }
  @Override public com.intellij.lang.ASTNode getDefaultImportAnchor(com.intellij.psi.PsiImportList a0, com.intellij.psi.PsiImportStatementBase a1) { return (com.intellij.lang.ASTNode) d.call("JavaPsiImplementationHelper.getDefaultImportAnchor", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiElement getDefaultMemberAnchor(com.intellij.psi.PsiClass a0, com.intellij.psi.PsiMember a1) { return (com.intellij.psi.PsiElement) d.call("JavaPsiImplementationHelper.getDefaultMemberAnchor", new Object[]{a0, a1}); }
  @Override public com.intellij.pom.java.LanguageLevel getEffectiveLanguageLevel(com.intellij.openapi.vfs.VirtualFile a0) { return (com.intellij.pom.java.LanguageLevel) d.call("JavaPsiImplementationHelper.getEffectiveLanguageLevel", new Object[]{a0}); }
  @Override public com.intellij.model.psi.PsiSymbolReference getFragmentNameSymbol(com.intellij.psi.javadoc.PsiDocFragmentName a0) { return (com.intellij.model.psi.PsiSymbolReference) d.call("JavaPsiImplementationHelper.getFragmentNameSymbol", new Object[]{a0}); }
  @Override public com.intellij.model.psi.PsiSymbolReference getInheritDocSymbol(com.intellij.psi.javadoc.PsiDocToken a0) { return (com.intellij.model.psi.PsiSymbolReference) d.call("JavaPsiImplementationHelper.getInheritDocSymbol", new Object[]{a0}); }
  @Override public com.intellij.psi.PsiClass getOriginalClass(com.intellij.psi.PsiClass a0) { return (com.intellij.psi.PsiClass) d.call("JavaPsiImplementationHelper.getOriginalClass", new Object[]{a0}); }
  @Override public com.intellij.psi.PsiJavaModule getOriginalModule(com.intellij.psi.PsiJavaModule a0) { return (com.intellij.psi.PsiJavaModule) d.call("JavaPsiImplementationHelper.getOriginalModule", new Object[]{a0}); }
  @Override public com.intellij.model.psi.PsiSymbolReference getSnippetRegionSymbol(com.intellij.psi.javadoc.PsiSnippetAttributeValue a0) { return (com.intellij.model.psi.PsiSymbolReference) d.call("JavaPsiImplementationHelper.getSnippetRegionSymbol", new Object[]{a0}); }
  @Override public void setupCatchBlock(java.lang.String a0, com.intellij.psi.PsiType a1, com.intellij.psi.PsiElement a2, com.intellij.psi.PsiCatchSection a3) { d.call("JavaPsiImplementationHelper.setupCatchBlock", new Object[]{a0, a1, a2, a3}); }
}
