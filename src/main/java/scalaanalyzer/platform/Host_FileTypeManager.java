package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_FileTypeManager extends com.intellij.openapi.fileTypes.FileTypeManager {
  private final Dispatch d;
  public Host_FileTypeManager(Dispatch d) { super(); this.d = d; }
  @Override public void associate(com.intellij.openapi.fileTypes.FileType a0, com.intellij.openapi.fileTypes.FileNameMatcher a1) { d.call("FileTypeManager.associate", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.fileTypes.FileType findFileTypeByName(java.lang.String a0) { return (com.intellij.openapi.fileTypes.FileType) d.call("FileTypeManager.findFileTypeByName", new Object[]{a0}); }
  @Override public java.util.List getAssociations(com.intellij.openapi.fileTypes.FileType a0) { return (java.util.List) d.call("FileTypeManager.getAssociations", new Object[]{a0}); }
  @Override public com.intellij.openapi.fileTypes.FileType getFileTypeByExtension(java.lang.String a0) { return (com.intellij.openapi.fileTypes.FileType) d.call("FileTypeManager.getFileTypeByExtension", new Object[]{a0}); }
  @Override public com.intellij.openapi.fileTypes.FileType getFileTypeByFileName(java.lang.String a0) { return (com.intellij.openapi.fileTypes.FileType) d.call("FileTypeManager.getFileTypeByFileName", new Object[]{a0}); }
  @Override public com.intellij.openapi.fileTypes.FileType getFileTypeByFile(com.intellij.openapi.vfs.VirtualFile a0) { return (com.intellij.openapi.fileTypes.FileType) d.call("FileTypeManager.getFileTypeByFile", new Object[]{a0}); }
  @Override public java.lang.String getIgnoredFilesList() { return (java.lang.String) d.call("FileTypeManager.getIgnoredFilesList", new Object[]{}); }
  @Override public com.intellij.openapi.fileTypes.FileType getKnownFileTypeOrAssociate(com.intellij.openapi.vfs.VirtualFile a0, com.intellij.openapi.project.Project a1) { return (com.intellij.openapi.fileTypes.FileType) d.call("FileTypeManager.getKnownFileTypeOrAssociate", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.fileTypes.FileType[] getRegisteredFileTypes() { return (com.intellij.openapi.fileTypes.FileType[]) d.call("FileTypeManager.getRegisteredFileTypes", new Object[]{}); }
  @Override public com.intellij.openapi.fileTypes.FileType getStdFileType(java.lang.String a0) { return (com.intellij.openapi.fileTypes.FileType) d.call("FileTypeManager.getStdFileType", new Object[]{a0}); }
  @Override public boolean isFileIgnored(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("FileTypeManager.isFileIgnored", new Object[]{a0}); }
  @Override public boolean isFileIgnored(java.lang.String a0) { return (Boolean) d.call("FileTypeManager.isFileIgnored", new Object[]{a0}); }
  @Override public void removeAssociation(com.intellij.openapi.fileTypes.FileType a0, com.intellij.openapi.fileTypes.FileNameMatcher a1) { d.call("FileTypeManager.removeAssociation", new Object[]{a0, a1}); }
  @Override public void setIgnoredFilesList(java.lang.String a0) { d.call("FileTypeManager.setIgnoredFilesList", new Object[]{a0}); }
}
