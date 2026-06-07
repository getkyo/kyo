package kyo.test.runner

/** Minimal concrete [[kyo.test.internal.TestBase]] subclass used as a fixture in [[kyo.test.runner.internal.SuiteDiscoveryTest]].
  *
  * This class extends [[kyo.test.internal.TestBase]] with an empty body, making it safe to discover via the service-loader mechanism. It is
  * NOT intended to be run as a real test suite.
  */
class SuiteDiscoveryFixture extends kyo.test.internal.TestBase[Any]:
    // empty body: no leaves registered
end SuiteDiscoveryFixture
