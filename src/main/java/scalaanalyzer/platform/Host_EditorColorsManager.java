package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_EditorColorsManager extends com.intellij.openapi.editor.colors.EditorColorsManager {
  private final Dispatch d;
  public Host_EditorColorsManager(Dispatch d) { super(); this.d = d; }
  @Override public void addColorScheme(com.intellij.openapi.editor.colors.EditorColorsScheme a0) { d.call("EditorColorsManager.addColorScheme", new Object[]{a0}); }
  @Override public com.intellij.openapi.editor.colors.EditorColorsScheme getActiveVisibleScheme() { return (com.intellij.openapi.editor.colors.EditorColorsScheme) d.call("EditorColorsManager.getActiveVisibleScheme", new Object[]{}); }
  @Override public com.intellij.openapi.editor.colors.EditorColorsScheme[] getAllSchemes() { return (com.intellij.openapi.editor.colors.EditorColorsScheme[]) d.call("EditorColorsManager.getAllSchemes", new Object[]{}); }
  @Override public com.intellij.openapi.editor.colors.EditorColorsScheme getGlobalScheme() { return (com.intellij.openapi.editor.colors.EditorColorsScheme) d.call("EditorColorsManager.getGlobalScheme", new Object[]{}); }
  @Override public long getSchemeModificationCounter() { return (Long) d.call("EditorColorsManager.getSchemeModificationCounter", new Object[]{}); }
  @Override public com.intellij.openapi.editor.colors.EditorColorsScheme getScheme(java.lang.String a0) { return (com.intellij.openapi.editor.colors.EditorColorsScheme) d.call("EditorColorsManager.getScheme", new Object[]{a0}); }
  @Override public boolean isDefaultScheme(com.intellij.openapi.editor.colors.EditorColorsScheme a0) { return (Boolean) d.call("EditorColorsManager.isDefaultScheme", new Object[]{a0}); }
  @Override public boolean isUseOnlyMonospacedFonts() { return (Boolean) d.call("EditorColorsManager.isUseOnlyMonospacedFonts", new Object[]{}); }
  @Override public void setCurrentSchemeOnLafChange(com.intellij.openapi.editor.colors.EditorColorsScheme a0) { d.call("EditorColorsManager.setCurrentSchemeOnLafChange", new Object[]{a0}); }
  @Override public void setGlobalScheme(com.intellij.openapi.editor.colors.EditorColorsScheme a0) { d.call("EditorColorsManager.setGlobalScheme", new Object[]{a0}); }
  @Override public void setUseOnlyMonospacedFonts(boolean a0) { d.call("EditorColorsManager.setUseOnlyMonospacedFonts", new Object[]{a0}); }
}
