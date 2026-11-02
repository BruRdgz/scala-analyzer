import java.lang.reflect.*;
import java.util.*;

/**
 * Generates `scalaanalyzer.platform.Host_<Simple>` subclasses of abstract platform classes: every abstract method
 * forwards to `scalaanalyzer.platform.Dispatch.call("<Simple>.<method>", args)`. Uses erased signatures.
 * Usage: java -cp <classpath> Gen.java <outDir> <fqcn>...
 */
public class Gen {
  private static final String PACKAGE = "scalaanalyzer.platform";

  // Methods that are not abstract but throw in the base class, and that the host answers: forwarded like the abstract ones.
  static final Map<String, Set<String>> FORWARDED_CONCRETE = Map.of(
    "com.intellij.util.indexing.FileBasedIndex", Set.of("processFilesContainingAllKeys"));

  public static void main(String[] args) throws Exception {
    String outDir = args[0];
    for (int i = 1; i < args.length; i++) generate(outDir, Class.forName(args[i], false, Gen.class.getClassLoader()));
  }

  static String typeName(Class<?> c) {
    return c.getCanonicalName() != null ? c.getCanonicalName() : c.getName().replace('$', '.');
  }

  static String boxed(Class<?> c) {
    if (c == boolean.class) return "Boolean";
    if (c == int.class) return "Integer";
    if (c == long.class) return "Long";
    if (c == char.class) return "Character";
    if (c == byte.class) return "Byte";
    if (c == short.class) return "Short";
    if (c == float.class) return "Float";
    if (c == double.class) return "Double";
    return typeName(c);
  }

  static void generate(String outDir, Class<?> base) throws Exception {
    // Sorted by signature: reflection order is unspecified, and regenerated files should diff cleanly.
    Map<String, Method> abstracts = new TreeMap<>();
    Set<String> concrete = new HashSet<>();
    Deque<Class<?>> todo = new ArrayDeque<>();
    for (Class<?> c = base; c != null; c = c.getSuperclass()) {
      for (Method m : c.getDeclaredMethods()) {
        String key = m.getName() + Arrays.toString(m.getParameterTypes());
        if (m.isBridge() || m.isSynthetic() || Modifier.isStatic(m.getModifiers()) || Modifier.isPrivate(m.getModifiers())) continue;
        if (Modifier.isAbstract(m.getModifiers())) { if (!concrete.contains(key)) abstracts.putIfAbsent(key, m); }
        else {
          concrete.add(key);
          if (FORWARDED_CONCRETE.getOrDefault(base.getName(), Set.of()).contains(m.getName())) abstracts.putIfAbsent(key, m);
        }
      }
      todo.addAll(Arrays.asList(c.getInterfaces()));
    }
    while (!todo.isEmpty()) {
      Class<?> i = todo.pop();
      for (Method m : i.getMethods()) {
        String key = m.getName() + Arrays.toString(m.getParameterTypes());
        if (Modifier.isAbstract(m.getModifiers()) && !concrete.contains(key)) abstracts.putIfAbsent(key, m);
      }
    }
    String simple = base.getSimpleName();
    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(PACKAGE).append(";\n\n@SuppressWarnings({\"unchecked\", \"rawtypes\", \"deprecation\"})\n");
    sb.append("public final class Host_").append(simple).append(" extends ").append(typeName(base)).append(" {\n");
    sb.append("  private final Dispatch d;\n");
    // Forward the parameters of the base class's shortest non-private constructor (e.g. FileIndexFacade(Project)).
    Constructor<?> superCtor = Arrays.stream(base.getDeclaredConstructors())
      .filter(c -> !Modifier.isPrivate(c.getModifiers()))
      .min(Comparator.comparingInt(Constructor::getParameterCount))
      .orElseThrow(() -> new IllegalArgumentException("no accessible constructor in " + base.getName()));
    StringBuilder ctorParams = new StringBuilder(), superArgs = new StringBuilder();
    Class<?>[] cps = superCtor.getParameterTypes();
    for (int k = 0; k < cps.length; k++) {
      ctorParams.append(typeName(cps[k])).append(" s").append(k).append(", ");
      if (k > 0) superArgs.append(", ");
      superArgs.append("s").append(k);
    }
    sb.append("  public Host_").append(simple).append("(").append(ctorParams).append("Dispatch d) { super(")
      .append(superArgs).append("); this.d = d; }\n");
    for (Method m : abstracts.values()) {
      Class<?>[] ps = m.getParameterTypes();
      StringBuilder params = new StringBuilder(), argList = new StringBuilder();
      for (int k = 0; k < ps.length; k++) {
        if (k > 0) { params.append(", "); argList.append(", "); }
        params.append(typeName(ps[k])).append(" a").append(k);
        argList.append("a").append(k);
      }
      String vis = Modifier.isPublic(m.getModifiers()) ? "public" : "protected";
      Class<?> r = m.getReturnType();
      String call = "d.call(\"" + simple + "." + m.getName() + "\", new Object[]{" + argList + "})";
      sb.append("  @Override ").append(vis).append(" ").append(typeName(r)).append(" ").append(m.getName())
        .append("(").append(params).append(")");
      if (m.getExceptionTypes().length > 0) {
        sb.append(" throws ");
        for (int k = 0; k < m.getExceptionTypes().length; k++) { if (k > 0) sb.append(", "); sb.append(typeName(m.getExceptionTypes()[k])); }
      }
      if (r == void.class) sb.append(" { ").append(call).append("; }\n");
      else sb.append(" { return (").append(boxed(r)).append(") ").append(call).append("; }\n");
    }
    sb.append("}\n");
    java.nio.file.Files.writeString(java.nio.file.Path.of(outDir, "Host_" + simple + ".java"), sb.toString());
    System.out.println("generated Host_" + simple + " with " + abstracts.size() + " abstract methods");
  }
}
