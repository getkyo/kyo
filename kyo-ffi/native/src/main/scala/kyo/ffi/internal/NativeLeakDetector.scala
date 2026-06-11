package kyo.ffi.internal

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kyo.Frame
import kyo.discard

/** Phantom state attached to each open guard's [[WeakReference]].
  *
  * Carries the open-site [[Frame]] so the leak warning can name the allocation site, plus the list of retained-callback pool slots the
  * guard holds so the detector can free them when the guard is collected without [[NativeGuard.close]]. Must NOT reference the guard itself
  * , that would keep the guard strongly reachable through the detector's internal map and the WeakReference would never be observed as
  * cleared. See [[NativeLeakDetector]] for the overall flow.
  *
  * `weakRef` is stashed by [[NativeLeakDetector.register]] right after construction so `unregister` can remove the map entry in O(1)
  * without iterating the full live set.
  *
  * @param frame
  *   source [[Frame]] of [[kyo.ffi.Ffi.Guard.open]], used verbatim in the stderr diagnostic.
  */
final private[internal] class GuardLeakToken(
    val frame: Frame,
    private val slotsBuf: scala.collection.mutable.ArrayBuffer[(String, Int)]
):
    /** Back-pointer to the WeakReference stored in [[NativeLeakDetector.live]]. Written once by `register`, read once by `unregister`. Null
      * only during the tiny window between token construction and the `live.put` call in `register`, callers never see this state.
      */
    private[internal] var weakRef: WeakReference[NativeGuard] | Null = null

    /** Append a `(shape, slot)` pair recorded by generated retained-callback code. Synchronized because a future multi-threaded Scala
      * Native could race retained-claim calls from different OS threads on the same guard.
      */
    def addSlot(shape: String, slot: Int): Unit =
        // Carrier-thread substrate: serializes retained-slot bookkeeping against a future multi-threaded Scala Native;
        // OS-thread lock, off the fiber path. See kyo-ffi/CONTRIBUTING.md.
        slotsBuf.synchronized {
            discard(slotsBuf += ((shape, slot)))
        }

    /** Immutable snapshot of the recorded slots, taken under the same lock that guards [[addSlot]]. Used by the sweep path when releasing
      * pool slots for a leaked guard.
      */
    def slotsSnapshot: Seq[(String, Int)] =
        // Carrier-thread substrate: serializes retained-slot bookkeeping against a future multi-threaded Scala Native;
        // OS-thread lock, off the fiber path. See kyo-ffi/CONTRIBUTING.md.
        slotsBuf.synchronized {
            slotsBuf.toSeq
        }
end GuardLeakToken

/** Hand-rolled leak detector for Scala Native's [[NativeGuard]], modeled after the JVM [[java.lang.ref.Cleaner]] integration in
  * `JvmGuard`/`GuardFactory`.
  *
  * Scala Native 0.5 exposes `java.lang.ref.WeakReference` but NOT `java.lang.ref.ReferenceQueue` integration (the 2-arg `WeakReference`
  * constructor and `WeakReference.enqueue()` are unimplemented in SN 0.5 javalib). We therefore use a sweep-based design: a daemon thread
  * (plus opportunistic sweeps on [[register]] and a final sweep at JVM shutdown) iterates the live map and checks each [[WeakReference]]'s
  * `.get()` result, a `null` result means the referent has been collected without [[NativeGuard.close]].
  *
  * Lifecycle:
  *   1. [[register]] is called from the [[NativeGuard]] constructor. It creates a [[WeakReference]] (1-arg, the only form SN 0.5
  *      implements) pointing at the guard, allocates a fresh [[GuardLeakToken]] with a back-pointer to the WeakReference, stores the
  *      mapping in a [[ConcurrentHashMap]], and returns the token.
  *   2. Generated retained-callback sites route through [[NativeGuard.unsafeRetainRetainedSlot]], which records the `(shape, slot)` pair on
  *      the token AND schedules the normal close-time release via `core.retainCleanup`.
  *   3. On [[NativeGuard.close]], the guard calls [[unregister]], an O(1) `live.remove(token.weakRef)` using the stashed back-pointer.
  *   4. If the guard becomes unreachable without close, its WeakReference's `.get()` starts returning `null`. The next [[sweep]] observes
  *      this, emits one stderr diagnostic ([[FfiErrors.leakWarning]]), calls [[CallbackRegistry.releaseRetainedSlotByName]] per recorded
  *      slot so the leaked guard no longer starves the pool, and removes the entry from the live map.
  *
  * Sweep schedule:
  *   - **Opportunistic**: top of every [[register]], catches leaks under high allocation pressure without waiting for the daemon tick.
  *   - **Daemon thread**: started lazily on first [[register]]. Loops `Thread.sleep(sweepIntervalMs); sweep()` until the JVM exits. Sweep
  *     interval configurable via `-Dkyo.ffi.native.leakSweepIntervalMs` (default 1000 ms).
  *   - **Shutdown hook**: registered once on first [[register]]. Runs a final sweep so leaks that survived until process exit still surface
  *     their diagnostics.
  *
  * Thread-safety: the live map is a [[ConcurrentHashMap]]; [[sweep]] is idempotent under racing callers. Token `slotsBuf` mutation is
  * serialized on the buffer object.
  *
  * Counter-intuitive note on the [[GuardRegistry]]: open guards are also held strongly by [[GuardRegistry]] to prevent premature collection
  * of retained callbacks. A leak warning therefore only fires if the user's code path manages to drop the registry reference too, today
  * this only happens when the registry entry is explicitly removed (e.g. `close()` was called but then the user leaked a reference *to the
  * token* for a follow-up retry scenario, or under a future platform that expires registry entries). The detector is a safety net: on a
  * well-behaved platform it never fires; under a future optimization that trades registry strong-refs for weak-refs, it starts catching
  * real leaks.
  */
