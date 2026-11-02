package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_NullableNotNullManager extends com.intellij.codeInsight.NullableNotNullManager {
  private final Dispatch d;
  public Host_NullableNotNullManager(com.intellij.openapi.project.Project s0, Dispatch d) { super(s0); this.d = d; }
  @Override public boolean canAnnotateLocals(java.lang.String a0) { return (Boolean) d.call("NullableNotNullManager.canAnnotateLocals", new Object[]{a0}); }
  @Override public com.intellij.codeInsight.NullabilityAnnotationInfo findEffectiveNullabilityInfo(com.intellij.psi.PsiModifierListOwner a0) { return (com.intellij.codeInsight.NullabilityAnnotationInfo) d.call("NullableNotNullManager.findEffectiveNullabilityInfo", new Object[]{a0}); }
  @Override protected com.intellij.codeInsight.NullableNotNullManager.NullabilityAnnotationDataHolder getAllNullabilityAnnotationsWithNickNames() { return (com.intellij.codeInsight.NullableNotNullManager.NullabilityAnnotationDataHolder) d.call("NullableNotNullManager.getAllNullabilityAnnotationsWithNickNames", new Object[]{}); }
  @Override public java.util.Optional getAnnotationNullability(java.lang.String a0) { return (java.util.Optional) d.call("NullableNotNullManager.getAnnotationNullability", new Object[]{a0}); }
  @Override public java.util.List getConflictingContainerAnnotations(com.intellij.psi.PsiModifierList a0) { return (java.util.List) d.call("NullableNotNullManager.getConflictingContainerAnnotations", new Object[]{a0}); }
  @Override public java.lang.String getDefaultAnnotation(com.intellij.codeInsight.Nullability a0, com.intellij.psi.PsiElement a1) { return (java.lang.String) d.call("NullableNotNullManager.getDefaultAnnotation", new Object[]{a0, a1}); }
  @Override public java.lang.String getDefaultNotNull() { return (java.lang.String) d.call("NullableNotNullManager.getDefaultNotNull", new Object[]{}); }
  @Override public java.util.List getDefaultNotNulls() { return (java.util.List) d.call("NullableNotNullManager.getDefaultNotNulls", new Object[]{}); }
  @Override public java.lang.String getDefaultNullable() { return (java.lang.String) d.call("NullableNotNullManager.getDefaultNullable", new Object[]{}); }
  @Override public java.util.List getDefaultNullables() { return (java.util.List) d.call("NullableNotNullManager.getDefaultNullables", new Object[]{}); }
  @Override public java.util.List getInstrumentedNotNulls() { return (java.util.List) d.call("NullableNotNullManager.getInstrumentedNotNulls", new Object[]{}); }
  @Override public java.util.List getNotNulls() { return (java.util.List) d.call("NullableNotNullManager.getNotNulls", new Object[]{}); }
  @Override public java.lang.String getNullabilityAnnotationInSameFramework(java.lang.String a0, com.intellij.codeInsight.Nullability a1) { return (java.lang.String) d.call("NullableNotNullManager.getNullabilityAnnotationInSameFramework", new Object[]{a0, a1}); }
  @Override public java.util.List getNullables() { return (java.util.List) d.call("NullableNotNullManager.getNullables", new Object[]{}); }
  @Override protected com.intellij.codeInsight.ContextNullabilityInfo getNullityDefault(com.intellij.psi.PsiModifierListOwner a0, com.intellij.psi.PsiAnnotation.TargetType[] a1) { return (com.intellij.codeInsight.ContextNullabilityInfo) d.call("NullableNotNullManager.getNullityDefault", new Object[]{a0, a1}); }
  @Override public boolean isTypeUseAnnotationLocationRestricted(java.lang.String a0) { return (Boolean) d.call("NullableNotNullManager.isTypeUseAnnotationLocationRestricted", new Object[]{a0}); }
  @Override public void setDefaultNotNull(java.lang.String a0) { d.call("NullableNotNullManager.setDefaultNotNull", new Object[]{a0}); }
  @Override public void setDefaultNullable(java.lang.String a0) { d.call("NullableNotNullManager.setDefaultNullable", new Object[]{a0}); }
  @Override public void setInstrumentedNotNulls(java.util.List a0) { d.call("NullableNotNullManager.setInstrumentedNotNulls", new Object[]{a0}); }
  @Override public void setNotNulls(java.lang.String[] a0) { d.call("NullableNotNullManager.setNotNulls", new Object[]{a0}); }
  @Override public void setNullables(java.lang.String[] a0) { d.call("NullableNotNullManager.setNullables", new Object[]{a0}); }
}
