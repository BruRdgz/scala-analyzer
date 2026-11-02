package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_FileBasedIndex extends com.intellij.util.indexing.FileBasedIndex {
  private final Dispatch d;
  public Host_FileBasedIndex(Dispatch d) { super(); this.d = d; }
  @Override public void ensureUpToDate(com.intellij.util.indexing.ID a0, com.intellij.openapi.project.Project a1, com.intellij.psi.search.GlobalSearchScope a2) { d.call("FileBasedIndex.ensureUpToDate", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.openapi.vfs.VirtualFile findFileById(com.intellij.openapi.project.Project a0, int a1) { return (com.intellij.openapi.vfs.VirtualFile) d.call("FileBasedIndex.findFileById", new Object[]{a0, a1}); }
  @Override public java.util.Collection getAllKeys(com.intellij.util.indexing.ID a0, com.intellij.openapi.project.Project a1) { return (java.util.Collection) d.call("FileBasedIndex.getAllKeys", new Object[]{a0, a1}); }
  @Override public java.util.Iterator getContainingFilesIterator(com.intellij.util.indexing.ID a0, java.lang.Object a1, com.intellij.psi.search.GlobalSearchScope a2) { return (java.util.Iterator) d.call("FileBasedIndex.getContainingFilesIterator", new Object[]{a0, a1, a2}); }
  @Override public java.util.Collection getContainingFiles(com.intellij.util.indexing.ID a0, java.lang.Object a1, com.intellij.psi.search.GlobalSearchScope a2) { return (java.util.Collection) d.call("FileBasedIndex.getContainingFiles", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.openapi.vfs.VirtualFile getFileBeingCurrentlyIndexed() { return (com.intellij.openapi.vfs.VirtualFile) d.call("FileBasedIndex.getFileBeingCurrentlyIndexed", new Object[]{}); }
  @Override public java.util.Map getFileData(com.intellij.util.indexing.ID a0, com.intellij.openapi.vfs.VirtualFile a1, com.intellij.openapi.project.Project a2) { return (java.util.Map) d.call("FileBasedIndex.getFileData", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.util.indexing.FileBasedIndex.IndexWritingFile getFileWritingCurrentlyIndexes() { return (com.intellij.util.indexing.FileBasedIndex.IndexWritingFile) d.call("FileBasedIndex.getFileWritingCurrentlyIndexes", new Object[]{}); }
  @Override public boolean getFilesWithKey(com.intellij.util.indexing.ID a0, java.util.Set a1, com.intellij.util.Processor a2, com.intellij.psi.search.GlobalSearchScope a3) { return (Boolean) d.call("FileBasedIndex.getFilesWithKey", new Object[]{a0, a1, a2, a3}); }
  @Override public long getIndexModificationStamp(com.intellij.util.indexing.ID a0, com.intellij.openapi.project.Project a1) { return (Long) d.call("FileBasedIndex.getIndexModificationStamp", new Object[]{a0, a1}); }
  @Override public java.lang.Object getSingleEntryIndexData(com.intellij.util.indexing.ID a0, com.intellij.openapi.vfs.VirtualFile a1, com.intellij.openapi.project.Project a2) { return (java.lang.Object) d.call("FileBasedIndex.getSingleEntryIndexData", new Object[]{a0, a1, a2}); }
  @Override public java.util.List getValues(com.intellij.util.indexing.ID a0, java.lang.Object a1, com.intellij.psi.search.GlobalSearchScope a2) { return (java.util.List) d.call("FileBasedIndex.getValues", new Object[]{a0, a1, a2}); }
  @Override public void iterateIndexableFiles(com.intellij.openapi.roots.ContentIterator a0, com.intellij.openapi.project.Project a1, com.intellij.openapi.progress.ProgressIndicator a2) { d.call("FileBasedIndex.iterateIndexableFiles", new Object[]{a0, a1, a2}); }
  @Override public boolean iterateNonIndexableFiles(com.intellij.openapi.project.Project a0, com.intellij.openapi.vfs.VirtualFileFilter a1, com.intellij.openapi.roots.ContentIterator a2) { return (Boolean) d.call("FileBasedIndex.iterateNonIndexableFiles", new Object[]{a0, a1, a2}); }
  @Override public boolean processAllKeys(com.intellij.util.indexing.ID a0, com.intellij.util.Processor a1, com.intellij.openapi.project.Project a2) { return (Boolean) d.call("FileBasedIndex.processAllKeys", new Object[]{a0, a1, a2}); }
  @Override public boolean processFilesContainingAllKeys(com.intellij.util.indexing.ID a0, java.util.Collection a1, com.intellij.psi.search.GlobalSearchScope a2, com.intellij.openapi.util.Condition a3, com.intellij.util.Processor a4) { return (Boolean) d.call("FileBasedIndex.processFilesContainingAllKeys", new Object[]{a0, a1, a2, a3, a4}); }
  @Override public boolean processFilesContainingAllKeys(java.util.Collection a0, com.intellij.psi.search.GlobalSearchScope a1, com.intellij.util.Processor a2) { return (Boolean) d.call("FileBasedIndex.processFilesContainingAllKeys", new Object[]{a0, a1, a2}); }
  @Override public boolean processFilesContainingAnyKey(com.intellij.util.indexing.ID a0, java.util.Collection a1, com.intellij.psi.search.GlobalSearchScope a2, com.intellij.util.indexing.IdFilter a3, com.intellij.openapi.util.Condition a4, com.intellij.util.Processor a5) { return (Boolean) d.call("FileBasedIndex.processFilesContainingAnyKey", new Object[]{a0, a1, a2, a3, a4, a5}); }
  @Override public boolean processValues(com.intellij.util.indexing.ID a0, java.lang.Object a1, com.intellij.openapi.vfs.VirtualFile a2, com.intellij.util.indexing.FileBasedIndex.ValueProcessor a3, com.intellij.psi.search.GlobalSearchScope a4) { return (Boolean) d.call("FileBasedIndex.processValues", new Object[]{a0, a1, a2, a3, a4}); }
  @Override public com.intellij.util.indexing.IdFilter projectIndexableFiles(com.intellij.openapi.project.Project a0) { return (com.intellij.util.indexing.IdFilter) d.call("FileBasedIndex.projectIndexableFiles", new Object[]{a0}); }
  @Override public void requestRebuild(com.intellij.util.indexing.ID a0, java.lang.Throwable a1) { d.call("FileBasedIndex.requestRebuild", new Object[]{a0, a1}); }
  @Override public void requestReindex(com.intellij.openapi.vfs.VirtualFile a0) { d.call("FileBasedIndex.requestReindex", new Object[]{a0}); }
  @Override public void scheduleRebuild(com.intellij.util.indexing.ID a0, java.lang.Throwable a1) { d.call("FileBasedIndex.scheduleRebuild", new Object[]{a0, a1}); }
  // Throws in FileBasedIndex; the host is never in dumb mode, so the command runs as it is. Hand-written: keep when regenerating.
  @Override public <T, E extends Throwable> T ignoreDumbMode(com.intellij.util.indexing.DumbModeAccessType a0, com.intellij.openapi.util.ThrowableComputable<T, E> a1) throws E { return a1.compute(); }
}
