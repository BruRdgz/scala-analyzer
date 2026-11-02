package scalaanalyzer.platform;

import com.intellij.codeInsight.multiverse.CodeInsightContext;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileFilter;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.impl.PsiTreeChangeEventImpl;
import com.intellij.psi.impl.PsiTreeChangePreprocessor;
import com.intellij.psi.impl.file.impl.FileManager;
import com.intellij.psi.impl.file.impl.FileManagerEx;
import com.intellij.psi.util.PsiModificationTracker;

/**
 * Delegates PSI file lookups and tree-change notifications to host-owned services.
 *
 * Answered calls are recorded when adapter counting is enabled. Unsupported operations always record
 * and throw, making unexpected platform integration visible.
 */
public final class HostPsiManager extends PsiManagerEx {
  private final Project project;
  private final PsiModificationTracker tracker;
  private final FileManagerEx fileManager;
  private final PsiTreeChangePreprocessor preprocessor;
  private final java.util.List<PsiTreeChangeListener> listeners;

  /**
   * Creates a manager whose tree events reach the preprocessor before listeners.
   *
   * @param project owning host project
   * @param tracker project modification tracker
   * @param fileManager host-owned file and view-provider lookup
   * @param preprocessor sees every tree change first (the IDE's PsiModificationTrackerImpl counts them there)
   * @param listeners    then see it, as PsiTreeChangeListeners registered with the IDE's PsiManagerImpl
   */
  public HostPsiManager(Project project, PsiModificationTracker tracker, FileManagerEx fileManager,
                        PsiTreeChangePreprocessor preprocessor, java.util.List<PsiTreeChangeListener> listeners) {
    this.project = project;
    this.tracker = tracker;
    this.fileManager = fileManager;
    this.preprocessor = preprocessor;
    this.listeners = listeners;
  }

  /** As PsiManagerImpl: the preprocessor, then each listener. */
  private void fire(String name, PsiTreeChangeEventImpl e, java.util.function.Consumer<PsiTreeChangeListener> toListener) {
    hit(name);
    preprocessor.treeChanged(e);
    listeners.forEach(toListener);
  }

  /** Records and rejects a method outside the supported manager surface. */
  private static <T> T no(String name) { return CallLog.unsupported("PsiManager." + name); }

  /** Records an answered method only when adapter-surface counting is enabled. */
  private static void hit(String name) {
    if (CallLog.countsAnswered()) CallLog.hit("PsiManager." + name);
  }

  // Supported: identity, lifecycle and tree-change notifications.
  @Override public Project getProject() {
    hit("getProject");
    return project;
  }
  @Override public boolean isDisposed() {
    hit("isDisposed");
    return false;
  }
  @Override public boolean areElementsEquivalent(PsiElement a, PsiElement b) {
    hit("areElementsEquivalent");
    return a == b;
  }
  @Override public PsiModificationTracker getModificationTracker() {
    hit("getModificationTracker");
    return tracker;
  }
  @Override public boolean isBatchFilesProcessingMode() {
    hit("isBatchFilesProcessingMode");
    return false;
  }
  @Override public void beforeChildAddition(PsiTreeChangeEventImpl e) { fire("beforeChildAddition", e, l -> l.beforeChildAddition(e)); }
  @Override public void beforeChildRemoval(PsiTreeChangeEventImpl e) { fire("beforeChildRemoval", e, l -> l.beforeChildRemoval(e)); }
  @Override public void beforeChildReplacement(PsiTreeChangeEventImpl e) { fire("beforeChildReplacement", e, l -> l.beforeChildReplacement(e)); }
  @Override public void beforeChildrenChange(PsiTreeChangeEventImpl e) { fire("beforeChildrenChange", e, l -> l.beforeChildrenChange(e)); }
  @Override public void beforeChildMovement(PsiTreeChangeEventImpl e) { fire("beforeChildMovement", e, l -> l.beforeChildMovement(e)); }
  @Override public void beforePropertyChange(PsiTreeChangeEventImpl e) { fire("beforePropertyChange", e, l -> l.beforePropertyChange(e)); }
  @Override public void childAdded(PsiTreeChangeEventImpl e) { fire("childAdded", e, l -> l.childAdded(e)); }
  @Override public void childRemoved(PsiTreeChangeEventImpl e) { fire("childRemoved", e, l -> l.childRemoved(e)); }
  @Override public void childReplaced(PsiTreeChangeEventImpl e) { fire("childReplaced", e, l -> l.childReplaced(e)); }
  @Override public void childMoved(PsiTreeChangeEventImpl e) { fire("childMoved", e, l -> l.childMoved(e)); }
  @Override public void childrenChanged(PsiTreeChangeEventImpl e) { fire("childrenChanged", e, l -> l.childrenChanged(e)); }
  @Override public void propertyChanged(PsiTreeChangeEventImpl e) { fire("propertyChanged", e, l -> l.propertyChanged(e)); }
  @Override public void beforeChange(boolean physical) { hit("beforeChange"); }
  @Override public void afterChange(boolean physical) { hit("afterChange"); }

