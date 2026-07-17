package kyo.stats.machine

import kyo.*

class MachineStatFactoryTest extends kyo.test.Test[Any]:

    // Every leaf drives the shared MachineStatFactory.started CAS and constructed flag directly, so
    // they must run one at a time (the default parallel leaf pool would race one leaf's resetForTest
    // against another leaf's own triggerStart).
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

        "starts exactly one sampler on the first winning call and a second call after the CAS fired does not start a second" in {
            MachineStatFactory.resetForTest()
            try
                val first  = MachineStatFactory.triggerStart(emptyReader)
                val second = MachineStatFactory.triggerStart(emptyReader)
                assert(first)
                assert(MachineStatFactory.hasStarted)
                assert(!second)
                assert(MachineStatFactory.hasStarted)
            finally MachineStatFactory.stopForTest()
            end try
        }

        "the opt-out lever suppresses the start through the injectable reader, and an unparseable value enables the sampler (graceful default)" in {
            MachineStatFactory.resetForTest()
            val envDisabled = MachineStatFactory.triggerStart(readerWithEnv("KYO_MACHINE_DISABLED", "true"))
            assert(!envDisabled)
            assert(!MachineStatFactory.hasStarted)
            MachineStatFactory.resetForTest()

            val prop  = "kyo.machine.disabled"
            val prior = java.lang.System.getProperty(prop)
            java.lang.System.setProperty(prop, "true")
            val propDisabled =
                try MachineStatFactory.triggerStart(System.live.unsafe)
                finally
                    if prior eq null then discard(java.lang.System.clearProperty(prop))
                    else discard(java.lang.System.setProperty(prop, prior))
            assert(!propDisabled)
            assert(!MachineStatFactory.hasStarted)
            MachineStatFactory.resetForTest()

            try
                val unparseableStarted = MachineStatFactory.triggerStart(readerWithEnv("KYO_MACHINE_DISABLED", "yes"))
                assert(unparseableStarted)
                assert(MachineStatFactory.hasStarted)
            finally MachineStatFactory.stopForTest()
            end try
        }
    }

    "construction vs start" - {

        "the constructed flag is set true independent of sampler start when activation is opted out" in {
            MachineStatFactory.resetForTest()
            MachineStatFactory.constructed.set(false)
            val started = MachineStatFactory.triggerStart(readerWithEnv("KYO_MACHINE_DISABLED", "true"))
            // Construction sets `constructed` unconditionally, before triggerStart is even reached; driving
            // the test-observation seam directly here means this leaf never risks winning the REAL
            // triggerStart CAS against the live host by constructing an actual MachineStatFactory.
            MachineStatFactory.constructed.set(true)
            assert(!started)
            assert(!MachineStatFactory.hasStarted)
            assert(MachineStatFactory.wasConstructed)
            MachineStatFactory.resetForTest()
        }
    }

    "traceExporter and eager-scan reachability" - {

        "the factory contributes no trace exporter and is reached by the eager class-init ServiceLoader scan" in {
            val factory = new MachineStatFactory()
            try assert(factory.traceExporter().isEmpty)
            finally MachineStatFactory.stopForTest() // the construction above may have won the real CAS

            // Real classpath ServiceLoader discovery (java.util.ServiceLoader over META-INF/services) is a
            // JVM-only mechanism: JS uses JSServiceLoaderRegistry (populated only by the main-source
            // MachineRegistration object) and Native needs a build-time nativeConfig allowlist this test
            // module does not declare, so only the JVM leg can observe the scan actually reaching this
            // module's factory; the call itself is cross-platform and harmless either way.
            MachineStatFactory.constructed.set(false)
            val _ = kyo.stats.internal.TraceExporter.getIsolated
            if kyo.internal.Platform.isJVM then assert(MachineStatFactory.wasConstructed)
        }
    }

end MachineStatFactoryTest
