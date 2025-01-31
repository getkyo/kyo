package kyo.internal

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class EnvironmentTest extends AnyFreeSpec with Matchers:

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
