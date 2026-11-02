package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_ProjectTaskManager extends com.intellij.task.ProjectTaskManager {
  private final Dispatch d;
  public Host_ProjectTaskManager(com.intellij.openapi.project.Project s0, Dispatch d) { super(s0); this.d = d; }
  @Override public org.jetbrains.concurrency.Promise buildAllModules() { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.buildAllModules", new Object[]{}); }
  @Override public org.jetbrains.concurrency.Promise build(com.intellij.openapi.module.Module[] a0) { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.build", new Object[]{a0}); }
  @Override public org.jetbrains.concurrency.Promise build(com.intellij.openapi.roots.ProjectModelBuildableElement[] a0) { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.build", new Object[]{a0}); }
  @Override public org.jetbrains.concurrency.Promise compile(com.intellij.openapi.vfs.VirtualFile[] a0) { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.compile", new Object[]{a0}); }
  @Override public com.intellij.task.ProjectTask createAllModulesBuildTask(boolean a0, com.intellij.openapi.project.Project a1) { return (com.intellij.task.ProjectTask) d.call("ProjectTaskManager.createAllModulesBuildTask", new Object[]{a0, a1}); }
  @Override public com.intellij.task.ProjectTask createBuildTask(boolean a0, com.intellij.openapi.roots.ProjectModelBuildableElement[] a1) { return (com.intellij.task.ProjectTask) d.call("ProjectTaskManager.createBuildTask", new Object[]{a0, a1}); }
  @Override public com.intellij.task.ProjectTask createModulesBuildTask(com.intellij.openapi.module.Module[] a0, boolean a1, boolean a2, boolean a3, boolean a4) { return (com.intellij.task.ProjectTask) d.call("ProjectTaskManager.createModulesBuildTask", new Object[]{a0, a1, a2, a3, a4}); }
  @Override public com.intellij.execution.runners.ExecutionEnvironment createProjectTaskExecutionEnvironment(com.intellij.task.ProjectTask a0) { return (com.intellij.execution.runners.ExecutionEnvironment) d.call("ProjectTaskManager.createProjectTaskExecutionEnvironment", new Object[]{a0}); }
  @Override public org.jetbrains.concurrency.Promise rebuildAllModules() { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.rebuildAllModules", new Object[]{}); }
  @Override public org.jetbrains.concurrency.Promise rebuild(com.intellij.openapi.module.Module[] a0) { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.rebuild", new Object[]{a0}); }
  @Override public org.jetbrains.concurrency.Promise rebuild(com.intellij.openapi.roots.ProjectModelBuildableElement[] a0) { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.rebuild", new Object[]{a0}); }
  @Override public org.jetbrains.concurrency.Promise run(com.intellij.task.ProjectTaskContext a0, com.intellij.task.ProjectTask a1) { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.run", new Object[]{a0, a1}); }
  @Override public org.jetbrains.concurrency.Promise run(com.intellij.task.ProjectTask a0) { return (org.jetbrains.concurrency.Promise) d.call("ProjectTaskManager.run", new Object[]{a0}); }
}