  // File lookups go to the host's FileManager, as PsiManagerImpl delegates to its FileManager.
  @Override public PsiFile findFile(VirtualFile f) {
    hit("findFile");
    return fileManager.findFile(f);
  }
  @Override public FileViewProvider findViewProvider(VirtualFile f) {
    hit("findViewProvider");
    return fileManager.findViewProvider(f);
  }
  @Override public PsiDirectory findDirectory(VirtualFile f) {
    hit("findDirectory");
    return fileManager.findDirectory(f);
  }
  @Override public FileViewProvider findCachedViewProvider(VirtualFile f) {
    hit("findCachedViewProvider");
    return fileManager.findCachedViewProvider(f);
  }
  // The host has one code-insight context per file (no multiverse): every context sees the same file.
  @Override public PsiFile findFile(VirtualFile f, CodeInsightContext c) {
    hit("findFile(context)");
    return fileManager.findFile(f);
  }
  @Override public FileViewProvider findViewProvider(VirtualFile f, CodeInsightContext c) {
    hit("findViewProvider(context)");
    return fileManager.findViewProvider(f);
  }

  // The project's content is its source files; library files (even attached sources) and copies are not in it.
  @Override public boolean isInProject(PsiElement e) {
    hit("isInProject");
    PsiFile file = e.getContainingFile();
    return file != null && file.getViewProvider().getVirtualFile() instanceof SourceFile;
  }

  // Unsupported until a scenario proves they are needed.
  @Override public void cleanupForNextTest() { no("cleanupForNextTest"); }
  @Override public void dropResolveCacheRegularly(ProgressIndicator i) { no("dropResolveCacheRegularly"); }
  @Override public void setAssertOnFileLoadingFilter(VirtualFileFilter f, Disposable d) { no("setAssertOnFileLoadingFilter"); }
  // A test-only guard against unexpected file loading; the host never sets one.
  @Override public boolean isAssertOnFileLoading(VirtualFile f) {
    hit("isAssertOnFileLoading");
    return false;
  }
  @Override public FileManager getFileManager() {
    hit("getFileManager");
    return fileManager;
  }
  @Override public FileManagerEx getFileManagerEx() {
    hit("getFileManagerEx");
    return fileManager;
  }
  @Override public void addTreeChangePreprocessor(PsiTreeChangePreprocessor p, Disposable d) { no("addTreeChangePreprocessor"); }
  @Override public void addTreeChangePreprocessor(PsiTreeChangePreprocessor p) { no("addTreeChangePreprocessor"); }
  @Override public void removeTreeChangePreprocessor(PsiTreeChangePreprocessor p) { no("removeTreeChangePreprocessor"); }
  @Override public void addTreeChangePreprocessorBackgroundable(PsiTreeChangePreprocessor p, Disposable d) { no("addTreeChangePreprocessorBackgroundable"); }
  @Override public void reloadFromDisk(PsiFile f) { no("reloadFromDisk"); }
  @Override public void addPsiTreeChangeListener(PsiTreeChangeListener l) { no("addPsiTreeChangeListener"); }
  @Override public void addPsiTreeChangeListener(PsiTreeChangeListener l, Disposable d) { no("addPsiTreeChangeListener"); }
  @Override public void addPsiTreeChangeListenerBackgroundable(PsiTreeChangeListener l, Disposable d) { no("addPsiTreeChangeListenerBackgroundable"); }
  @Override public void removePsiTreeChangeListener(PsiTreeChangeListener l) { no("removePsiTreeChangeListener"); }
  @Override public void startBatchFilesProcessingMode() { no("startBatchFilesProcessingMode"); }
  @Override public void finishBatchFilesProcessingMode() { no("finishBatchFilesProcessingMode"); }
  // A hint that many files are processed (the IDE then lets their PSI go sooner); the host has no such mode.
  @Override public <T> T runInBatchFilesMode(Computable<T> c) {
    hit("runInBatchFilesMode");
    return c.compute();
  }
  @Override public void dropResolveCaches() { no("dropResolveCaches"); }
  @Override public void dropPsiCaches() { no("dropPsiCaches"); }
}
