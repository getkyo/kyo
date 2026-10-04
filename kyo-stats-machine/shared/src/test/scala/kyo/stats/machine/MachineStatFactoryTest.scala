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

    /** Interrupts a sampler this suite started and clears the start CAS, so no live sampler loop outlives the leaf. */
    private def stop(started: Maybe[Fiber.Unsafe[Unit, Any]]): Unit =
        started.foreach(fiber => discard(fiber.interrupt()))
        MachineStatFactory.resetForTest()

    "triggerStart" - {

        "starts exactly one sampler on the first winning call and a second call after the CAS fired does not start a second" in {
            MachineStatFactory.resetForTest()
            val first = MachineStatFactory.triggerStart(disabled = false)
            try
                val second = MachineStatFactory.triggerStart(disabled = false)
                assert(first.isDefined)
                assert(MachineStatFactory.hasStarted)
                assert(second.isEmpty)
                assert(MachineStatFactory.hasStarted)
            finally stop(first)
            end try
        }

        "the opt-out suppresses the start, and the flag supplies the default when the caller names nothing" in {
            MachineStatFactory.resetForTest()
            assert(MachineStatFactory.triggerStart(disabled = true).isEmpty)
            assert(!MachineStatFactory.hasStarted)
            MachineStatFactory.resetForTest()

            // The no-argument form is what production calls: it reads the kyo.machine.disabled flag, which
            // resolves once at class load and is false unless the host set it.
            val started = MachineStatFactory.triggerStart()
            try assert(started.isDefined == !kyo.machine.disabled())
            finally stop(started)
            end try
        }
    }

end MachineStatFactoryTest
