package core

class RunControlSuite extends org.scalatest.funsuite.AnyFunSuite {
  test("waits for cancellation") {
    println(s"test runner ready: ${ProcessHandle.current().pid()}")
    // Long enough for the client to cancel; a broken cancellation must not leave this JVM running indefinitely.
    Thread.sleep(60000)
  }
}
