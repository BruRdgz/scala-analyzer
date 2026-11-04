package core

class ExampleFeatureSpec extends org.scalatest.featurespec.AnyFeatureSpec {
  Feature("a calculator") {
    Scenario("adds numbers") { assert(1 + 1 == 2) }
  }
}
