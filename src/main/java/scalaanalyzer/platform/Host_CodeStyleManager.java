package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_CodeStyleManager extends com.intellij.psi.codeStyle.CodeStyleManager {
  private final Dispatch d;
  public Host_CodeStyleManager(Dispatch d) { super(); this.d = d; }
  @Override public int adjustLineIndent(com.intellij.openapi.editor.Document a0, int a1) { return (Integer) d.call("CodeStyleManager.adjustLineIndent", new Object[]{a0, a1}); }
  @Override public void adjustLineIndent(com.intellij.psi.PsiFile a0, com.intellij.openapi.util.TextRange a1) throws com.intellij.util.IncorrectOperationException { d.call("CodeStyleManager.adjustLineIndent", new Object[]{a0, a1}); }
  @Override public int adjustLineIndent(com.intellij.psi.PsiFile a0, int a1) throws com.intellij.util.IncorrectOperationException { return (Integer) d.call("CodeStyleManager.adjustLineIndent", new Object[]{a0, a1}); }
  @Override public java.lang.String fillIndent(com.intellij.psi.codeStyle.Indent a0, com.intellij.openapi.fileTypes.FileType a1) { return (java.lang.String) d.call("CodeStyleManager.fillIndent", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.codeStyle.Indent getIndent(java.lang.String a0, com.intellij.openapi.fileTypes.FileType a1) { return (com.intellij.psi.codeStyle.Indent) d.call("CodeStyleManager.getIndent", new Object[]{a0, a1}); }
  @Override public java.lang.String getLineIndent(com.intellij.openapi.editor.Document a0, int a1) { return (java.lang.String) d.call("CodeStyleManager.getLineIndent", new Object[]{a0, a1}); }
  @Override public java.lang.String getLineIndent(com.intellij.psi.PsiFile a0, int a1) { return (java.lang.String) d.call("CodeStyleManager.getLineIndent", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.project.Project getProject() { return (com.intellij.openapi.project.Project) d.call("CodeStyleManager.getProject", new Object[]{}); }
  @Override public boolean isLineToBeIndented(com.intellij.psi.PsiFile a0, int a1) { return (Boolean) d.call("CodeStyleManager.isLineToBeIndented", new Object[]{a0, a1}); }
  @Override public boolean isSequentialProcessingAllowed() { return (Boolean) d.call("CodeStyleManager.isSequentialProcessingAllowed", new Object[]{}); }
  @Override public java.lang.Object performActionWithFormatterDisabled(com.intellij.openapi.util.Computable a0) { return (java.lang.Object) d.call("CodeStyleManager.performActionWithFormatterDisabled", new Object[]{a0}); }
  @Override public void performActionWithFormatterDisabled(com.intellij.util.ThrowableRunnable a0) throws java.lang.Throwable { d.call("CodeStyleManager.performActionWithFormatterDisabled", new Object[]{a0}); }
  @Override public void performActionWithFormatterDisabled(java.lang.Runnable a0) { d.call("CodeStyleManager.performActionWithFormatterDisabled", new Object[]{a0}); }
  @Override public void reformatNewlyAddedElement(com.intellij.lang.ASTNode a0, com.intellij.lang.ASTNode a1) throws com.intellij.util.IncorrectOperationException { d.call("CodeStyleManager.reformatNewlyAddedElement", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiElement reformatRange(com.intellij.psi.PsiElement a0, int a1, int a2, boolean a3) throws com.intellij.util.IncorrectOperationException { return (com.intellij.psi.PsiElement) d.call("CodeStyleManager.reformatRange", new Object[]{a0, a1, a2, a3}); }
  @Override public com.intellij.psi.PsiElement reformatRange(com.intellij.psi.PsiElement a0, int a1, int a2) throws com.intellij.util.IncorrectOperationException { return (com.intellij.psi.PsiElement) d.call("CodeStyleManager.reformatRange", new Object[]{a0, a1, a2}); }
  @Override public void reformatText(com.intellij.psi.PsiFile a0, int a1, int a2) throws com.intellij.util.IncorrectOperationException { d.call("CodeStyleManager.reformatText", new Object[]{a0, a1, a2}); }
  @Override public void reformatText(com.intellij.psi.PsiFile a0, java.util.Collection a1) throws com.intellij.util.IncorrectOperationException { d.call("CodeStyleManager.reformatText", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiElement reformat(com.intellij.psi.PsiElement a0, boolean a1) throws com.intellij.util.IncorrectOperationException { return (com.intellij.psi.PsiElement) d.call("CodeStyleManager.reformat", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiElement reformat(com.intellij.psi.PsiElement a0) throws com.intellij.util.IncorrectOperationException { return (com.intellij.psi.PsiElement) d.call("CodeStyleManager.reformat", new Object[]{a0}); }
  @Override public com.intellij.psi.codeStyle.Indent zeroIndent() { return (com.intellij.psi.codeStyle.Indent) d.call("CodeStyleManager.zeroIndent", new Object[]{}); }
}
