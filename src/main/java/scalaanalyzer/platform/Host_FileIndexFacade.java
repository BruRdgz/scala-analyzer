package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_FileIndexFacade extends com.intellij.openapi.roots.FileIndexFacade {
  private final Dispatch d;
  public Host_FileIndexFacade(com.intellij.openapi.project.Project s0, Dispatch d) { super(s0); this.d = d; }
  @Override public com.intellij.openapi.module.Module getModuleForFile(com.intellij.openapi.vfs.VirtualFile a0) { return (com.intellij.openapi.module.Module) d.call("FileIndexFacade.getModuleForFile", new Object[]{a0}); }
  @Override public com.intellij.openapi.util.ModificationTracker getRootModificationTracker() { return (com.intellij.openapi.util.ModificationTracker) d.call("FileIndexFacade.getRootModificationTracker", new Object[]{}); }
  @Override public java.util.Collection getUnloadedModuleDescriptions() { return (java.util.Collection) d.call("FileIndexFacade.getUnloadedModuleDescriptions", new Object[]{}); }
  @Override public boolean isExcludedFile(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isExcludedFile", new Object[]{a0}); }
  @Override public boolean isInContent(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isInContent", new Object[]{a0}); }
  @Override public boolean isInLibraryClasses(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isInLibraryClasses", new Object[]{a0}); }
  @Override public boolean isInLibrarySource(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isInLibrarySource", new Object[]{a0}); }
  @Override public boolean isInLibrary(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isInLibrary", new Object[]{a0}); }
  @Override public boolean isInSourceContent(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isInSourceContent", new Object[]{a0}); }
  @Override public boolean isInSource(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isInSource", new Object[]{a0}); }
  @Override public boolean isIndexable(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isIndexable", new Object[]{a0}); }
  @Override public boolean isUnderIgnored(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileIndexFacade.isUnderIgnored", new Object[]{a0}); }
  @Override public boolean isValidAncestor(com.intellij.openapi.vfs.VirtualFile a0, com.intellij.openapi.vfs.VirtualFile a1) { return (Boolean) d.call("FileIndexFacade.isValidAncestor", new Object[]{a0, a1}); }
}
