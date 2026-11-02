package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_ExternalAnnotationsManager extends com.intellij.codeInsight.ExternalAnnotationsManager {
  private final Dispatch d;
  public Host_ExternalAnnotationsManager(Dispatch d) { super(); this.d = d; }
  @Override public void annotateExternally(com.intellij.psi.PsiModifierListOwner a0, java.lang.String a1, com.intellij.psi.PsiFile a2, com.intellij.psi.PsiNameValuePair[] a3) throws com.intellij.codeInsight.ExternalAnnotationsManager.CanceledConfigurationException { d.call("ExternalAnnotationsManager.annotateExternally", new Object[]{a0, a1, a2, a3}); }
  @Override public com.intellij.codeInsight.ExternalAnnotationsManager.AnnotationPlace chooseAnnotationsPlaceNoUi(com.intellij.psi.PsiElement a0) { return (com.intellij.codeInsight.ExternalAnnotationsManager.AnnotationPlace) d.call("ExternalAnnotationsManager.chooseAnnotationsPlaceNoUi", new Object[]{a0}); }
  @Override public com.intellij.codeInsight.ExternalAnnotationsManager.AnnotationPlace chooseAnnotationsPlace(com.intellij.psi.PsiElement a0) { return (com.intellij.codeInsight.ExternalAnnotationsManager.AnnotationPlace) d.call("ExternalAnnotationsManager.chooseAnnotationsPlace", new Object[]{a0}); }
  @Override public boolean deannotate(com.intellij.psi.PsiModifierListOwner a0, java.lang.String a1) { return (Boolean) d.call("ExternalAnnotationsManager.deannotate", new Object[]{a0, a1}); }
  @Override public boolean editExternalAnnotation(com.intellij.psi.PsiModifierListOwner a0, java.lang.String a1, com.intellij.psi.PsiNameValuePair[] a2) { return (Boolean) d.call("ExternalAnnotationsManager.editExternalAnnotation", new Object[]{a0, a1, a2}); }
  @Override public java.util.List findDefaultConstructorExternalAnnotations(com.intellij.psi.PsiClass a0, java.lang.String a1) { return (java.util.List) d.call("ExternalAnnotationsManager.findDefaultConstructorExternalAnnotations", new Object[]{a0, a1}); }
  @Override public java.util.List findDefaultConstructorExternalAnnotations(com.intellij.psi.PsiClass a0) { return (java.util.List) d.call("ExternalAnnotationsManager.findDefaultConstructorExternalAnnotations", new Object[]{a0}); }
  @Override public com.intellij.psi.PsiAnnotation findExternalAnnotation(com.intellij.psi.PsiModifierListOwner a0, java.lang.String a1) { return (com.intellij.psi.PsiAnnotation) d.call("ExternalAnnotationsManager.findExternalAnnotation", new Object[]{a0, a1}); }
  @Override public java.util.List findExternalAnnotationsFiles(com.intellij.psi.PsiModifierListOwner a0) { return (java.util.List) d.call("ExternalAnnotationsManager.findExternalAnnotationsFiles", new Object[]{a0}); }
  @Override public java.util.List findExternalAnnotations(com.intellij.psi.PsiModifierListOwner a0, java.lang.String a1) { return (java.util.List) d.call("ExternalAnnotationsManager.findExternalAnnotations", new Object[]{a0, a1}); }
  @Override public com.intellij.psi.PsiAnnotation[] findExternalAnnotations(com.intellij.psi.PsiModifierListOwner a0) { return (com.intellij.psi.PsiAnnotation[]) d.call("ExternalAnnotationsManager.findExternalAnnotations", new Object[]{a0}); }
  @Override public boolean hasAnnotationRootsForFile(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("ExternalAnnotationsManager.hasAnnotationRootsForFile", new Object[]{a0}); }
  @Override public boolean hasConfiguredAnnotationRoot(com.intellij.psi.PsiModifierListOwner a0) { return (Boolean) d.call("ExternalAnnotationsManager.hasConfiguredAnnotationRoot", new Object[]{a0}); }
  @Override public boolean isExternalAnnotationWritable(com.intellij.psi.PsiModifierListOwner a0, java.lang.String a1) { return (Boolean) d.call("ExternalAnnotationsManager.isExternalAnnotationWritable", new Object[]{a0, a1}); }
}
