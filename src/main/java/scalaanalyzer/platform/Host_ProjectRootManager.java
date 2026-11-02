package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_ProjectRootManager extends com.intellij.openapi.roots.ProjectRootManager {
  private final Dispatch d;
  public Host_ProjectRootManager(Dispatch d) { this.d = d; }
  @Override public com.intellij.openapi.roots.ProjectFileIndex getFileIndex() { return (com.intellij.openapi.roots.ProjectFileIndex) d.call("ProjectRootManager.getFileIndex", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator orderEntries(java.util.Collection a0) { return (com.intellij.openapi.roots.OrderEnumerator) d.call("ProjectRootManager.orderEntries", new Object[]{a0}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator orderEntries() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("ProjectRootManager.orderEntries", new Object[]{}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getContentRootsFromAllModules() { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ProjectRootManager.getContentRootsFromAllModules", new Object[]{}); }
  @Override public java.util.List getContentRootUrls() { return (java.util.List) d.call("ProjectRootManager.getContentRootUrls", new Object[]{}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getContentRoots() { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ProjectRootManager.getContentRoots", new Object[]{}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getContentSourceRoots() { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ProjectRootManager.getContentSourceRoots", new Object[]{}); }
  @Override public java.util.List getModuleSourceRoots(java.util.Set a0) { return (java.util.List) d.call("ProjectRootManager.getModuleSourceRoots", new Object[]{a0}); }
  @Override public com.intellij.openapi.projectRoots.Sdk getProjectSdk() { return (com.intellij.openapi.projectRoots.Sdk) d.call("ProjectRootManager.getProjectSdk", new Object[]{}); }
  @Override public java.lang.String getProjectSdkName() { return (java.lang.String) d.call("ProjectRootManager.getProjectSdkName", new Object[]{}); }
  @Override public java.lang.String getProjectSdkTypeName() { return (java.lang.String) d.call("ProjectRootManager.getProjectSdkTypeName", new Object[]{}); }
  @Override public void setProjectSdk(com.intellij.openapi.projectRoots.Sdk a0) { d.call("ProjectRootManager.setProjectSdk", new Object[]{a0}); }
  @Override public void setProjectSdkName(java.lang.String a0, java.lang.String a1) { d.call("ProjectRootManager.setProjectSdkName", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.roots.ModuleRootManager getModuleRootManager(com.intellij.openapi.module.Module a0) { return (com.intellij.openapi.roots.ModuleRootManager) d.call("ProjectRootManager.getModuleRootManager", new Object[]{a0}); }
}
