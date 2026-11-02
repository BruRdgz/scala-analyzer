package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_PropertiesComponent extends com.intellij.ide.util.PropertiesComponent {
  private final Dispatch d;
  public Host_PropertiesComponent(Dispatch d) { this.d = d; }
  @Override public java.lang.String getValue(java.lang.String a0) { return (java.lang.String) d.call("PropertiesComponent.getValue", new Object[]{a0}); }
  @Override public void setValue(java.lang.String a0, boolean a1, boolean a2) { d.call("PropertiesComponent.setValue", new Object[]{a0, a1, a2}); }
  @Override public void setValue(java.lang.String a0, java.lang.String a1, java.lang.String a2) { d.call("PropertiesComponent.setValue", new Object[]{a0, a1, a2}); }
  @Override public void setValue(java.lang.String a0, int a1, int a2) { d.call("PropertiesComponent.setValue", new Object[]{a0, a1, a2}); }
  @Override public void setValue(java.lang.String a0, float a1, float a2) { d.call("PropertiesComponent.setValue", new Object[]{a0, a1, a2}); }
  @Override public void setValue(java.lang.String a0, java.lang.String a1) { d.call("PropertiesComponent.setValue", new Object[]{a0, a1}); }
  @Override public boolean isValueSet(java.lang.String a0) { return (Boolean) d.call("PropertiesComponent.isValueSet", new Object[]{a0}); }
  @Override public void unsetValue(java.lang.String a0) { d.call("PropertiesComponent.unsetValue", new Object[]{a0}); }
  @Override public java.lang.String[] getValues(java.lang.String a0) { return (java.lang.String[]) d.call("PropertiesComponent.getValues", new Object[]{a0}); }
  @Override public void setValues(java.lang.String a0, java.lang.String[] a1) { d.call("PropertiesComponent.setValues", new Object[]{a0, a1}); }
  @Override public java.util.List getList(java.lang.String a0) { return (java.util.List) d.call("PropertiesComponent.getList", new Object[]{a0}); }
  @Override public void setList(java.lang.String a0, java.util.Collection a1) { d.call("PropertiesComponent.setList", new Object[]{a0, a1}); }
  @Override public boolean updateValue(java.lang.String a0, boolean a1) { return (Boolean) d.call("PropertiesComponent.updateValue", new Object[]{a0, a1}); }
}
