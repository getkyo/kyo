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
    val _                                                                  = MachineStatFactory.triggerStart(System.live.unsafe)
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

    /** Names of the opt-out env var and system property. */
    private val disableEnv  = "KYO_MACHINE_DISABLED"
    private val disableProp = "kyo.machine.disabled"

    /** Reads the opt-out once from the given environment reader. A truthy value suppresses the sampler; unset or
      * unparseable enables (a graceful, fail-open default). Takes a `System.Unsafe` so the read source is
      * injectable: production passes `System.live.unsafe`; a test passes a staged reader with a chosen env/prop
      * map, since `System.let` swaps `System.local` and cannot reach `System.live`, and a real env var cannot be
      * set inside a running JVM. No effect is needed at the read (the SPI factory constructor has no Frame, the
      * established kyo-stats-otlp pattern).
      *
      * The lever is a direct env/property read, not a `kyo.config.StaticFlag`, on purpose: it is read at
      * classpath-presence activation, which runs at `kyo.Stat` class init, before and independently of any
      * kyo-config initialization, and must resolve on JVM, Node and Native alike. It reads through
      * `System.Unsafe.env`, so it resolves `process.env` on Node, where `java.lang.System.getenv` returns null;
      * the nearest sibling, kyo-stats-otlp, reads its own activation env the same bootstrap-time way.
      *
      * The environment variable deliberately takes precedence over the system property: the env var is the
      * per-host bootstrap lever set on the deployment, and the system property is the local development override.
      * This precedence is intentional, distinct from the property-first order a `StaticFlag` resolves.
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
            val fiber = Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Scope.run {
                        MachineSampler.run
                    }
                }
            }
            startedFiber.set(Present(fiber.unsafe))
            true
        else false

    /** The last-started sampler fiber, for the test seam below. Production never reads this (the sampler is
      * a process-lifetime singleton), but a test that stages a start must be able to stop it rather than
      * leak a forever-running detached fiber holding /proc read handles.
      */
    private val startedFiber: AtomicRef.Unsafe[Maybe[Fiber.Unsafe[Unit, Any]]] =
        AtomicRef.Unsafe.init(Absent)

    /** Test-only seam: interrupts the last-started sampler fiber (if any) and clears the CAS, so a test that
      * stages a `triggerStart` leaves no live sampler loop behind. Never called by production code.
      */
    private[machine] def stopForTest()(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        startedFiber.getAndSet(Absent).foreach(f => discard(f.interrupt()))
        started.set(false)
    end stopForTest

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
