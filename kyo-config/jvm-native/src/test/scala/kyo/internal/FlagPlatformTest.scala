package kyo.internal

import org.scalatest.freespec.AnyFreeSpec

/** JVM and Native env resolution.
  *
  * Environment variables cannot be set from inside a running process, so the present-name case reads an
  * ambient variable of the test process and compares against what `java.lang.System.getenv` returns for it,
  * rather than asserting a hardcoded value.
  */
class FlagPlatformTest extends AnyFreeSpec {

    "env" - {
        "returns exactly what java.lang.System.getenv returns for a name set in the process environment" in {
            val ambient = java.lang.System.getenv()
            assume(!ambient.isEmpty, "the test process has no environment variables to read")
            val name     = ambient.keySet().iterator().next()
            val expected = java.lang.System.getenv(name)
            assert(expected ne null)
            assert(FlagPlatform.env(name) == expected)
        }

        "returns null for a name that is not set" in {
            val name = "KYO_FLAGPLATFORM_DEFINITELY_UNSET"
            assert(java.lang.System.getenv(name) eq null)
            assert(FlagPlatform.env(name) eq null)
        }
    }

}
