package kyo.internal

import org.scalatest.freespec.AnyFreeSpec
import scala.jdk.CollectionConverters.*

/** JVM and Native resolution: the process's own environment and properties.
  *
  * Environment variables cannot be set from inside a running process, so the present-name case reads an ambient variable of the test process
  * and compares against what `java.lang.System.getenv` returns for it, rather than asserting a hardcoded value.
  */
class HostConfigTest extends AnyFreeSpec {

    "env" - {
        "returns exactly what java.lang.System.getenv returns for a name set in the process environment" in {
            val ambient = java.lang.System.getenv()
            assume(!ambient.isEmpty, "the test process has no environment variables to read")
            val name     = ambient.keySet().iterator().next()
            val expected = java.lang.System.getenv(name)
            assert(expected ne null)
            assert(HostConfig.env(name) == expected)
        }

        "returns null for a name that is not set" in {
            val name = "KYO_HOSTCONFIG_DEFINITELY_UNSET"
            assert(java.lang.System.getenv(name) eq null)
            assert(HostConfig.env(name) eq null)
        }

        "lists exactly the names of the process environment" in {
            assert(HostConfig.envNames.toSet == java.lang.System.getenv().keySet().asScala.toSet)
        }
    }

    "property" - {
        "reads a property set at run time and lists its name" in {
            java.lang.System.setProperty("kyo.hostconfigtest.property", "set")
            try {
                assert(HostConfig.property("kyo.hostconfigtest.property") == "set")
                assert(HostConfig.propertyNames.exists(_ == "kyo.hostconfigtest.property"))
            } finally {
                java.lang.System.clearProperty("kyo.hostconfigtest.property")
                ()
            }
        }

        "returns null for a name that is not set" in {
            assert(HostConfig.property("kyo.hostconfigtest.unset") eq null)
        }
    }

}
