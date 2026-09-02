package kyo.stats.machine

import kyo.*

class MachineStatFactoryTest extends kyo.test.Test[Any]:

    // Every leaf drives the shared MachineStatFactory.started CAS and constructed flag directly, so
    // they must run one at a time (the default parallel leaf pool would race one leaf's resetForTest
    // against another leaf's own triggerStart).
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

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
                val first  = MachineStatFactory.triggerStart(disabled = false)
                val second = MachineStatFactory.triggerStart(disabled = false)
                assert(first)
                assert(MachineStatFactory.hasStarted)
                assert(!second)
                assert(MachineStatFactory.hasStarted)
            finally MachineStatFactory.stopForTest()
            end try
        }

        "the opt-out suppresses the start, and the flag supplies the default when the caller names nothing" in {
            MachineStatFactory.resetForTest()
            assert(!MachineStatFactory.triggerStart(disabled = true))
            assert(!MachineStatFactory.hasStarted)
            MachineStatFactory.resetForTest()

            // The no-argument form is what production calls: it reads the kyo.machine.disabled flag, which
            // resolves once at class load and is false unless the host set it.
            try
                assert(MachineStatFactory.triggerStart() == !kyo.machine.disabled())
            finally MachineStatFactory.stopForTest()
            end try
        }
    }

end MachineStatFactoryTest
