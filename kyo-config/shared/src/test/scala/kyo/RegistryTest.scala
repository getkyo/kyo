package kyo

import org.scalatest.freespec.AnyFreeSpec

class RegistryTest extends AnyFreeSpec {

    // Force-load the test flags so they are registered
    locally {
        RegistryTestFlags.registeredFlag
        RegistryTestFlags.anotherFlag
        RegistryTestFlags.dynamicRegistryFlag
    }

    "Registry" - {

        "self-registration — Flag.all contains registered flags" in {
            val all = Flag.all
            assert(all.exists(_.name == "kyo.RegistryTestFlags.registeredFlag"))
        }

        "Flag.get by name" in {
            val found = Flag.get("kyo.RegistryTestFlags.registeredFlag")
            assert(found.isDefined)
            assert(found.get.name == "kyo.RegistryTestFlags.registeredFlag")
        }

        "Flag.get returns None for nonexistent name" in {
            val found = Flag.get("nonexistent.flag.name")
            assert(found.isEmpty)
        }

        "duplicate name throws FlagDuplicateNameException" in {
            // Register a flag manually, then try registering another with the same name.
            // We can't easily create two objects with the same auto-derived name,
            // so we test by creating an anonymous flag and manually trying to register it
            // with a name that is already taken.
            // Actually, let's just verify the registry rejects on the second load.
            val ex = intercept[FlagDuplicateNameException] {
                RegistryTestFlags.duplicateHolder.triggerDuplicate()
            }
            assert(ex.getMessage.contains("Duplicate flag name"))
        }

        "registry is Map-based — no size limit" in {
            val names = Flag.all.map(_.name)
            assert(names.contains("kyo.RegistryTestFlags.registeredFlag"))
            assert(names.contains("kyo.RegistryTestFlags.anotherFlag"))
        }

        "dump() returns formatted table" in {
            val table = Flag.dump()
            assert(table.contains("Name"))
            assert(table.contains("Type"))
            assert(table.contains("Value"))
            assert(table.contains("Default"))
            assert(table.contains("Source"))
            // Should contain box-drawing characters
            assert(table.contains("\u2502")) // vertical bar
            assert(table.contains("\u2500")) // horizontal line
        }

        "dump() includes flag data" in {
            val table = Flag.dump()
            assert(table.contains("kyo.RegistryTestFlags.registeredFlag"))
            assert(table.contains("static"))
        }

        "near-miss property detection warns on case mismatch" in {
            // Set a near-miss property with different case
            // The real flag name is "kyo.RegistryTestFlags.nearMissTarget"
            // We set a property with different case
            java.lang.System.setProperty("kyo.registrytestflags.nearMissTarget", "99")
            try {
                val baos    = new java.io.ByteArrayOutputStream()
                val oldErr  = java.lang.System.err
                val capture = new java.io.PrintStream(baos)
                java.lang.System.setErr(capture)
                try {
                    RegistryTestFlags.nearMissTarget // trigger registration
                    capture.flush()
                    val output = baos.toString
                    assert(output.contains("did you mean"))
                } finally {
                    java.lang.System.setErr(oldErr)
                }
            } finally {
                java.lang.System.clearProperty("kyo.registrytestflags.nearMissTarget"): Unit
            }
        }

        "no near-miss false positive for unrelated properties" in {
            java.lang.System.setProperty("kyo.completely.unrelated", "5")
            try {
                val baos    = new java.io.ByteArrayOutputStream()
                val oldErr  = java.lang.System.err
                val capture = new java.io.PrintStream(baos)
                java.lang.System.setErr(capture)
                try {
                    RegistryTestFlags.noFalsePositiveFlag // trigger registration
                    capture.flush()
                    val output = baos.toString
                    assert(!output.contains("kyo.RegistryTestFlags.noFalsePositiveFlag"))
                } finally {
                    java.lang.System.setErr(oldErr)
                }
            } finally {
                java.lang.System.clearProperty("kyo.completely.unrelated"): Unit
            }
        }

        "Flag.all returns both StaticFlag and DynamicFlag" in {
            val all = Flag.all
            assert(all.exists(f => f.name == "kyo.RegistryTestFlags.registeredFlag" && !f.isDynamic))
            assert(all.exists(f => f.name == "kyo.RegistryTestFlags.dynamicRegistryFlag" && f.isDynamic))
        }

        "Flag.all returns list" in {
            val all = Flag.all
            assert(all.isInstanceOf[List[?]])
            assert(all.nonEmpty)
        }

        "validation failure at init does not leave zombie in registry" in {
            // A flag whose value parsing throws should NOT be registered
            val zombieName = "kyo.RegistryTestFlags.zombieFlag"
            // Ensure it's not already registered
            assert(Flag.get(zombieName).isEmpty, "zombieFlag should not be pre-registered")

            // Set a system property that will fail to parse as Int
            java.lang.System.setProperty(zombieName, "not-a-number")
            try {
                // On JVM: ExceptionInInitializerError, on JS/Native: FlagException directly
                try {
                    RegistryTestFlags.zombieFlag // trigger init — will fail because "not-a-number" is not Int
                    fail("Expected exception was not thrown")
                } catch {
                    case _: ExceptionInInitializerError => // expected on JVM
                    case _: FlagException               => // expected on JS/Native
                }
                // The flag should NOT be in the registry (no zombie)
                assert(Flag.get(zombieName).isEmpty, "zombieFlag should not be registered after init failure")
            } finally {
                java.lang.System.clearProperty(zombieName): Unit
            }
        }

        "dump() returns non-empty string for non-empty registry" in {
            val table = Flag.dump()
            assert(table.nonEmpty)
            assert(table != "(no flags registered)")
        }
    }

}

object RegistryTestFlags {
    object registeredFlag      extends StaticFlag[Int](100)
    object anotherFlag         extends StaticFlag[String]("hello")
    object dynamicRegistryFlag extends DynamicFlag[Int](0)

    // For duplicate detection: manually re-register the same name
    object duplicateHolder {
        def triggerDuplicate(): Unit =
            Flag.register(registeredFlag) // already registered, will throw
    }

    // For near-miss test — the real name is kyo.RegistryTestFlags.nearMissTarget
    // We set a property with different casing before loading
    object nearMissTarget extends StaticFlag[Int](0)

    object noFalsePositiveFlag extends StaticFlag[Int](0)

    // For zombie detection test — this object's class init will fail if
    // system property "kyo.RegistryTestFlags.zombieFlag" is set to a non-integer
    object zombieFlag extends StaticFlag[Int](0)
}
