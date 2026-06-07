package kyo.internal

class EnvironmentTest extends kyo.test.Test[Any]:

    // Each leaf sets the process-global `kyo.development` system property; run sequentially so concurrent leaves
    // do not clobber each other's property value.
    override def config = super.config.sequential

    "infers as development" - {
        "if system property is enabled, which should be the case in this test execution" in {
            System.setProperty("kyo.development", "true")
            assert(Environment.inferIsDevelopment())
        }
    }

    "doesn't infer as development" - {
        "if system property is false" in {
            System.setProperty("kyo.development", "false")
            assert(!Environment.inferIsDevelopment())
        }
        "if system property is invalid" in {
            System.setProperty("kyo.development", "blah")
            assert(!Environment.inferIsDevelopment())
        }
    }
end EnvironmentTest
