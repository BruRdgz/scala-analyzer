package core

class ExampleFunSuite extends org.scalatest.funsuite.AnyFunSuite {
  test("adds numbers") { assert(1 + 1 == 2) }
  test("escaped \"name\"") { assert(true) }
}

abstract class AbstractSuite extends org.scalatest.funsuite.AnyFunSuite {
  test("abstract test") { assert(true) }
}

trait SuiteTrait extends org.scalatest.funsuite.AnyFunSuite
object SuiteObject extends org.scalatest.funsuite.AnyFunSuite
class OrdinaryClass
