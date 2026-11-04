package scalaanalyzer.munit;

import java.util.ArrayList;
import java.util.List;

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * Runs an MUnit suite, or some of its tests, through the JUnit 4 that MUnit depends on, and reports them as TeamCity
 * service messages like the Scala plugin's runners. The IDE runs MUnit through IntelliJ's JUnit runtime instead.
 *
 * <p>Takes the plugin runners' arguments: {@code -s <suite> [-testName <name>]...}; it ignores the others.
 */
public final class MUnitTestRunner {
  private MUnitTestRunner() {}

  public static void main(String[] args) throws ClassNotFoundException {
    String suite = null;
    List<String> names = new ArrayList<>();
    for (int i = 0; i + 1 < args.length; i++) {
      if (args[i].equals("-s")) suite = args[++i];
      else if (args[i].equals("-testName")) names.add(args[++i]);
    }
    if (suite == null) throw new IllegalArgumentException("missing -s <suite>");

    Request request = Request.aClass(Class.forName(suite));
    if (!names.isEmpty()) request = request.filterWith(new Named(names));
    JUnitCore junit = new JUnitCore();
    junit.addListener(new Reporter());
    junit.run(request);
  }

  /** Keeps the tests with the given names: MUnit names a test's description method after the test. */
  private static final class Named extends Filter {
    private final List<String> names;

    Named(List<String> names) {
      this.names = names;
    }

    @Override
    public boolean shouldRun(Description description) {
      return description.getMethodName() == null || names.contains(description.getMethodName());
    }

    @Override
    public String describe() {
      return "tests named " + names;
    }
  }

  /** Reports JUnit's events with the service messages scala-analyzer reads from the plugin's runners. */
  private static final class Reporter extends RunListener {
    @Override
    public void testStarted(Description description) {
      emit("testStarted", "name", description.getMethodName());
    }

    @Override
    public void testFinished(Description description) {
      emit("testFinished", "name", description.getMethodName());
    }

    @Override
    public void testIgnored(Description description) {
      emit("testIgnored", "name", description.getMethodName());
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
      emit("testIgnored", "name", failure.getDescription().getMethodName(), "message", failure.getMessage());
    }

    /** A failure without a test is the suite's: it could not be created, or no test matched the names. */
    @Override
    public void testFailure(Failure failure) {
      String test = failure.getDescription().getMethodName();
      if (test == null) emit("message", "status", "ERROR", "text", failure.getMessage(), "errorDetails", failure.getTrace());
      else emit("testFailed", "name", test, "message", failure.getMessage(), "details", failure.getTrace());
    }
  }

  private static void emit(String kind, String... attributes) {
    StringBuilder message = new StringBuilder("##teamcity[").append(kind);
    for (int i = 0; i < attributes.length; i += 2) {
      message.append(' ').append(attributes[i]).append("='").append(escape(attributes[i + 1])).append('\'');
    }
    System.out.println(message.append(']'));
  }

  /** As the plugin's {@code TestRunnerUtil.escapeString}. */
  private static String escape(String value) {
    if (value == null) return "";
    return value.replace("|", "||").replace("'", "|'").replace("\n", "|n").replace("\r", "|r")
      .replace("[", "|[").replace("]", "|]");
  }
}
