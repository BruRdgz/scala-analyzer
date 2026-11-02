package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_PsiShortNamesCache extends com.intellij.psi.search.PsiShortNamesCache {
  private final Dispatch d;
  public Host_PsiShortNamesCache(Dispatch d) { super(); this.d = d; }
  @Override public java.lang.String[] getAllClassNames() { return (java.lang.String[]) d.call("PsiShortNamesCache.getAllClassNames", new Object[]{}); }
  @Override public java.lang.String[] getAllFieldNames() { return (java.lang.String[]) d.call("PsiShortNamesCache.getAllFieldNames", new Object[]{}); }
  @Override public java.lang.String[] getAllMethodNames() { return (java.lang.String[]) d.call("PsiShortNamesCache.getAllMethodNames", new Object[]{}); }
  @Override public com.intellij.psi.PsiClass[] getClassesByName(java.lang.String a0, com.intellij.psi.search.GlobalSearchScope a1) { return (com.intellij.psi.PsiClass[]) d.call("PsiShortNamesCache.getClassesByName", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiField[] getFieldsByNameIfNotMoreThan(java.lang.String a0, com.intellij.psi.search.GlobalSearchScope a1, int a2) { return (com.intellij.psi.PsiField[]) d.call("PsiShortNamesCache.getFieldsByNameIfNotMoreThan", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.psi.PsiField[] getFieldsByName(java.lang.String a0, com.intellij.psi.search.GlobalSearchScope a1) { return (com.intellij.psi.PsiField[]) d.call("PsiShortNamesCache.getFieldsByName", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiMethod[] getMethodsByNameIfNotMoreThan(java.lang.String a0, com.intellij.psi.search.GlobalSearchScope a1, int a2) { return (com.intellij.psi.PsiMethod[]) d.call("PsiShortNamesCache.getMethodsByNameIfNotMoreThan", new Object[]{a0, a1, a2}); }
  @Override public com.intellij.psi.PsiMethod[] getMethodsByName(java.lang.String a0, com.intellij.psi.search.GlobalSearchScope a1) { return (com.intellij.psi.PsiMethod[]) d.call("PsiShortNamesCache.getMethodsByName", new Object[]{a0, a1}); }
  @Override public boolean processMethodsWithName(java.lang.String a0, com.intellij.psi.search.GlobalSearchScope a1, com.intellij.util.Processor a2) { return (Boolean) d.call("PsiShortNamesCache.processMethodsWithName", new Object[]{a0, a1, a2}); }
}
