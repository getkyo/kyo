package kyo.internal.reflect.query

import kyo.*
import kyo.internal.reflect.type_.TypeArena
import scala.collection.mutable

/** Internal representation of an open classpath.
  *
  * Lifecycle: Building (during Phase A/B/C) -> Ready (after open returns) -> Closed (after outer Scope.run exits).
  *
  * The opaque type `Reflect.Classpath` aliases this class. Extension methods in `Reflect` delegate to the methods here.
  */
final class Classpath private[reflect] (
    private[kyo] val stateRef: AtomicRef[Classpath.State]
):

    /** Guard used by resolving accessors. Fails with ClasspathClosed if state is Closed. Fails with ClasspathBuilding if state is Building
      * (defense-in-depth; user code cannot reach the classpath in Building state).
      */
    private[kyo] def checkOpen(using Frame): Unit < (Sync & Abort[ReflectError]) =
        stateRef.get.map:
            case _: Classpath.State.Building => Abort.fail(ReflectError.ClasspathBuilding)
            case _: Classpath.State.Ready    => Kyo.unit
            case Classpath.State.Closed      => Abort.fail(ReflectError.ClasspathClosed)

    /** Look up a class symbol by fully-qualified dotted name. Returns Absent if not found (soft-fail). Fails with ClasspathClosed if
      * closed.
      */
    private[kyo] def lookupClass(fqn: String)(using Frame): Maybe[Reflect.Symbol] < (Sync & Abort[ReflectError]) =
        checkOpen.andThen:
            stateRef.get.map:
                case s: Classpath.State.Ready    => Maybe(s.fqnIndex.get(fqn).orNull)
                case _: Classpath.State.Building => Maybe.Absent
                case Classpath.State.Closed      => Maybe.Absent

    /** Look up a package symbol by fully-qualified dotted name. */
    private[kyo] def lookupPackage(fqn: String)(using Frame): Maybe[Reflect.Symbol] < (Sync & Abort[ReflectError]) =
        checkOpen.andThen:
            stateRef.get.map:
                case s: Classpath.State.Ready =>
                    Maybe(s.packageIndex.get(fqn).orNull)
                case _ => Maybe.Absent

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
        // Unsafe: non-effectful read of immutable Ready-state classlist
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

    /** Allocate a new `Classpath` in Building state. Called before Phase B begins. */
    private[kyo] def allocate(using Frame): Classpath < Sync =
        AtomicRef.init[State](new State.Building(new mutable.ArrayBuffer[ReflectError]())).map: ref =>
            new Classpath(ref)

    /** Transition from Building to Ready after Phase C completes. */
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
        // Unsafe: atomic CAS of state machine, called from single-threaded Phase C merge
        import AllowUnsafe.embrace.danger
        val ready = new State.Ready(allSymbols, topLevelClasses, packages, fqnIndex, packageIndex, canonical, errors)
        cp.stateRef.unsafe.set(ready)
    end transitionToReady

    /** Transition to Closed. Called by Scope.ensure finalizer. */
    private[kyo] def close(cp: Classpath): Unit =
        // Unsafe: atomic state transition to Closed, called from Scope finalizer
        import AllowUnsafe.embrace.danger
        cp.stateRef.unsafe.set(State.Closed)
    end close

end Classpath
