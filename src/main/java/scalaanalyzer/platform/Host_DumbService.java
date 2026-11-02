package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_DumbService extends com.intellij.openapi.project.DumbService {
  private final Dispatch d;
  public Host_DumbService(Dispatch d) { this.d = d; }
  @Override public kotlinx.coroutines.flow.StateFlow getState() { return (kotlinx.coroutines.flow.StateFlow) d.call("DumbService.getState", new Object[]{}); }
  @Override public void queueTask(com.intellij.openapi.project.DumbModeTask a0) { d.call("DumbService.queueTask", new Object[]{a0}); }
  @Override public void cancelTask(com.intellij.openapi.project.DumbModeTask a0) { d.call("DumbService.cancelTask", new Object[]{a0}); }
  @Override public void cancelAllTasksAndWait() { d.call("DumbService.cancelAllTasksAndWait", new Object[]{}); }
  @Override public void completeJustSubmittedTasks() { d.call("DumbService.completeJustSubmittedTasks", new Object[]{}); }
  @Override public java.lang.Object runInDumbMode(java.lang.String a0, kotlin.jvm.functions.Function1 a1, kotlin.coroutines.Continuation a2) { return (java.lang.Object) d.call("DumbService.runInDumbMode", new Object[]{a0, a1, a2}); }
  @Override public javax.swing.JComponent wrapGently(javax.swing.JComponent a0, com.intellij.openapi.Disposable a1) { return (javax.swing.JComponent) d.call("DumbService.wrapGently", new Object[]{a0, a1}); }
  @Override public void showDumbModeNotification(java.lang.String a0) { d.call("DumbService.showDumbModeNotification", new Object[]{a0}); }
  @Override public void showDumbModeNotificationForFunctionalityWithCoalescing(java.lang.String a0, com.intellij.openapi.project.DumbModeBlockedFunctionality a1, java.lang.Object a2) { d.call("DumbService.showDumbModeNotificationForFunctionalityWithCoalescing", new Object[]{a0, a1, a2}); }
  @Override public void showDumbModeNotificationForAction(java.lang.String a0, java.lang.String a1) { d.call("DumbService.showDumbModeNotificationForAction", new Object[]{a0, a1}); }
  @Override public void showDumbModeNotificationForFailedAction(java.lang.String a0, java.lang.String a1) { d.call("DumbService.showDumbModeNotificationForFailedAction", new Object[]{a0, a1}); }
  @Override public void showDumbModeActionBalloon(java.lang.String a0, java.lang.Runnable a1, java.util.List a2) { d.call("DumbService.showDumbModeActionBalloon", new Object[]{a0, a1, a2}); }
  @Override public void setAlternativeResolveEnabled(boolean a0) { d.call("DumbService.setAlternativeResolveEnabled", new Object[]{a0}); }
  @Override public boolean isAlternativeResolveEnabled() { return (Boolean) d.call("DumbService.isAlternativeResolveEnabled", new Object[]{}); }
  @Override public java.lang.Object suspendIndexingAndRun(java.lang.String a0, kotlin.jvm.functions.Function2 a1, kotlin.coroutines.Continuation a2) { return (java.lang.Object) d.call("DumbService.suspendIndexingAndRun", new Object[]{a0, a1, a2}); }
  @Override public void suspendIndexingAndRun(java.lang.String a0, java.lang.Runnable a1) { d.call("DumbService.suspendIndexingAndRun", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.application.AccessToken runWithWaitForSmartModeDisabled() { return (com.intellij.openapi.application.AccessToken) d.call("DumbService.runWithWaitForSmartModeDisabled", new Object[]{}); }
  @Override public void unsafeRunWhenSmart(java.lang.Runnable a0) { d.call("DumbService.unsafeRunWhenSmart", new Object[]{a0}); }
  @Override public boolean isDumb() { return (Boolean) d.call("DumbService.isDumb", new Object[]{}); }
  @Override public com.intellij.openapi.util.ModificationTracker getModificationTracker() { return (com.intellij.openapi.util.ModificationTracker) d.call("DumbService.getModificationTracker", new Object[]{}); }
  @Override public java.lang.Throwable getDumbModeStartTrace() { return (java.lang.Throwable) d.call("DumbService.getDumbModeStartTrace", new Object[]{}); }
  @Override public boolean canRunSmart() { return (Boolean) d.call("DumbService.canRunSmart", new Object[]{}); }
  @Override public void runWhenSmart(java.lang.Runnable a0) { d.call("DumbService.runWhenSmart", new Object[]{a0}); }
  @Override public void waitForSmartMode() { d.call("DumbService.waitForSmartMode", new Object[]{}); }
  @Override public boolean waitForSmartMode(long a0) { return (Boolean) d.call("DumbService.waitForSmartMode", new Object[]{a0}); }
  @Override public void showDumbModeNotificationForFunctionality(java.lang.String a0, com.intellij.openapi.project.DumbModeBlockedFunctionality a1) { d.call("DumbService.showDumbModeNotificationForFunctionality", new Object[]{a0, a1}); }
  @Override public void smartInvokeLater(java.lang.Runnable a0) { d.call("DumbService.smartInvokeLater", new Object[]{a0}); }
  @Override public void smartInvokeLater(java.lang.Runnable a0, com.intellij.openapi.application.ModalityState a1) { d.call("DumbService.smartInvokeLater", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.project.Project getProject() { return (com.intellij.openapi.project.Project) d.call("DumbService.getProject", new Object[]{}); }
}
