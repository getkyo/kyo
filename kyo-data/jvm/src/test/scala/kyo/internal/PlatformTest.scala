package kyo.internal

class PlatformTest extends kyo.test.Test[Any]:

    "isBsdName" - {
        "classifies every fixed os.name string" in {
            assert(Platform.isBsdName("freebsd"))
            assert(Platform.isBsdName("openbsd"))
            assert(Platform.isBsdName("netbsd"))
            assert(!Platform.isBsdName("mac os x"))
            assert(!Platform.isBsdName("darwin"))
            assert(!Platform.isBsdName("linux"))
            assert(!Platform.isBsdName("windows 11"))
            assert(!Platform.isBsdName("sunos"))
            assert(!Platform.isBsdName("dragonfly"))
            assert(!Platform.isBsdName(""))
            assert(!Platform.isBsdName("unknown"))
            assert(!Platform.isBsdName("FreeBSD"))
        }
    }

    "isMacName" - {
        "classifies every fixed os.name string" in {
            assert(!Platform.isMacName("freebsd"))
            assert(!Platform.isMacName("openbsd"))
            assert(!Platform.isMacName("netbsd"))
            assert(Platform.isMacName("mac os x"))
            assert(Platform.isMacName("darwin"))
            assert(!Platform.isMacName("linux"))
            assert(!Platform.isMacName("windows 11"))
            assert(!Platform.isMacName("sunos"))
            assert(!Platform.isMacName("dragonfly"))
            assert(!Platform.isMacName(""))
            assert(!Platform.isMacName("unknown"))
            assert(!Platform.isMacName("FreeBSD"))
        }
    }

    "isMacOrBsd" - {
        "matches the pre-move truth table on the running host" in {
            val n = java.lang.System.getProperty("os.name", "").toLowerCase
            assert(Platform.isMacOrBsd == (n.contains("mac") || n.contains("bsd") || n.contains("darwin")))
            assert(Platform.isMac == Platform.isMacName(n))
            assert(Platform.isBsd == Platform.isBsdName(n))
        }
    }

end PlatformTest
