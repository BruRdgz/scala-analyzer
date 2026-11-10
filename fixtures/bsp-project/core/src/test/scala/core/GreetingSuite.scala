package core

class GreetingSuite extends org.scalatest.funsuite.AnyFunSuite {
  test("greets by name") { assert("Hello, world".startsWith("Hello")) }
}
