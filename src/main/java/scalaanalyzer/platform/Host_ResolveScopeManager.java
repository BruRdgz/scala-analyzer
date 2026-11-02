package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_ResolveScopeManager extends com.intellij.psi.impl.ResolveScopeManager {
  private final Dispatch d;
  public Host_ResolveScopeManager(Dispatch d) { this.d = d; }
  @Override public com.intellij.psi.search.GlobalSearchScope getUseScope(com.intellij.psi.PsiElement a0) { return (com.intellij.psi.search.GlobalSearchScope) d.call("ResolveScopeManager.getUseScope", new Object[]{a0}); }
  @Override public com.intellij.psi.search.GlobalSearchScope getResolveScope(com.intellij.psi.PsiElement a0) { return (com.intellij.psi.search.GlobalSearchScope) d.call("ResolveScopeManager.getResolveScope", new Object[]{a0}); }
  @Override public com.intellij.psi.search.GlobalSearchScope getDefaultResolveScope(com.intellij.openapi.vfs.VirtualFile a0) { return (com.intellij.psi.search.GlobalSearchScope) d.call("ResolveScopeManager.getDefaultResolveScope", new Object[]{a0}); }
}