private[internal] object NativeLeakDetector:

    /** Sweep interval in milliseconds for the daemon thread. Read once at class-load. Non-positive or malformed values fall back to the
      * default.
      */
    val SweepIntervalMs: Long =
        try
            val raw = sys.props.getOrElse("kyo.ffi.native.leakSweepIntervalMs", "1000").toLong
            if raw <= 0 then 1000L else raw
        catch case _: Throwable => 1000L

    // Live entries: WeakReference -> token. Key identity uniqueness is guaranteed because each `register` allocates a fresh WeakReference.
    private val live: ConcurrentHashMap[WeakReference[NativeGuard], GuardLeakToken] =
        new ConcurrentHashMap[WeakReference[NativeGuard], GuardLeakToken]()

    // One-shot guards on the daemon thread start and the shutdown-hook install.
    private val daemonStarted: AtomicBoolean     = new AtomicBoolean(false)
    private val shutdownInstalled: AtomicBoolean = new AtomicBoolean(false)
    private val daemonStopped: AtomicBoolean     = new AtomicBoolean(false)

    // Reference to the daemon thread for tests that want to stop it cleanly.
    @volatile private var daemonThread: Thread | Null = null

    private def ensureDaemon(): Unit =
        if daemonStarted.compareAndSet(false, true) then
            val t = new Thread(
                () =>
                    while !daemonStopped.get() do
                        // Carrier-thread substrate: this is the dedicated leak-detector DAEMON thread, not a fiber. Scala Native 0.5
                        // lacks ReferenceQueue integration, so a sweep loop is the only leak-detection mechanism. See kyo-ffi/CONTRIBUTING.md.
                        try Thread.sleep(SweepIntervalMs)
                        catch case _: InterruptedException => ()
                        try
                            discard(sweep())
                        catch case _: Throwable => ()
                    end while
                ,
                "kyo-ffi-leak-detector"
            )
            t.setDaemon(true)
            daemonThread = t
            try t.start()
            catch case _: Throwable => ()
        end if
    end ensureDaemon

    private def installShutdownHookOnce(): Unit =
        if shutdownInstalled.compareAndSet(false, true) then
            try
                val hook = new Thread(
                    () =>
                        discard(sweep()),
                    "kyo-ffi-leak-detector-shutdown"
                )
                Runtime.getRuntime.nn.addShutdownHook(hook)
            catch
                case _: Throwable =>
                    // Runtime may reject shutdown-hook registration during shutdown itself, swallow.
                    ()
            end try

    /** Register `guard` for post-mortem leak detection.
      *
      * Creates a [[WeakReference]] pointing at `guard`, allocates a [[GuardLeakToken]] with a back-pointer to that WeakReference, stores
      * the mapping in [[live]], and returns the token so the caller (the owning [[NativeGuard]] constructor) can stash it for later
      * [[unregister]] / slot-recording calls.
      *
      * Performs an opportunistic [[sweep]] as a side-effect, catches leaks under high allocation pressure without waiting for the daemon
      * tick. Also lazily starts the daemon thread and installs the shutdown hook on first call.
      */
    def register(guard: NativeGuard, frame: Frame): GuardLeakToken =
        ensureDaemon()
        installShutdownHookOnce()
        discard(sweep())
        val token = new GuardLeakToken(frame, new scala.collection.mutable.ArrayBuffer[(String, Int)]())
        val ref   = new WeakReference[NativeGuard](guard)
        token.weakRef = ref
        live.put(ref, token)
        token
    end register

    /** Remove the token's mapping so its WeakReference no longer surfaces as a leak in a subsequent [[sweep]].
      *
      * Called from [[NativeGuard.close]] after the normal close-time cleanup has released all retained slots. O(1) via the token's stashed
      * [[GuardLeakToken.weakRef]] back-pointer.
      */
    def unregister(token: GuardLeakToken): Unit =
        token.weakRef match
            case null => ()
            case ref =>
                discard(live.remove(ref))
    end unregister

    /** Record a retained-callback pool slot against `token`.
      *
      * Called from [[NativeGuard.unsafeRetainRetainedSlot]] so the detector's slot list mirrors the guard's scheduled release closures.
      * Shape name matches the per-shape identifiers in [[CallbackRegistry]] (`V_U`, `I_U`, etc.).
      */
    def recordRetainedSlot(token: GuardLeakToken, shape: String, slot: Int): Unit =
        token.addSlot(shape, slot)

    /** Iterate [[live]], identifying entries whose WeakReference's referent has been collected (`.get() == null`) and processing each as a
      * leak: emit [[FfiErrors.leakWarning]] once per leaked guard, release every recorded pool slot via
      * [[CallbackRegistry.releaseRetainedSlotByName]], and remove the entry from the map.
      *
      * Returns the number of leaks observed (useful in tests; production callers can discard it).
      *
      * Uses the `ConcurrentHashMap` iterator, which tolerates concurrent modification (weakly consistent), a concurrent `register` or
      * `unregister` mid-sweep is safe; in the worst case we either see the new entry on this pass or the next one.
      */
    def sweep(): Int =
        var leaks = 0
        val it    = live.entrySet().nn.iterator().nn
        while it.hasNext do
            val entry = it.next().nn
            val ref   = entry.getKey.nn
            ref.get() match
                case null =>
                    // Referent collected without close(), process as a leak.
                    val token = entry.getValue.nn
                    // Remove first so a subsequent sweep (from the daemon + the opportunistic register-time sweep racing) does not double-
                    // report. `remove(key, value)` is atomic and only succeeds if the mapping is still the one we observed.
                    if live.remove(ref, token) then
                        leaks += 1
                        try java.lang.System.err.println(FfiErrors.leakWarning(token.frame.show))
                        catch case _: Throwable => ()
                        val slots = token.slotsSnapshot
                        val sit   = slots.iterator
                        while sit.hasNext do
                            val (shape, slot) = sit.next()
                            try CallbackRegistry.releaseRetainedSlotByName(shape, slot)
                            catch case _: Throwable => ()
                        end while
                    end if
                case _ => ()
            end match
        end while
        leaks
    end sweep

    /** Testing hook: simulate a leak for `token` by running the exact leak-processing path the sweep would execute if its WeakReference had
      * already observed a null referent. Emits the stderr warning, releases every recorded pool slot via
      * [[CallbackRegistry.releaseRetainedSlotByName]], and removes the entry from the [[live]] map. Returns 1 if the token was still live,
      * 0 if it had already been swept or unregistered.
      *
      * This hook exists because Scala Native 0.5 does not implement `WeakReference.clear()` (nor `ReferenceQueue` integration), so tests
      * cannot force a `.get() == null` without waiting on the real Immix collector, which would make the test flaky. By calling into the
      * same branch the sweep takes on an observed leak, the test exercises the production code path deterministically.
      *
      * Visible only to tests in the `kyo.ffi.internal` package.
      */
    private[internal] def testForceLeak(token: GuardLeakToken): Int =
        token.weakRef match
            case null => 0
            case ref =>
                if live.remove(ref, token) then
                    try java.lang.System.err.println(FfiErrors.leakWarning(token.frame.show))
                    catch case _: Throwable => ()
                    val slots = token.slotsSnapshot
                    val sit   = slots.iterator
                    while sit.hasNext do
                        val (shape, slot) = sit.next()
                        try CallbackRegistry.releaseRetainedSlotByName(shape, slot)
                        catch case _: Throwable => ()
                    end while
                    1
                else 0
    end testForceLeak

    /** Testing hook: current count of tracked (unclosed, uncollected) guards. */
    private[internal] def testLiveCount: Int = live.size

    /** Testing hook: stop the daemon sweep thread (best-effort). The daemon flag is one-shot, once stopped, the detector still functions
      * via opportunistic register-time sweeps and the shutdown hook, but no new daemon is started.
      */
    private[internal] def testStopDaemon(): Unit =
        daemonStopped.set(true)
        daemonThread match
            case null => ()
            case t =>
                t.interrupt()
                try t.join(1000L)
                catch case _: Throwable => ()
        end match
    end testStopDaemon

end NativeLeakDetector
