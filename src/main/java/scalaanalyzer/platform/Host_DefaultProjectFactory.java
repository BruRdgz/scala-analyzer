package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_DefaultProjectFactory extends com.intellij.openapi.project.DefaultProjectFactory {
  private final Dispatch d;
  public Host_DefaultProjectFactory(Dispatch d) { super(); this.d = d; }
  @Override public com.intellij.openapi.project.Project getDefaultProject() { return (com.intellij.openapi.project.Project) d.call("DefaultProjectFactory.getDefaultProject", new Object[]{}); }
}
