package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_StubIndex extends com.intellij.psi.stubs.StubIndex {
  private final Dispatch d;
  public Host_StubIndex(Dispatch d) { this.d = d; }
  @Override public java.util.Iterator getContainingFilesIterator(com.intellij.psi.stubs.StubIndexKey a0, java.lang.Object a1, com.intellij.openapi.project.Project a2, com.intellij.psi.search.GlobalSearchScope a3) { return (java.util.Iterator) d.call("StubIndex.getContainingFilesIterator", new Object[]{a0, a1, a2, a3}); }
  @Override public java.util.Collection getAllKeys(com.intellij.psi.stubs.StubIndexKey a0, com.intellij.openapi.project.Project a1) { return (java.util.Collection) d.call("StubIndex.getAllKeys", new Object[]{a0, a1}); }
  @Override public int getMaxContainingFileCount(com.intellij.psi.stubs.StubIndexKey a0, java.lang.Object a1, com.intellij.openapi.project.Project a2, com.intellij.psi.search.GlobalSearchScope a3) { return (Integer) d.call("StubIndex.getMaxContainingFileCount", new Object[]{a0, a1, a2, a3}); }
  @Override public void forceRebuild(java.lang.Throwable a0) { d.call("StubIndex.forceRebuild", new Object[]{a0}); }
  @Override public com.intellij.openapi.util.ModificationTracker getPerFileElementTypeModificationTracker(com.intellij.psi.tree.IFileElementType a0) { return (com.intellij.openapi.util.ModificationTracker) d.call("StubIndex.getPerFileElementTypeModificationTracker", new Object[]{a0}); }
  @Override public com.intellij.openapi.util.ModificationTracker getStubIndexModificationTracker(com.intellij.openapi.project.Project a0) { return (com.intellij.openapi.util.ModificationTracker) d.call("StubIndex.getStubIndexModificationTracker", new Object[]{a0}); }

  // Concrete overloads in StubIndex delegate to each other; the real implementation overrides this one.
  @Override public boolean processElements(com.intellij.psi.stubs.StubIndexKey a0, java.lang.Object a1, com.intellij.openapi.project.Project a2,
      com.intellij.psi.search.GlobalSearchScope a3, com.intellij.util.indexing.IdFilter a4, java.lang.Class a5, com.intellij.util.Processor a6) {
    return (Boolean) d.call("StubIndex.processElements", new Object[]{a0, a1, a2, a3, a4, a5, a6});
  }
  @Override public boolean processAllKeys(com.intellij.psi.stubs.StubIndexKey a0, com.intellij.util.Processor a1,
      com.intellij.psi.search.GlobalSearchScope a2, com.intellij.util.indexing.IdFilter a3) {
    return (Boolean) d.call("StubIndex.processAllKeys", new Object[]{a0, a1, a2, a3});
  }
}
