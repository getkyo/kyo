package kyo.test

import kyo.*
// Assuming necessary Kyo types such as Env and that testConfigWith and TestAspect are available

trait TestConfig extends Serializable:
    def repeats: Int
    def retries: Int
    def samples: Int
    def shrinks: Int
    def checkAspect: TestAspect.CheckAspect = TestAspect.identity
end TestConfig

object TestConfig:
    val tag: Tag[TestConfig] = Tag[TestConfig]

    @deprecated("use TestV2", "2.1.8")
    final case class Test(repeats: Int, retries: Int, samples: Int, shrinks: Int) extends TestConfig

    final case class TestV2(
        repeats: Int,
        retries: Int,
        samples: Int,
        shrinks: Int,
        override val checkAspect: TestAspect.CheckAspect
    ) extends TestConfig

    // Default test configuration layer
    val default: Layer[TestConfig, Any] =
        live(100, 100, 200, 1000, TestAspect.identity)

    // Constructs a new TestConfig layer with default checkAspect
    def live(repeats: Int, retries: Int, samples: Int, shrinks: Int): Layer[TestConfig, Any] =
        Layer(TestV2(repeats, retries, samples, shrinks, TestAspect.identity))

    // Constructs a new TestConfig layer with an explicit checkAspect
    def live(repeats: Int, retries: Int, samples: Int, shrinks: Int, checkAspect: TestAspect.CheckAspect): Layer[TestConfig, Any] =
        Layer(TestV2(repeats, retries, samples, shrinks, checkAspect))

    // Retrieves the repeats setting from the environment
    def repeats(using trace: Trace): Int < Any =
        testConfigWith(testConfig => testConfig.repeats)

    // Retrieves the retries setting from the environment
    def retries(using trace: Trace): Int < Any =
        testConfigWith(testConfig => testConfig.retries)

    // Retrieves the samples setting from the environment
    def samples(using trace: Trace): Int < Any =
        testConfigWith(testConfig => testConfig.samples)

    // Retrieves the shrinks setting from the environment
    def shrinks(using trace: Trace): Int < Any =
        testConfigWith(testConfig => testConfig.shrinks)

    // Retrieves the checkAspect from the environment
    def checkAspect(using trace: Trace): TestAspect.CheckAspect < Any =
        testConfigWith(testConfig => testConfig.checkAspect)
end TestConfig
