package scalaanalyzer.build

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, JvmEnvironmentItem}

import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}

import scala.collection.mutable.ArrayBuffer

class TestRunTest extends munit.FunSuite {
  test("decodes TeamCity values without confusing escaped delimiters with attribute boundaries") {
    val (kind, fields) = TestRun.message("##teamcity[testFailed name='it |'works|' |[x|]' message='a||b|nc|rd' details='stack'error = 'true']")
    assertEquals(kind, "testFailed")
    assertEquals(fields("name"), "it 'works' [x]")
    assertEquals(fields("message"), "a|b\nc\rd")
    intercept[IllegalArgumentException](TestRun.message("##teamcity[testFailed name='unfinished]"))
    val stack = "at Suite.test(Suite.scala:1)|n" * 20000
    assertEquals(TestRun.message(s"##teamcity[testFailed name='long' details='$stack']")._2("details").length, stack.length - 20000)
  }

  test("counts terminal outcomes once across runner event conventions and preserves readable failures") {
    val log = ArrayBuffer.empty[String]
    val report = new TestRun.Report(log += _)
    Seq(
      "##teamcity[testStarted name='same' nodeId='1']",
      "##teamcity[testFailed name='same' nodeId='1' message='assertion failed' details='first|nsecond']",
      "##teamcity[testFinished name='same' nodeId='1']",
      "##teamcity[testStarted name='same' nodeId='2']",
      "##teamcity[testFinished name='same' nodeId='2']",
      "##teamcity[testIgnored name='skipped' nodeId='3' message='pending']",
      "##teamcity[testFinished name='skipped' nodeId='3']",
      "ordinary output",
    ).foreach(report.line)
    assertEquals(report.result("example.Suite", 0),
      TestRun.Result("Suite: 1 passed, 1 failed, 1 ignored", failed = true, ignored = true))
    assertEquals(log.toSeq, Seq("same: failed: assertion failed", "first", "second", "same: passed", "skipped: ignored: pending", "ordinary output"))
  }

  test("an empty, interrupted or aborted run is never a passing zero-test result") {
    val messages = Seq(
      Seq.empty,
      Seq("##teamcity[testStarted name='unfinished']"),
      Seq("##teamcity[testFinished name='pass']", "##teamcity[message text='aborted' status='ERROR']"),
    )
    messages.foreach { lines =>
      val report = new TestRun.Report(_ => ())
      lines.foreach(report.line)
      assert(report.result("Suite", 0).failed)
      assert(!report.result("Suite", 0).summary.contains("0 passed"))
    }
    val report = new TestRun.Report(_ => ())
    report.line("##teamcity[testFinished name='pass']")
    assert(report.result("Suite", 1).failed)
  }

  test("compilation and launch failures produce error summaries rather than zero tests") {
    val missingJava = Files.createTempDirectory("scala-analyzer-missing-java")
    val environment = new JvmEnvironmentItem(new BuildTargetIdentifier("target"), java.util.List.of(), java.util.List.of(),
      Path.of(".").toAbsolutePath.toString, java.util.Map.of())
    val cases: Seq[() => TestRun.Environment] = Seq(
      () => throw new IllegalStateException("compilation failed"),
      () => TestRun.Environment(missingJava.resolve("java"), environment),
    )
    val runner = new TestRun
    try cases.foreach { prepare =>
      val summaries = new LinkedBlockingQueue[TestRun.Result]
      runner.start(TestRun.Selection("target", "scalatest", "Suite", None), prepare, _ => (), summaries.add(_))
        .get(10, TimeUnit.SECONDS)
      val summary = summaries.poll()
      assert(summary != null)
      assert(summary.failed)
      assert(summary.summary.contains("test run failed"))
      assert(!summary.summary.contains("0 passed"))
    } finally {
      runner.close()
      Files.delete(missingJava)
    }
  }

  test("reserves a run during BSP preparation and cancellation prevents launching afterwards") {
    val runner = new TestRun
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val prepared = new CountDownLatch(1)
    val selection = TestRun.Selection("target", "scalatest", "Suite", None)
    def prepare(): TestRun.Environment = {
      entered.countDown()
      val released = release.await(10, TimeUnit.SECONDS)
      if (!released) throw new IllegalStateException("preparation was not released")
      prepared.countDown()
      throw new IllegalStateException("preparation ended after cancellation")
    }
    try {
      val answer = runner.start(selection, () => prepare(), _ => fail("unexpected log"), _ => fail("unexpected summary"))
      val started = entered.await(10, TimeUnit.SECONDS)
      assert(started)
      intercept[IllegalStateException](runner.start(selection, () => prepare(), _ => (), _ => ()))
      assert(answer.cancel(true))
      release.countDown()
      val ended = prepared.await(10, TimeUnit.SECONDS)
      assert(ended)
    } finally {
      release.countDown()
      runner.close()
    }
  }
}
