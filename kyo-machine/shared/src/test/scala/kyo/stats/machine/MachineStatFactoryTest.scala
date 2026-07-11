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

        "starts exactly one sampler on the first winning call" in {
            MachineStatFactory.resetForTest()
            val started = MachineStatFactory.triggerStart(emptyReader)
            assert(started)
            assert(MachineStatFactory.hasStarted)
        }

        "a second triggerStart call after the CAS fired does not start a second sampler" in {
            MachineStatFactory.resetForTest()
            val first  = MachineStatFactory.triggerStart(emptyReader)
            val second = MachineStatFactory.triggerStart(emptyReader)
            assert(first)
            assert(!second)
            assert(MachineStatFactory.hasStarted)
        }

        "opt-out env KYO_MACHINE_DISABLED=true suppresses the start through the injectable reader" in {
            MachineStatFactory.resetForTest()
            val reader  = readerWithEnv("KYO_MACHINE_DISABLED", "true")
            val started = MachineStatFactory.triggerStart(reader)
            assert(!started)
            assert(!MachineStatFactory.hasStarted)
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
            end try
        }

        "an unparseable opt-out value enables the sampler (graceful default), never a thrown registration" in {
            MachineStatFactory.resetForTest()
            val reader  = readerWithEnv("KYO_MACHINE_DISABLED", "yes")
            val started = MachineStatFactory.triggerStart(reader)
            assert(started)
            assert(MachineStatFactory.hasStarted)
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
    // module does not declare. Referencing kyo.Stat.kyoScope forces object Stat's
    // class initializer (once per test-runner fork; a no-op if already forced by an
    // earlier test), which runs the eager scan that constructs the classpath-present
    // MachineStatFactory and starts the sampler, with no traceSpan call anywhere in
    // this driver: trace-independent auto-load reaches the factory through kyo.Stat
    // alone.
    "the eager Stat scan reaches the factory from a metrics-only driver".onlyJvm in {
        val _ = Stat.kyoScope
        assert(MachineStatFactory.hasStarted)
    }

end MachineStatFactoryTest
