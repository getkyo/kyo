package kyo.stats.machine

import kyo.*
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.TraceExporter

/** Service-loader factory whose construction starts the host-metrics sampler on classpath presence.
  *
  * Discovered on JVM/Native via `META-INF/services/kyo.stats.internal.ExporterFactory` (forced eagerly by
  * `kyo.Stat`'s class-init scan) and on JS/Wasm via `MachineRegistration`. Construction reads the opt-out
  * once and, unless suppressed, starts exactly one sampler (CAS-gated). It contributes no trace exporter
  * (`traceExporter()` returns `None`): the SPI seam is used only as an on-classpath start trigger.
  *
  * Scala Native build precondition: Scala Native discovers `ServiceLoader` providers only from a build-time
  * allowlist, so a downstream Native build must register this factory or dead-code elimination drops the
  * unreferenced provider and sampling never starts. Add it to `nativeConfig`:
  * {{{
  * nativeConfig ~= { _.withServiceProviders(Map(
  *     "kyo.stats.internal.ExporterFactory" -> Seq("kyo.stats.machine.MachineStatFactory")
  * )) }
  * }}}
  * JVM and JS need no such step (JVM reads `META-INF/services`; JS registers via `MachineRegistration`).
  */
private[kyo] class MachineStatFactory extends ExporterFactory:
    import AllowUnsafe.embrace.danger
    val _                                                                  = MachineStatFactory.triggerStart(System.live.unsafe)
    override def traceExporter()(using AllowUnsafe): Option[TraceExporter] = None
end MachineStatFactory

private[kyo] object MachineStatFactory:

    import AllowUnsafe.embrace.danger

    private val started = AtomicBoolean.Unsafe.init(false)

    /** Names of the opt-out env var and system property. */
    private val disableEnv  = "KYO_MACHINE_DISABLED"
    private val disableProp = "kyo.machine.disabled"

    /** Reads the opt-out once from the given environment reader. Set to a truthy value suppresses the sampler;
      * unset or unparseable enables (graceful default). Takes a `System.Unsafe` so the read source is
      * injectable: production passes `System.live.unsafe`; a test passes a staged reader with a chosen env/prop
      * map, since `System.let` swaps `System.local` and cannot reach `System.live`, and a real env var cannot be
      * set inside a running JVM. No effect is needed at the read (the SPI factory constructor has no Frame, the
      * established kyo-stats-otlp pattern).
      */
    private def disabled(env: System.Unsafe)(using AllowUnsafe): Boolean =
        val raw = env.env(disableEnv).orElse(env.property(disableProp))
        raw match
            case Present(v) => v.trim.equalsIgnoreCase("true")
            case Absent     => false
    end disabled

    /** Starts the sampler at most once across all factory constructions (CAS-gated), unless the given reader
      * reports opt-out. The sampler runs in a detached fiber (`Fiber.initUnscoped`) so it outlives the
      * triggering call's own scope; the tick loop inside `MachineSampler.run` keeps that fiber's own scope
      * open until interrupt. Returns true iff this call won the CAS and started the sampler (so a test can
      * distinguish a start from an opt-out suppression from a CAS-lost suppression).
      */
    def triggerStart(env: System.Unsafe)(using AllowUnsafe): Boolean =
        if !disabled(env) && started.compareAndSet(false, true) then
            given Frame = Frame.internal
            val _ = Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Scope.run {
                        MachineSampler.run
                    }
                }
            }
            true
        else false

    /** Test-only seam: whether the one-shot start CAS has already fired. */
    private[machine] def hasStarted(using AllowUnsafe): Boolean = started.get()

    /** Test-only seam: resets the one-shot start CAS so an ordered sequence of factory-start scenarios each
      * starts from a known false state. Never called by production code (no reset path exists at runtime; the
      * sampler is a process-lifetime singleton). Present only so leaves 4-8 are stageable and order-independent.
      */
    private[machine] def resetForTest()(using AllowUnsafe): Unit = started.set(false)

end MachineStatFactory
