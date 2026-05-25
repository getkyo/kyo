package kyo.internal.reflect.query

import kyo.*
import kyo.internal.reflect.type_.TypeArena
import scala.collection.mutable

/** Internal representation of an open classpath.
  *
  * Lifecycle: Building (during Phase A/B/C) -> Ready (after open returns) -> Closed (after outer Scope.run exits).
  *
  * The opaque type `Reflect.Classpath` aliases this class. Extension methods in `Reflect` delegate to the methods here.
  *
  * The `readyLatch` is initialized at allocation time and released when `transitionToReady` is called. Callers of
  * `lookupClass`/`lookupPackage` that arrive during the Building phase await the latch (suspending asynchronously) and then re-attempt the
  * lookup once the classpath is Ready. This enables concurrent deduplication via `Cache.memo` (which carries `Async` in its return type)
  * and removes the need for callers to poll.
  */
final class Classpath private[reflect] (
    private[kyo] val stateRef: AtomicRef[Classpath.State],
    // Unsafe: Latch.Unsafe held as a plain field; initialized once at allocate time, released once at transitionToReady.
    private[kyo] val readyLatch: Latch.Unsafe
):

    /** Guard used by resolving accessors. Fails with ClasspathClosed if state is Closed. Fails with ClasspathBuilding if state is Building
      * (defense-in-depth; user code cannot reach the classpath in Building state via non-lookup paths).
      */
    private[kyo] def checkOpen(using Frame): Unit < (Sync & Abort[ReflectError]) =
        stateRef.get.map:
            case _: Classpath.State.Building => Abort.fail(ReflectError.ClasspathBuilding)
            case _: Classpath.State.Ready    => Kyo.unit
            case Classpath.State.Closed      => Abort.fail(ReflectError.ClasspathClosed)

    /** Look up a class symbol by fully-qualified dotted name. Returns Absent if not found (soft-fail).
      *
      * When the classpath is in Building state, the caller suspends (via `Async`) until `transitionToReady` releases the `readyLatch`, then
      * performs the lookup. This provides the building-state gate needed for concurrent callers that may arrive before Phase C completes.
      * Fails with ClasspathClosed if closed after waiting.
      */
    private[kyo] def lookupClass(fqn: String)(using Frame): Maybe[Reflect.Symbol] < (Sync & Async & Abort[ReflectError]) =
        stateRef.get.map:
            case _: Classpath.State.Building =>
                readyLatch.safe.await.andThen:
                    stateRef.get.map:
                        case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
                        case _                        => Abort.fail(ReflectError.ClasspathClosed)
            case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
            case Classpath.State.Closed   => Abort.fail(ReflectError.ClasspathClosed)

    /** Look up a package symbol by fully-qualified dotted name.
      *
      * Same gate semantics as `lookupClass`: awaits `readyLatch` when Building.
      */
    private[kyo] def lookupPackage(fqn: String)(using Frame): Maybe[Reflect.Symbol] < (Sync & Async & Abort[ReflectError]) =
        stateRef.get.map:
            case _: Classpath.State.Building =>
                readyLatch.safe.await.andThen:
                    stateRef.get.map:
                        case s: Classpath.State.Ready => Maybe(s.packageIndex.get(fqn).orNull)
                        case _                        => Abort.fail(ReflectError.ClasspathClosed)
            case s: Classpath.State.Ready => Maybe(s.packageIndex.get(fqn).orNull)
            case Classpath.State.Closed   => Abort.fail(ReflectError.ClasspathClosed)

    /** All top-level class symbols (not packages). */
    private[kyo] def allTopLevelClasses(using Frame): Chunk[Reflect.Symbol] < (Sync & Abort[ReflectError]) =
        checkOpen.andThen:
            stateRef.get.map:
                case s: Classpath.State.Ready    => s.topLevelClasses
                case _: Classpath.State.Building => Chunk.empty
                case Classpath.State.Closed      => Chunk.empty

    /** All package symbols. */
    private[kyo] def allPackages(using Frame): Chunk[Reflect.Symbol] < (Sync & Abort[ReflectError]) =
        checkOpen.andThen:
            stateRef.get.map:
                case s: Classpath.State.Ready    => s.packages
                case _: Classpath.State.Building => Chunk.empty
                case Classpath.State.Closed      => Chunk.empty

    /** Errors accumulated during loading (soft-fail mode). Empty for clean classpaths or strict-mode (strict throws before returning). */
    private[kyo] def accumulatedErrors: Chunk[ReflectError] =
        // Unsafe: state.get() - safe non-effectful read since errors are immutable after Phase C
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready    => s.errors
            case b: Classpath.State.Building => Chunk.from(b.errors)
            case Classpath.State.Closed      => Chunk.empty
        end match
    end accumulatedErrors

    /** All symbols in the classpath (classes + packages). */
    private[kyo] def allSymbols: Chunk[Reflect.Symbol] =
        // Unsafe: allSymbols non-effectful read of immutable Ready state
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready    => s.allSymbols
            case _: Classpath.State.Building => Chunk.empty
            case Classpath.State.Closed      => Chunk.empty
        end match
    end allSymbols

