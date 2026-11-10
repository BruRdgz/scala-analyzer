package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_FileTemplateManager extends com.intellij.ide.fileTemplates.FileTemplateManager {
  private final Dispatch d;
  public Host_FileTemplateManager(Dispatch d) { super(); this.d = d; }
  @Override public void addRecentName(java.lang.String a0) { d.call("FileTemplateManager.addRecentName", new Object[]{a0}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate addTemplate(java.lang.String a0, java.lang.String a1) { return (com.intellij.ide.fileTemplates.FileTemplate) d.call("FileTemplateManager.addTemplate", new Object[]{a0, a1}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate findInternalTemplate(java.lang.String a0) { return (com.intellij.ide.fileTemplates.FileTemplate) d.call("FileTemplateManager.findInternalTemplate", new Object[]{a0}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate[] getAllCodeTemplates() { return (com.intellij.ide.fileTemplates.FileTemplate[]) d.call("FileTemplateManager.getAllCodeTemplates", new Object[]{}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate[] getAllJ2eeTemplates() { return (com.intellij.ide.fileTemplates.FileTemplate[]) d.call("FileTemplateManager.getAllJ2eeTemplates", new Object[]{}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate[] getAllPatterns() { return (com.intellij.ide.fileTemplates.FileTemplate[]) d.call("FileTemplateManager.getAllPatterns", new Object[]{}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate[] getAllTemplates() { return (com.intellij.ide.fileTemplates.FileTemplate[]) d.call("FileTemplateManager.getAllTemplates", new Object[]{}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate getCodeTemplate(java.lang.String a0) { return (com.intellij.ide.fileTemplates.FileTemplate) d.call("FileTemplateManager.getCodeTemplate", new Object[]{a0}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplatesScheme getCurrentScheme() { return (com.intellij.ide.fileTemplates.FileTemplatesScheme) d.call("FileTemplateManager.getCurrentScheme", new Object[]{}); }
  @Override public java.util.Properties getDefaultProperties() { return (java.util.Properties) d.call("FileTemplateManager.getDefaultProperties", new Object[]{}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate getDefaultTemplate(java.lang.String a0) { return (com.intellij.ide.fileTemplates.FileTemplate) d.call("FileTemplateManager.getDefaultTemplate", new Object[]{a0}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate getInternalTemplate(java.lang.String a0) { return (com.intellij.ide.fileTemplates.FileTemplate) d.call("FileTemplateManager.getInternalTemplate", new Object[]{a0}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate[] getInternalTemplates() { return (com.intellij.ide.fileTemplates.FileTemplate[]) d.call("FileTemplateManager.getInternalTemplates", new Object[]{}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate getJ2eeTemplate(java.lang.String a0) { return (com.intellij.ide.fileTemplates.FileTemplate) d.call("FileTemplateManager.getJ2eeTemplate", new Object[]{a0}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate getPattern(java.lang.String a0) { return (com.intellij.ide.fileTemplates.FileTemplate) d.call("FileTemplateManager.getPattern", new Object[]{a0}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplatesScheme getProjectScheme() { return (com.intellij.ide.fileTemplates.FileTemplatesScheme) d.call("FileTemplateManager.getProjectScheme", new Object[]{}); }
  @Override public java.util.Collection getRecentNames() { return (java.util.Collection) d.call("FileTemplateManager.getRecentNames", new Object[]{}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate getTemplate(java.lang.String a0) { return (com.intellij.ide.fileTemplates.FileTemplate) d.call("FileTemplateManager.getTemplate", new Object[]{a0}); }
  @Override public com.intellij.ide.fileTemplates.FileTemplate[] getTemplates(java.lang.String a0) { return (com.intellij.ide.fileTemplates.FileTemplate[]) d.call("FileTemplateManager.getTemplates", new Object[]{a0}); }
  @Override public java.lang.String internalTemplateToSubject(java.lang.String a0) { return (java.lang.String) d.call("FileTemplateManager.internalTemplateToSubject", new Object[]{a0}); }
  @Override public void removeTemplate(com.intellij.ide.fileTemplates.FileTemplate a0) { d.call("FileTemplateManager.removeTemplate", new Object[]{a0}); }
  @Override public void saveAllTemplates() { d.call("FileTemplateManager.saveAllTemplates", new Object[]{}); }
  @Override public void setCurrentScheme(com.intellij.ide.fileTemplates.FileTemplatesScheme a0) { d.call("FileTemplateManager.setCurrentScheme", new Object[]{a0}); }
  @Override public void setTemplates(java.lang.String a0, java.util.Collection a1) { d.call("FileTemplateManager.setTemplates", new Object[]{a0, a1}); }
}
