package kyo.internal

class DiagnosticsTest extends kyo.test.Test[Any]:

    // Diagnostics is a process-global singleton, and other suites may register/unregister concurrently in the same JVM. Every assertion
    // below looks up ITS OWN uniquely-named entry rather than asserting anything about the full list, and every registration is closed at
    // the end of the test so nothing leaks into a later test's view of the registry.
    def freshName(tag: String): String = s"DiagnosticsTest-$tag-${scala.util.Random.nextLong()}"

    "dumpAll" - {
        "includes a registered dumper's rendered snapshot under its name" in {
            val name = freshName("dump")
            val reg  = Diagnostics.register(name)(() => "the snapshot body")
            try assert(Diagnostics.dumpAll().contains(s"=== $name ===\nthe snapshot body"))
            finally reg.close()
        }

        "stops including a dumper once its registration is closed" in {
            val name = freshName("dump-closed")
            val reg  = Diagnostics.register(name)(() => "body")
            reg.close()
            assert(!Diagnostics.dumpAll().contains(name))
        }

        "contains a throwing dumper's failure inline instead of aborting the render" in {
            val name = freshName("dump-throws")
            val reg  = Diagnostics.register(name)(() => throw new RuntimeException("boom"))
            try assert(Diagnostics.dumpAll().contains(s"=== $name ===\ndump threw:"))
            finally reg.close()
        }
    }

    "probeAll" - {
        "reports the registered probe's current snapshot under its name" in {
            val name = freshName("probe")
            val reg  = Diagnostics.register(name)(() => "body", () => Diagnostics.Probe(closed = false, cycles = 3L, pending = true))
            try
                val found = Diagnostics.probeAll().toMap.get(name)
                assert(found == Some(Diagnostics.Probe(closed = false, cycles = 3L, pending = true)))
            finally reg.close()
            end try
        }

        "reports a closed no-op probe for a dumper registered without one" in {
            val name = freshName("probe-default")
            val reg  = Diagnostics.register(name)(() => "body")
            try
                val found = Diagnostics.probeAll().toMap.get(name)
                assert(found == Some(Diagnostics.Probe(closed = true, cycles = 0L, pending = false)))
            finally reg.close()
            end try
        }

        "drops a throwing probe rather than failing the whole scan" in {
            val okName  = freshName("probe-ok")
            val badName = freshName("probe-throws")
            val okReg   = Diagnostics.register(okName)(() => "body", () => Diagnostics.Probe(closed = false, cycles = 1L, pending = false))
            val badReg  = Diagnostics.register(badName)(() => "body", () => throw new RuntimeException("boom"))
            try
                val all = Diagnostics.probeAll().toMap
                assert(all.get(okName) == Some(Diagnostics.Probe(closed = false, cycles = 1L, pending = false)))
                assert(!all.contains(badName))
            finally
                okReg.close()
                badReg.close()
            end try
        }

        "stops reporting a probe once its registration is closed" in {
            val name = freshName("probe-closed")
            val reg  = Diagnostics.register(name)(() => "body", () => Diagnostics.Probe(closed = false, cycles = 1L, pending = true))
            reg.close()
            assert(!Diagnostics.probeAll().toMap.contains(name))
        }
    }
end DiagnosticsTest
