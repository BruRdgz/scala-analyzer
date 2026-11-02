package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_AnnotationCacheOwnerNormalizer extends com.intellij.codeInsight.AnnotationCacheOwnerNormalizer {
  private final Dispatch d;
  public Host_AnnotationCacheOwnerNormalizer(Dispatch d) { super(); this.d = d; }
  @Override protected com.intellij.psi.PsiModifierListOwner doNormalize(com.intellij.psi.PsiModifierListOwner a0) { return (com.intellij.psi.PsiModifierListOwner) d.call("AnnotationCacheOwnerNormalizer.doNormalize", new Object[]{a0}); }
}
