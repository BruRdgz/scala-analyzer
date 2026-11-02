package scalaanalyzer.platform;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class Host_EncodingManager extends com.intellij.openapi.vfs.encoding.EncodingManager {
  private final Dispatch d;
  public Host_EncodingManager(Dispatch d) { super(); this.d = d; }
  @Override public java.nio.charset.Charset getCachedCharsetFromContent(com.intellij.openapi.editor.Document a0) { return (java.nio.charset.Charset) d.call("EncodingManager.getCachedCharsetFromContent", new Object[]{a0}); }
  @Override public java.nio.charset.Charset getDefaultCharsetForPropertiesFiles(com.intellij.openapi.vfs.VirtualFile a0) { return (java.nio.charset.Charset) d.call("EncodingManager.getDefaultCharsetForPropertiesFiles", new Object[]{a0}); }
  @Override public java.lang.String getDefaultCharsetName() { return (java.lang.String) d.call("EncodingManager.getDefaultCharsetName", new Object[]{}); }
  @Override public java.nio.charset.Charset getDefaultCharset() { return (java.nio.charset.Charset) d.call("EncodingManager.getDefaultCharset", new Object[]{}); }
  @Override public java.nio.charset.Charset getDefaultConsoleEncoding() { return (java.nio.charset.Charset) d.call("EncodingManager.getDefaultConsoleEncoding", new Object[]{}); }
  @Override public java.nio.charset.Charset getEncoding(com.intellij.openapi.vfs.VirtualFile a0, boolean a1) { return (java.nio.charset.Charset) d.call("EncodingManager.getEncoding", new Object[]{a0, a1}); }
  @Override public java.util.Collection getFavorites() { return (java.util.Collection) d.call("EncodingManager.getFavorites", new Object[]{}); }
  @Override public boolean isNative2AsciiForPropertiesFiles() { return (Boolean) d.call("EncodingManager.isNative2AsciiForPropertiesFiles", new Object[]{}); }
  @Override public boolean isNative2Ascii(com.intellij.openapi.vfs.VirtualFile a0) { return (Boolean) d.call("EncodingManager.isNative2Ascii", new Object[]{a0}); }
  @Override public void setDefaultCharsetForPropertiesFiles(com.intellij.openapi.vfs.VirtualFile a0, java.nio.charset.Charset a1) { d.call("EncodingManager.setDefaultCharsetForPropertiesFiles", new Object[]{a0, a1}); }
  @Override public void setEncoding(com.intellij.openapi.vfs.VirtualFile a0, java.nio.charset.Charset a1) { d.call("EncodingManager.setEncoding", new Object[]{a0, a1}); }
  @Override public void setNative2AsciiForPropertiesFiles(com.intellij.openapi.vfs.VirtualFile a0, boolean a1) { d.call("EncodingManager.setNative2AsciiForPropertiesFiles", new Object[]{a0, a1}); }
}
