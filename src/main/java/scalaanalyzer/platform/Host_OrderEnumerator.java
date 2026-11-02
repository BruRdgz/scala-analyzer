package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_OrderEnumerator extends com.intellij.openapi.roots.OrderEnumerator {
  private final Dispatch d;
  public Host_OrderEnumerator(Dispatch d) { super(); this.d = d; }
  @Override public com.intellij.openapi.roots.OrderRootsEnumerator classes() { return (com.intellij.openapi.roots.OrderRootsEnumerator) d.call("OrderEnumerator.classes", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator compileOnly() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.compileOnly", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator exportedOnly() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.exportedOnly", new Object[]{}); }
  @Override public void forEachLibrary(com.intellij.util.Processor a0) { d.call("OrderEnumerator.forEachLibrary", new Object[]{a0}); }
  @Override public void forEachModule(com.intellij.util.Processor a0) { d.call("OrderEnumerator.forEachModule", new Object[]{a0}); }
  @Override public void forEach(com.intellij.util.Processor a0) { d.call("OrderEnumerator.forEach", new Object[]{a0}); }
  @Override public java.lang.Object process(com.intellij.openapi.roots.RootPolicy a0, java.lang.Object a1) { return (java.lang.Object) d.call("OrderEnumerator.process", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator productionOnly() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.productionOnly", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator recursively() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.recursively", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderRootsEnumerator roots(com.intellij.openapi.roots.OrderRootType a0) { return (com.intellij.openapi.roots.OrderRootsEnumerator) d.call("OrderEnumerator.roots", new Object[]{a0}); }
  @Override public com.intellij.openapi.roots.OrderRootsEnumerator roots(com.intellij.util.NotNullFunction a0) { return (com.intellij.openapi.roots.OrderRootsEnumerator) d.call("OrderEnumerator.roots", new Object[]{a0}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator runtimeOnly() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.runtimeOnly", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator satisfying(com.intellij.openapi.util.Condition a0) { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.satisfying", new Object[]{a0}); }
  @Override public boolean shouldRecurse(com.intellij.openapi.roots.ModuleOrderEntry a0, java.util.List a1) { return (Boolean) d.call("OrderEnumerator.shouldRecurse", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.roots.OrderRootsEnumerator sources() { return (com.intellij.openapi.roots.OrderRootsEnumerator) d.call("OrderEnumerator.sources", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator using(com.intellij.openapi.roots.RootModelProvider a0) { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.using", new Object[]{a0}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator withoutDepModules() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.withoutDepModules", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator withoutLibraries() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.withoutLibraries", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator withoutModuleSourceEntries() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.withoutModuleSourceEntries", new Object[]{}); }
  @Override public com.intellij.openapi.roots.OrderEnumerator withoutSdk() { return (com.intellij.openapi.roots.OrderEnumerator) d.call("OrderEnumerator.withoutSdk", new Object[]{}); }
}