end Classpath

object Classpath:

    /** Lifecycle state of a `Classpath`. */
    sealed private[reflect] trait State derives CanEqual
    private[reflect] object State:

        /** Pre-open state: Phase B fibers are still constructing symbols. */
        final class Building(
            val errors: mutable.ArrayBuffer[ReflectError]
        ) extends State

        /** Fully loaded and usable state. */
        final class Ready(
            val allSymbols: Chunk[Reflect.Symbol],
            val topLevelClasses: Chunk[Reflect.Symbol],
            val packages: Chunk[Reflect.Symbol],
            val fqnIndex: Map[String, Reflect.Symbol],
            val packageIndex: Map[String, Reflect.Symbol],
            val canonical: TypeArena,
            val errors: Chunk[ReflectError]
        ) extends State

        /** Terminal state: scope has exited, all resolving accessors fail. */
        case object Closed extends State
    end State

    /** Allocate a new `Classpath` in Building state. Called before Phase B begins.
      *
      * A `readyLatch` with count=1 is created here and released in `transitionToReady`. Any caller that invokes
      * `lookupClass`/`lookupPackage` before the classpath transitions to Ready will suspend on the latch.
      */
    private[kyo] def allocate(using Frame): Classpath < Sync =
        // Unsafe: Latch.Unsafe.init requires AllowUnsafe; safe here because this is the single
        // allocation point for the latch and the result is immediately captured.
        val latch =
            import AllowUnsafe.embrace.danger
            Latch.Unsafe.init(1)
        AtomicRef.init[State](new State.Building(new mutable.ArrayBuffer[ReflectError]())).map: ref =>
            new Classpath(ref, latch)
    end allocate

    /** Transition from Building to Ready after Phase C completes.
      *
      * Sets the state to Ready and then releases `readyLatch`, unblocking any fibers that are awaiting the completion of classpath
      * construction inside `lookupClass`/`lookupPackage`. The state is written BEFORE releasing the latch so that waking callers see the
      * Ready state immediately.
      */
    private[kyo] def transitionToReady(
        cp: Classpath,
        allSymbols: Chunk[Reflect.Symbol],
        topLevelClasses: Chunk[Reflect.Symbol],
        packages: Chunk[Reflect.Symbol],
        fqnIndex: Map[String, Reflect.Symbol],
        packageIndex: Map[String, Reflect.Symbol],
        canonical: TypeArena,
        errors: Chunk[ReflectError]
    ): Unit =
        // Unsafe: atomic state write + latch release, called from single-threaded Phase C
        import AllowUnsafe.embrace.danger
        val ready = new State.Ready(allSymbols, topLevelClasses, packages, fqnIndex, packageIndex, canonical, errors)
        cp.stateRef.unsafe.set(ready)
        cp.readyLatch.release()
    end transitionToReady

    /** Transition to Closed. Called by Scope.ensure finalizer. */
    private[kyo] def close(cp: Classpath): Unit =
        // Unsafe: atomic CAS Classpath state -> Closed, called from Scope finalizer
        import AllowUnsafe.embrace.danger
        cp.stateRef.unsafe.set(State.Closed)
    end close

end Classpath
