package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_JavaCodeStyleManager extends com.intellij.psi.codeStyle.JavaCodeStyleManager {
  private final Dispatch d;
  public Host_JavaCodeStyleManager(Dispatch d) { super(); this.d = d; }
  @Override public boolean addImport(com.intellij.psi.PsiJavaFile a0, com.intellij.psi.PsiClass a1) { return (Boolean) d.call("JavaCodeStyleManager.addImport", new Object[]{a0, a1}); }
  @Override public int findEntryIndex(com.intellij.psi.PsiImportStatementBase a0) { return (Integer) d.call("JavaCodeStyleManager.findEntryIndex", new Object[]{a0}); }
  @Override public java.util.Collection findRedundantImports(com.intellij.psi.PsiJavaFile a0) { return (java.util.Collection) d.call("JavaCodeStyleManager.findRedundantImports", new Object[]{a0}); }
  @Override public java.lang.String getPrefixByVariableKind(com.intellij.psi.codeStyle.VariableKind a0) { return (java.lang.String) d.call("JavaCodeStyleManager.getPrefixByVariableKind", new Object[]{a0}); }
  @Override public java.lang.String getSuffixByVariableKind(com.intellij.psi.codeStyle.VariableKind a0) { return (java.lang.String) d.call("JavaCodeStyleManager.getSuffixByVariableKind", new Object[]{a0}); }
  @Override public boolean isStaticAutoImportName(java.lang.String a0) { return (Boolean) d.call("JavaCodeStyleManager.isStaticAutoImportName", new Object[]{a0}); }
  @Override public void optimizeImports(com.intellij.psi.PsiFile a0) throws com.intellij.util.IncorrectOperationException { d.call("JavaCodeStyleManager.optimizeImports", new Object[]{a0}); }
  @Override public com.intellij.psi.PsiImportList prepareOptimizeImportsResult(com.intellij.psi.PsiJavaFile a0) { return (com.intellij.psi.PsiImportList) d.call("JavaCodeStyleManager.prepareOptimizeImportsResult", new Object[]{a0}); }
  @Override public java.lang.String propertyNameToVariableName(java.lang.String a0, com.intellij.psi.codeStyle.VariableKind a1) { return (java.lang.String) d.call("JavaCodeStyleManager.propertyNameToVariableName", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiElement qualifyClassReferences(com.intellij.psi.PsiElement a0) { return (com.intellij.psi.PsiElement) d.call("JavaCodeStyleManager.qualifyClassReferences", new Object[]{a0}); }
  @Override public void removeRedundantImports(com.intellij.psi.PsiJavaFile a0) throws com.intellij.util.IncorrectOperationException { d.call("JavaCodeStyleManager.removeRedundantImports", new Object[]{a0}); }
  @Override public void shortenClassReferences(com.intellij.psi.PsiElement a0, int a1, int a2) throws com.intellij.util.IncorrectOperationException { d.call("JavaCodeStyleManager.shortenClassReferences", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.psi.PsiElement shortenClassReferences(com.intellij.psi.PsiElement a0, int a1) throws com.intellij.util.IncorrectOperationException { return (com.intellij.psi.PsiElement) d.call("JavaCodeStyleManager.shortenClassReferences", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiElement shortenClassReferences(com.intellij.psi.PsiElement a0) throws com.intellij.util.IncorrectOperationException { return (com.intellij.psi.PsiElement) d.call("JavaCodeStyleManager.shortenClassReferences", new Object[]{a0}); }
  @Override public com.intellij.psi.codeStyle.SuggestedNameInfo suggestNames(java.util.Collection a0, com.intellij.psi.codeStyle.VariableKind a1, com.intellij.psi.PsiType a2) { return (com.intellij.psi.codeStyle.SuggestedNameInfo) d.call("JavaCodeStyleManager.suggestNames", new Object[]{a0, a1, a2}); }
  @Override public java.util.Collection suggestSemanticNames(com.intellij.psi.PsiExpression a0, com.intellij.psi.codeStyle.VariableKind a1) { return (java.util.Collection) d.call("JavaCodeStyleManager.suggestSemanticNames", new Object[]{a0, a1}); }
  @Override public java.util.Collection suggestSemanticNames(com.intellij.psi.PsiExpression a0) { return (java.util.Collection) d.call("JavaCodeStyleManager.suggestSemanticNames", new Object[]{a0}); }
  @Override public com.intellij.psi.codeStyle.SuggestedNameInfo suggestUniqueVariableName(com.intellij.psi.codeStyle.SuggestedNameInfo a0, com.intellij.psi.PsiElement a1, boolean a2, boolean a3) { return (com.intellij.psi.codeStyle.SuggestedNameInfo) d.call("JavaCodeStyleManager.suggestUniqueVariableName", new Object[]{a0, a1, a2, a3}); }
  @Override public java.lang.String suggestUniqueVariableName(java.lang.String a0, com.intellij.psi.PsiElement a1, boolean a2, java.util.Set a3) { return (java.lang.String) d.call("JavaCodeStyleManager.suggestUniqueVariableName", new Object[]{a0, a1, a2, a3}); }
  @Override public java.lang.String suggestUniqueVariableName(java.lang.String a0, com.intellij.psi.PsiElement a1, boolean a2) { return (java.lang.String) d.call("JavaCodeStyleManager.suggestUniqueVariableName", new Object[]{a0, a1, a2}); }
  @Override public java.lang.String suggestUniqueVariableName(java.lang.String a0, com.intellij.psi.PsiElement a1, java.util.function.Predicate a2) { return (java.lang.String) d.call("JavaCodeStyleManager.suggestUniqueVariableName", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.psi.codeStyle.SuggestedNameInfo suggestVariableName(com.intellij.psi.codeStyle.VariableKind a0, java.lang.String a1, com.intellij.psi.PsiExpression a2, com.intellij.psi.PsiType a3, boolean a4) { return (com.intellij.psi.codeStyle.SuggestedNameInfo) d.call("JavaCodeStyleManager.suggestVariableName", new Object[]{a0, a1, a2, a3, a4}); }
  @Override public java.lang.String variableNameToPropertyName(java.lang.String a0, com.intellij.psi.codeStyle.VariableKind a1) { return (java.lang.String) d.call("JavaCodeStyleManager.variableNameToPropertyName", new Object[]{a0, a1}); }
}
