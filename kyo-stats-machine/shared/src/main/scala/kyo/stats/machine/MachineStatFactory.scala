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
    // Unsafe: the factory is constructed by the stats service-loader at Stat class-init, which threads
    // no AllowUnsafe; starting the sampler here is the classpath-presence activation boundary.
    import AllowUnsafe.embrace.danger
    MachineStatFactory.constructed.set(true)
    val _                                                                  = MachineStatFactory.triggerStart()
    override def traceExporter()(using AllowUnsafe): Option[TraceExporter] = None
end MachineStatFactory

private[kyo] object MachineStatFactory:

    // Unsafe: the single-owner start flag and the injectable-System opt-out read run at
    // classpath-presence activation, off any effect context that could supply AllowUnsafe.
    import AllowUnsafe.embrace.danger

    private val started = AtomicBoolean.Unsafe.init(false)

    /** Set true the first time any `MachineStatFactory` is CONSTRUCTED (service-loader discovery reached
      * the provider), independent of whether the sampler then started. A test forces `object Stat`'s
      * class-init eager scan and asserts this to prove the scan reaches the factory, even when the sampler
      * start is opted out (as it is for the module's own test runs). Test-observation seam only.
      */
    private[machine] val constructed = AtomicBoolean.Unsafe.init(false)

    /** Starts the sampler at most once across all factory constructions (CAS-gated), unless opted out. The
      * sampler runs in a detached fiber (`Fiber.initUnscoped`) so it outlives the triggering call's own
      * scope; the tick loop inside `MachineSampler.run` keeps that fiber's own scope open until interrupt.
      * Answers the sampler fiber iff this call won the CAS and started it, so a test can distinguish a start
      * from an opt-out suppression from a CAS-lost one, and can stop the fiber it started. The sampler is a
      * process-lifetime singleton, so the factory discards it and nothing in production holds a way to stop it.
      *
      * `disabled` defaults to the `kyo.machine.disabled` flag and is a parameter so a test can drive both
      * arms without a process-wide property; a `StaticFlag` resolves once at class load and cannot be staged.
      */
    def triggerStart(disabled: Boolean = kyo.machine.disabled())(using AllowUnsafe): Maybe[Fiber.Unsafe[Unit, Any]] =
        if !disabled && started.compareAndSet(false, true) then
            given Frame = Frame.internal
            val fiber   = Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Scope.run {
                        MachineSampler.run
                    }
                }
            }
            Present(fiber.unsafe)
        else Absent

    /** Test-only seam: whether the one-shot start CAS has already fired. */
    private[machine] def hasStarted(using AllowUnsafe): Boolean = started.get()

    /** Test-only seam: whether any factory instance has been constructed (discovery reached the provider),
      * regardless of whether the sampler start fired or was opted out.
      */
    private[machine] def wasConstructed(using AllowUnsafe): Boolean = constructed.get()

    /** Test-only seam: resets the one-shot start CAS so an ordered sequence of factory-start scenarios each
      * starts from a known false state. Never called by production code (no reset path exists at runtime; the
      * sampler is a process-lifetime singleton). Present only so the factory-start test scenarios are
      * stageable and order-independent.
      */
    private[machine] def resetForTest()(using AllowUnsafe): Unit = started.set(false)

end MachineStatFactory
