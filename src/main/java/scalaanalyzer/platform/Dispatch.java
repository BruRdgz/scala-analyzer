package scalaanalyzer.platform;

/** Answer table for generated Host_* subclasses of abstract platform classes. */
public interface Dispatch {
  /**
   * Answers a generated adapter call or rejects an unsupported method.
   *
   * @param method diagnostic method name supplied by the generated adapter
   * @param args boxed arguments, possibly null for a parameterless method
   * @return the boxed result, or null for a void or intentionally absent result
   */
  Object call(String method, Object[] args);
}
