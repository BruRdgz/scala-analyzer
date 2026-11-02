package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_ModuleManager extends com.intellij.openapi.module.ModuleManager {
  private final Dispatch d;
  public Host_ModuleManager(Dispatch d) { this.d = d; }
  @Override public com.intellij.openapi.module.Module loadModule(java.lang.String a0) throws java.io.IOException, org.jdom.JDOMException, com.intellij.openapi.module.ModuleWithNameAlreadyExists { return (com.intellij.openapi.module.Module) d.call("ModuleManager.loadModule", new Object[]{a0}); }
  @Override public com.intellij.openapi.module.Module loadModule(java.nio.file.Path a0) throws java.io.IOException, com.intellij.openapi.module.ModuleWithNameAlreadyExists { return (com.intellij.openapi.module.Module) d.call("ModuleManager.loadModule", new Object[]{a0}); }
  @Override public com.intellij.openapi.module.Module newModule(java.lang.String a0, java.lang.String a1) { return (com.intellij.openapi.module.Module) d.call("ModuleManager.newModule", new Object[]{a0, a1}); }
  @Override public void disposeModule(com.intellij.openapi.module.Module a0) { d.call("ModuleManager.disposeModule", new Object[]{a0}); }
  @Override public com.intellij.openapi.module.Module[] getModules() { return (com.intellij.openapi.module.Module[]) d.call("ModuleManager.getModules", new Object[]{}); }
  @Override public com.intellij.openapi.module.Module findModuleByName(java.lang.String a0) { return (com.intellij.openapi.module.Module) d.call("ModuleManager.findModuleByName", new Object[]{a0}); }
  @Override public com.intellij.openapi.module.Module[] getSortedModules() { return (com.intellij.openapi.module.Module[]) d.call("ModuleManager.getSortedModules", new Object[]{}); }
  @Override public java.util.Comparator moduleDependencyComparator() { return (java.util.Comparator) d.call("ModuleManager.moduleDependencyComparator", new Object[]{}); }
  @Override public java.util.List getModuleDependentModules(com.intellij.openapi.module.Module a0) { return (java.util.List) d.call("ModuleManager.getModuleDependentModules", new Object[]{a0}); }
  @Override public boolean isModuleDependent(com.intellij.openapi.module.Module a0, com.intellij.openapi.module.Module a1) { return (Boolean) d.call("ModuleManager.isModuleDependent", new Object[]{a0, a1}); }
  @Override public com.intellij.util.graph.Graph moduleGraph() { return (com.intellij.util.graph.Graph) d.call("ModuleManager.moduleGraph", new Object[]{}); }
  @Override public com.intellij.util.graph.Graph moduleGraph(boolean a0) { return (com.intellij.util.graph.Graph) d.call("ModuleManager.moduleGraph", new Object[]{a0}); }
  @Override public com.intellij.openapi.module.ModifiableModuleModel getModifiableModel() { return (com.intellij.openapi.module.ModifiableModuleModel) d.call("ModuleManager.getModifiableModel", new Object[]{}); }
  @Override public java.util.Collection getAllModuleDescriptions() { return (java.util.Collection) d.call("ModuleManager.getAllModuleDescriptions", new Object[]{}); }
  @Override public java.util.Collection getUnloadedModuleDescriptions() { return (java.util.Collection) d.call("ModuleManager.getUnloadedModuleDescriptions", new Object[]{}); }
  @Override public com.intellij.openapi.module.UnloadedModuleDescription getUnloadedModuleDescription(java.lang.String a0) { return (com.intellij.openapi.module.UnloadedModuleDescription) d.call("ModuleManager.getUnloadedModuleDescription", new Object[]{a0}); }
  @Override public com.intellij.openapi.module.ModuleGrouper getModuleGrouper(com.intellij.openapi.module.ModifiableModuleModel a0) { return (com.intellij.openapi.module.ModuleGrouper) d.call("ModuleManager.getModuleGrouper", new Object[]{a0}); }
  @Override public java.lang.Object setUnloadedModules(java.util.List a0, kotlin.coroutines.Continuation a1) { return (java.lang.Object) d.call("ModuleManager.setUnloadedModules", new Object[]{a0, a1}); }
  @Override public void setUnloadedModulesSync(java.util.List a0) { d.call("ModuleManager.setUnloadedModulesSync", new Object[]{a0}); }
}
