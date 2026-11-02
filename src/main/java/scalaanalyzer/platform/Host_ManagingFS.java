package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_ManagingFS extends com.intellij.openapi.vfs.newvfs.ManagingFS {
  private final Dispatch d;
  public Host_ManagingFS(Dispatch d) { super(); this.d = d; }
  @Override protected java.util.function.Function accessDiskWithCheckCanceled(java.util.function.Function a0) { return (java.util.function.Function) d.call("ManagingFS.accessDiskWithCheckCanceled", new Object[]{a0}); }
  @Override public boolean areChildrenLoaded(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("ManagingFS.areChildrenLoaded", new Object[]{a0}); }
  @Override public byte[] contentsToByteArray(com.intellij.openapi.vfs.VirtualFile a0) throws java.io.IOException { return (byte[]) d.call("ManagingFS.contentsToByteArray", new Object[]{a0}); }
  @Override public com.intellij.openapi.vfs.VirtualFile copyFile(java.lang.Object a0, com.intellij.openapi.vfs.VirtualFile a1, com.intellij.openapi.vfs.VirtualFile a2, java.lang.String a3) throws java.io.IOException { return (com.intellij.openapi.vfs.VirtualFile) d.call("ManagingFS.copyFile", new Object[]{a0, a1, a2, a3}); }
  @Override public com.intellij.openapi.vfs.VirtualFile createChildDirectory(java.lang.Object a0, com.intellij.openapi.vfs.VirtualFile a1, java.lang.String a2) throws java.io.IOException { return (com.intellij.openapi.vfs.VirtualFile) d.call("ManagingFS.createChildDirectory", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.openapi.vfs.VirtualFile createChildFile(java.lang.Object a0, com.intellij.openapi.vfs.VirtualFile a1, java.lang.String a2) throws java.io.IOException { return (com.intellij.openapi.vfs.VirtualFile) d.call("ManagingFS.createChildFile", new Object[]{a0, a1, a2}); }
  @Override public void deleteFile(java.lang.Object a0, com.intellij.openapi.vfs.VirtualFile a1) throws java.io.IOException { d.call("ManagingFS.deleteFile", new Object[]{a0, a1}); }
  @Override public boolean exists(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("ManagingFS.exists", new Object[]{a0}); }
  @Override public com.intellij.openapi.vfs.VirtualFile findFileById(int a0) { return (com.intellij.openapi.vfs.VirtualFile) d.call("ManagingFS.findFileById", new Object[]{a0}); }
  @Override public com.intellij.openapi.vfs.newvfs.NewVirtualFile findRoot(java.lang.String a0, com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem a1) { return (com.intellij.openapi.vfs.newvfs.NewVirtualFile) d.call("ManagingFS.findRoot", new Object[]{a0, a1}); }
  @Override public void flushPendingUpdatesOrNotify() { d.call("ManagingFS.flushPendingUpdatesOrNotify", new Object[]{}); }
  @Override public void flushPendingUpdates() throws java.io.IOException { d.call("ManagingFS.flushPendingUpdates", new Object[]{}); }
  @Override public void flushPendingUpdates(com.intellij.openapi.vfs.VirtualFile a0) throws java.io.IOException { d.call("ManagingFS.flushPendingUpdates", new Object[]{a0}); }
  @Override public long getCreationTimestamp() { return (Long) d.call("ManagingFS.getCreationTimestamp", new Object[]{}); }
  @Override public int getFilesystemModificationCount() { return (Integer) d.call("ManagingFS.getFilesystemModificationCount", new Object[]{}); }
  @Override public java.io.InputStream getInputStream(com.intellij.openapi.vfs.VirtualFile a0) throws java.io.IOException { return (java.io.InputStream) d.call("ManagingFS.getInputStream", new Object[]{a0}); }
  @Override public long getLength(com.intellij.openapi.vfs.VirtualFile a0) { return (Long) d.call("ManagingFS.getLength", new Object[]{a0}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getLocalRoots() { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ManagingFS.getLocalRoots", new Object[]{}); }
  @Override public int getModificationCount(com.intellij.openapi.vfs.VirtualFile a0) { return (Integer) d.call("ManagingFS.getModificationCount", new Object[]{a0}); }
  @Override public java.io.OutputStream getOutputStream(com.intellij.openapi.vfs.VirtualFile a0, java.lang.Object a1, long a2, long a3) throws java.io.IOException { return (java.io.OutputStream) d.call("ManagingFS.getOutputStream", new Object[]{a0, a1, a2, a3}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getRoots() { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ManagingFS.getRoots", new Object[]{}); }
  @Override public com.intellij.openapi.vfs.VirtualFile[] getRoots(com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem a0) { return (com.intellij.openapi.vfs.VirtualFile[]) d.call("ManagingFS.getRoots", new Object[]{a0}); }
  @Override public int getStructureModificationCount() { return (Integer) d.call("ManagingFS.getStructureModificationCount", new Object[]{}); }
  @Override public long getTimeStamp(com.intellij.openapi.vfs.VirtualFile a0) { return (Long) d.call("ManagingFS.getTimeStamp", new Object[]{a0}); }
  @Override public boolean isDirectory(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("ManagingFS.isDirectory", new Object[]{a0}); }
  @Override public boolean isSymLink(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("ManagingFS.isSymLink", new Object[]{a0}); }
  @Override public boolean isWritable(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("ManagingFS.isWritable", new Object[]{a0}); }
  @Override public java.lang.String[] list(com.intellij.openapi.vfs.VirtualFile a0) { return (java.lang.String[]) d.call("ManagingFS.list", new Object[]{a0}); }
  @Override public void moveFile(java.lang.Object a0, com.intellij.openapi.vfs.VirtualFile a1, com.intellij.openapi.vfs.VirtualFile a2) throws java.io.IOException { d.call("ManagingFS.moveFile", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.openapi.vfs.newvfs.AttributeInputStream readAttribute(com.intellij.openapi.vfs.VirtualFile a0, com.intellij.openapi.vfs.newvfs.FileAttribute a1) { return (com.intellij.openapi.vfs.newvfs.AttributeInputStream) d.call("ManagingFS.readAttribute", new Object[]{a0, a1}); }
  @Override public void renameFile(java.lang.Object a0, com.intellij.openapi.vfs.VirtualFile a1, java.lang.String a2) throws java.io.IOException { d.call("ManagingFS.renameFile", new Object[]{a0, a1, a2}); }
  @Override public java.lang.String resolveSymLink(com.intellij.openapi.vfs.VirtualFile a0) { return (java.lang.String) d.call("ManagingFS.resolveSymLink", new Object[]{a0}); }
  @Override public void setTimeStamp(com.intellij.openapi.vfs.VirtualFile a0, long a1) throws java.io.IOException { d.call("ManagingFS.setTimeStamp", new Object[]{a0, a1}); }
  @Override public void setWritable(com.intellij.openapi.vfs.VirtualFile a0, boolean a1) throws java.io.IOException { d.call("ManagingFS.setWritable", new Object[]{a0, a1}); }
  @Override public boolean wereChildrenAccessed(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("ManagingFS.wereChildrenAccessed", new Object[]{a0}); }
  @Override public com.intellij.openapi.vfs.newvfs.AttributeOutputStream writeAttribute(com.intellij.openapi.vfs.VirtualFile a0, com.intellij.openapi.vfs.newvfs.FileAttribute a1) { return (com.intellij.openapi.vfs.newvfs.AttributeOutputStream) d.call("ManagingFS.writeAttribute", new Object[]{a0, a1}); }
}
