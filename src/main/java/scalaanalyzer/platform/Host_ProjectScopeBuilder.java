package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_ProjectScopeBuilder extends com.intellij.psi.search.ProjectScopeBuilder {
  private final Dispatch d;
  public Host_ProjectScopeBuilder(Dispatch d) { this.d = d; }
  @Override public com.intellij.psi.search.GlobalSearchScope buildEverythingScope() { return (com.intellij.psi.search.GlobalSearchScope) d.call("ProjectScopeBuilder.buildEverythingScope", new Object[]{}); }
  @Override public com.intellij.psi.search.GlobalSearchScope buildLibrariesScope() { return (com.intellij.psi.search.GlobalSearchScope) d.call("ProjectScopeBuilder.buildLibrariesScope", new Object[]{}); }
  @Override public com.intellij.psi.search.GlobalSearchScope buildLibraryClassesScope() { return (com.intellij.psi.search.GlobalSearchScope) d.call("ProjectScopeBuilder.buildLibraryClassesScope", new Object[]{}); }
  @Override public com.intellij.psi.search.GlobalSearchScope buildAllScope() { return (com.intellij.psi.search.GlobalSearchScope) d.call("ProjectScopeBuilder.buildAllScope", new Object[]{}); }
  @Override public com.intellij.psi.search.GlobalSearchScope buildProjectScope() { return (com.intellij.psi.search.GlobalSearchScope) d.call("ProjectScopeBuilder.buildProjectScope", new Object[]{}); }
  @Override public com.intellij.psi.search.GlobalSearchScope buildContentScope() { return (com.intellij.psi.search.GlobalSearchScope) d.call("ProjectScopeBuilder.buildContentScope", new Object[]{}); }
}
