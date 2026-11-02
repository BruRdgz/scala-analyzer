package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_ModuleRootManager extends com.intellij.openapi.roots.ModuleRootManager {
  private final Dispatch d;
  public Host_ModuleRootManager(Dispatch d) { super(); this.d = d; }
  @Override public com.intellij.openapi.roots.ContentEntry[] getContentEntries() { return (com.intellij.openapi.roots.ContentEntry[]) d.call("ModuleRootManager.getContentEntries", new Object[]{}); }
  @Override public java.lang.String[] getContentRootUrls() { return (java.lang.String[]) d.call("ModuleRootManager.getContentRootUrls", new Object[]{}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getContentRoots() { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ModuleRootManager.getContentRoots", new Object[]{}); }
  @Override public com.intellij.openapi.module.Module[] getDependencies() { return (com.intellij.openapi.module.Module[]) d.call("ModuleRootManager.getDependencies", new Object[]{}); }
  @Override public com.intellij.openapi.module.Module[] getDependencies(boolean a0) { return (com.intellij.openapi.module.Module[]) d.call("ModuleRootManager.getDependencies", new Object[]{a0}); }
  @Override public java.lang.String[] getDependencyModuleNames() { return (java.lang.String[]) d.call("ModuleRootManager.getDependencyModuleNames", new Object[]{}); }
  @Override public java.lang.String[] getExcludeRootUrls() { return (java.lang.String[]) d.call("ModuleRootManager.getExcludeRootUrls", new Object[]{}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getExcludeRoots() { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ModuleRootManager.getExcludeRoots", new Object[]{}); }
  @Override public com.intellij.openapi.roots.ProjectModelExternalSource getExternalSource() { return (com.intellij.openapi.roots.ProjectModelExternalSource) d.call("ModuleRootManager.getExternalSource", new Object[]{}); }
  @Override public com.intellij.openapi.roots.ModuleFileIndex getFileIndex() { return (com.intellij.openapi.roots.ModuleFileIndex) d.call("ModuleRootManager.getFileIndex", new Object[]{}); }
  @Override public com.intellij.openapi.roots.ModifiableRootModel getModifiableModel() { return (com.intellij.openapi.roots.ModifiableRootModel) d.call("ModuleRootManager.getModifiableModel", new Object[]{}); }
  @Override public com.intellij.openapi.module.Module[] getModuleDependencies() { return (com.intellij.openapi.module.Module[]) d.call("ModuleRootManager.getModuleDependencies", new Object[]{}); }
  @Override public com.intellij.openapi.module.Module[] getModuleDependencies(boolean a0) { return (com.intellij.openapi.module.Module[]) d.call("ModuleRootManager.getModuleDependencies", new Object[]{a0}); }
  @Override public java.lang.Object getModuleExtension(java.lang.Class a0) { return (java.lang.Object) d.call("ModuleRootManager.getModuleExtension", new Object[]{a0}); }
  @Override public com.intellij.openapi.module.Module getModule() { return (com.intellij.openapi.module.Module) d.call("ModuleRootManager.getModule", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEntry[] getOrderEntries() { return (com.intellij.openapi.roots.OrderEntry[]) d.call("ModuleRootManager.getOrderEntries", new Object[]{}); }
  @Override public com.intellij.openapi.projectRoots.Sdk getSdk() { return (com.intellij.openapi.projectRoots.Sdk) d.call("ModuleRootManager.getSdk", new Object[]{}); }
  @Override public java.lang.String[] getSourceRootUrls() { return (java.lang.String[]) d.call("ModuleRootManager.getSourceRootUrls", new Object[]{}); }
  @Override public java.lang.String[] getSourceRootUrls(boolean a0) { return (java.lang.String[]) d.call("ModuleRootManager.getSourceRootUrls", new Object[]{a0}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getSourceRoots() { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ModuleRootManager.getSourceRoots", new Object[]{}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getSourceRoots(boolean a0) { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ModuleRootManager.getSourceRoots", new Object[]{a0}); }
  @Override public java.util.List getSourceRoots(java.util.Set a0) { return (java.util.List) d.call("ModuleRootManager.getSourceRoots", new Object[]{a0}); }
  @Override public java.util.List getSourceRoots(org.jetbrains.jps.model.module.JpsModuleSourceRootType a0) { return (java.util.List) d.call("ModuleRootManager.getSourceRoots", new Object[]{a0}); }
  @Override public boolean isDependsOn(com.intellij.openapi.module.Module a0) { return (Boolean) d.call("ModuleRootManager.isDependsOn", new Object[]{a0}); }
  @Override public boolean isSdkInherited() { return (Boolean) d.call("ModuleRootManager.isSdkInherited", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator orderEntries() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("ModuleRootManager.orderEntries", new Object[]{}); }
  @Override public java.lang.Object processOrder(com.intellij.openapi.roots.RootPolicy a0, java.lang.Object a1) { return (java.lang.Object) d.call("ModuleRootManager.processOrder", new Object[]{a0, a1}); }
}
