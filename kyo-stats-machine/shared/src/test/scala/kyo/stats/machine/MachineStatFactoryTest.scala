package kyo.stats.machine

import kyo.*

class MachineStatFactoryTest extends kyo.test.Test[Any]:

    // The triggerStart/resetForTest leaves drive the shared MachineStatFactory.started CAS
    // directly, so they must run one at a time within this suite (the default parallel leaf
    // pool would race one leaf's resetForTest against another leaf's own triggerStart).
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private val emptyReader: System.Unsafe =
        new System.Unsafe:
            def env(name: String)(using AllowUnsafe): Maybe[String]      = Absent
            def property(name: String)(using AllowUnsafe): Maybe[String] = Absent
            def lineSeparator()(using AllowUnsafe): String               = "\n"
            def userName()(using AllowUnsafe): String                    = "test"
            def operatingSystem()(using AllowUnsafe): System.OS          = System.OS.Unknown
            def architecture()(using AllowUnsafe): System.Arch           = System.Arch.Unknown
            def availableProcessors()(using AllowUnsafe): Int            = 1

    private def readerWithEnv(name: String, value: String): System.Unsafe =
        new System.Unsafe:
            def env(n: String)(using AllowUnsafe): Maybe[String]      = if n == name then Present(value) else Absent
            def property(n: String)(using AllowUnsafe): Maybe[String] = Absent
            def lineSeparator()(using AllowUnsafe): String            = "\n"
            def userName()(using AllowUnsafe): String                 = "test"
            def operatingSystem()(using AllowUnsafe): System.OS       = System.OS.Unknown
            def architecture()(using AllowUnsafe): System.Arch        = System.Arch.Unknown
            def availableProcessors()(using AllowUnsafe): Int         = 1

    "triggerStart" - {

        // Every leaf that actually starts a sampler (a winning triggerStart) stops it in a finally, so no
        // leaf leaves a live detached sampler loop reading /proc behind it (stopForTest interrupts the
        // started fiber and clears the CAS).

        "starts exactly one sampler on the first winning call" in {
            MachineStatFactory.resetForTest()
            try
                val started = MachineStatFactory.triggerStart(emptyReader)
                assert(started)
                assert(MachineStatFactory.hasStarted)
            finally MachineStatFactory.stopForTest()
            end try
        }

        "a second triggerStart call after the CAS fired does not start a second sampler" in {
            MachineStatFactory.resetForTest()
            try
                val first  = MachineStatFactory.triggerStart(emptyReader)
                val second = MachineStatFactory.triggerStart(emptyReader)
                assert(first)
                assert(!second)
                assert(MachineStatFactory.hasStarted)
            finally MachineStatFactory.stopForTest()
            end try
        }

        "opt-out env KYO_MACHINE_DISABLED=true suppresses the start through the injectable reader" in {
            MachineStatFactory.resetForTest()
            val reader  = readerWithEnv("KYO_MACHINE_DISABLED", "true")
            val started = MachineStatFactory.triggerStart(reader)
            assert(!started)
            assert(!MachineStatFactory.hasStarted)
            // No sampler started, so nothing to stop; resetForTest suffices for the next leaf.
            MachineStatFactory.resetForTest()
        }

        "opt-out property kyo.machine.disabled=true also suppresses the start (live-path system property)" in {
            MachineStatFactory.resetForTest()
            val prop  = "kyo.machine.disabled"
            val prior = java.lang.System.getProperty(prop)
            java.lang.System.setProperty(prop, "true")
            try
                val started = MachineStatFactory.triggerStart(System.live.unsafe)
                assert(!started)
                assert(!MachineStatFactory.hasStarted)
            finally
                if prior eq null then discard(java.lang.System.clearProperty(prop))
                else discard(java.lang.System.setProperty(prop, prior))
                MachineStatFactory.resetForTest()
            end try
        }

        "an unparseable opt-out value enables the sampler (graceful default), never a thrown registration" in {
            MachineStatFactory.resetForTest()
            try
                val reader  = readerWithEnv("KYO_MACHINE_DISABLED", "yes")
                val started = MachineStatFactory.triggerStart(reader)
                assert(started)
                assert(MachineStatFactory.hasStarted)
            finally MachineStatFactory.stopForTest()
            end try
        }
    }

    "traceExporter" - {

        "the factory contributes no trace exporter" in {
            val factory = new MachineStatFactory()
            assert(factory.traceExporter().isEmpty)
        }
    }

    // Real classpath ServiceLoader discovery (java.util.ServiceLoader over
    // META-INF/services) is a JVM-only mechanism: JS uses JSServiceLoaderRegistry
    // (populated only by the @JSExportTopLevel MachineRegistration object, a
    // main-source construct outside a test's reach), and Native's ServiceLoader
    // requires a build-time nativeConfig.withServiceProviders allowlist this test
    // module does not declare.
    //
    // The eager scan runs the per-factory-isolated discovery TraceExporter.getIsolated (the exact
    // mechanism object Stat's class-init forces). Resetting the construction marker immediately before
    // driving that same discovery, then asserting the marker flipped, proves the module's factory is
    // reachable through the eager scan's mechanism without depending on class-init residue from an earlier
    // suite: a factory that were not registered in META-INF/services would leave the marker false.
    // (object Stat forcing the scan at class-init, trace-independently, is pinned separately by kyo-core's
    // StatTest; this leaf owns the module-side "the factory is discoverable" half.)
    "the module factory is reached by the eager scan's isolated ServiceLoader discovery".onlyJvm in {
        MachineStatFactory.constructed.set(false)
        val _ = kyo.stats.internal.TraceExporter.getIsolated
        assert(MachineStatFactory.wasConstructed)
    }

end MachineStatFactoryTest
