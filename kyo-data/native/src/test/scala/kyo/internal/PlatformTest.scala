package kyo.internal

class PlatformTest extends kyo.test.Test[Any]:

    "OS family" - {
        "exactly one OS family resolves at link time" in {
            val trueCount = List(Platform.isMacOrBsd, Platform.isLinux, Platform.isWindows).count(identity)
            assert(trueCount <= 1) // mutual exclusivity
            assert(trueCount >= 1) // exhaustiveness
        }
    }

    "isBsd" - {
        "excludes isMac on the linked target" in {
            assert(!(Platform.isBsd && Platform.isMac))
        }
    }

    "isMacOrBsd" - {
        "stays composed from isMac and isBsd" in {
            // definition pin, not verification: true by construction, cannot fail against the code as written
            assert(Platform.isMacOrBsd == (Platform.isMac || Platform.isBsd))
        }
    }

end PlatformTest
