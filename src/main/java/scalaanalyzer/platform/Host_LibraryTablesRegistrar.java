package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_LibraryTablesRegistrar extends com.intellij.openapi.roots.libraries.LibraryTablesRegistrar {
  private final Dispatch d;
  public Host_LibraryTablesRegistrar(Dispatch d) { this.d = d; }
  @Override public com.intellij.openapi.roots.libraries.LibraryTable getLibraryTable(com.intellij.openapi.project.Project a0) { return (com.intellij.openapi.roots.libraries.LibraryTable) d.call("LibraryTablesRegistrar.getLibraryTable", new Object[]{a0}); }
  @Override public com.intellij.openapi.roots.libraries.LibraryTable getLibraryTable() { return (com.intellij.openapi.roots.libraries.LibraryTable) d.call("LibraryTablesRegistrar.getLibraryTable", new Object[]{}); }
  @Override public com.intellij.openapi.roots.libraries.LibraryTable getGlobalLibraryTable(com.intellij.openapi.project.Project a0) { return (com.intellij.openapi.roots.libraries.LibraryTable) d.call("LibraryTablesRegistrar.getGlobalLibraryTable", new Object[]{a0}); }
  @Override public com.intellij.openapi.roots.libraries.LibraryTable getLibraryTableByLevel(java.lang.String a0, com.intellij.openapi.project.Project a1) { return (com.intellij.openapi.roots.libraries.LibraryTable) d.call("LibraryTablesRegistrar.getLibraryTableByLevel", new Object[]{a0, a1}); }
  @Override public com.intellij.openapi.roots.libraries.LibraryTable getCustomLibraryTableByLevel(java.lang.String a0) { return (com.intellij.openapi.roots.libraries.LibraryTable) d.call("LibraryTablesRegistrar.getCustomLibraryTableByLevel", new Object[]{a0}); }
  @Override public java.util.List getCustomLibraryTables() { return (java.util.List) d.call("LibraryTablesRegistrar.getCustomLibraryTables", new Object[]{}); }
}
