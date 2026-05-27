package kyo.internal.tasty.query

import kyo.*
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Internal representation of an open classpath.
  *
  * Lifecycle: Building (during Phase A/B/C) -> Ready (after open returns) -> Closed (after outer Scope.run exits).
  *
  * The opaque type `Tasty.Classpath` aliases this class. Extension methods in `Tasty` delegate to the methods here.
  *
  * Pure accessors read immutable Ready-state data via `AllowUnsafe`. The caller contract is: pure accessors are valid only after `open`
  * returns (i.e. the classpath is in Ready state). Using them before `open` returns is a programmer error. After close, pure accessors
  * return whatever heap data is there (closed-state enforcement is the responsibility of `body` alone, added in Phase 4).
  *
  * Effectful accessors (`lookupClass`, `lookupPackage`, `checkOpen`) are retained for internal use (e.g. tests that verify closed-state
  * behavior, or internal orchestration code that runs before Ready).
  */
final class Classpath private[tasty] (
    private[kyo] val stateRef: AtomicRef[Classpath.State]
):
    /** Returns true if the classpath is in the Closed state. Used by Symbol.body for the explicit pre-decode guard.
      *
      * Requires AllowUnsafe because it reads the AtomicRef without an effect context.
      */
    private[kyo] def isClosed(using AllowUnsafe): Boolean =
        stateRef.unsafe.get() == Classpath.State.Closed

    /** Guard used by resolving accessors. Fails with ClasspathClosed if state is Closed. Fails with ClasspathBuilding if state is Building
      * (defense-in-depth; user code cannot reach the classpath in Building state via non-lookup paths).
      */
    private[kyo] def checkOpen(using Frame): Unit < (Sync & Abort[TastyError]) =
        stateRef.get.map:
            case _: Classpath.State.Building => Abort.fail(TastyError.ClasspathBuilding)
            case _: Classpath.State.Ready    => Kyo.unit
            case Classpath.State.Closed      => Abort.fail(TastyError.ClasspathClosed)

    /** Look up a class symbol by fully-qualified dotted name (effectful, for internal/test use).
      *
      * Reads directly from the immutable `fqnIndex` HashMap in the `Ready` state. Returns `Absent` if not found (soft-fail). Fails with
      * `ClasspathClosed` if the classpath has been closed.
      */
    private[kyo] def lookupClass(fqn: String)(using Frame): Maybe[Tasty.Symbol] < (Sync & Abort[TastyError]) =
        stateRef.get.map:
            case s: Classpath.State.Ready    => Maybe(s.fqnIndex.get(fqn).orNull)
            case _: Classpath.State.Building => Abort.fail(TastyError.ClasspathBuilding)
            case Classpath.State.Closed      => Abort.fail(TastyError.ClasspathClosed)

    /** Look up a package symbol by fully-qualified dotted name (effectful, for internal/test use).
      *
      * Reads directly from the immutable `packageIndex` HashMap in the `Ready` state. Returns `Absent` if not found. Fails with
      * `ClasspathClosed` if the classpath has been closed.
      */
    private[kyo] def lookupPackage(fqn: String)(using Frame): Maybe[Tasty.Symbol] < (Sync & Abort[TastyError]) =
        stateRef.get.map:
            case s: Classpath.State.Ready    => Maybe(s.packageIndex.get(fqn).orNull)
            case _: Classpath.State.Building => Abort.fail(TastyError.ClasspathBuilding)
            case Classpath.State.Closed      => Abort.fail(TastyError.ClasspathClosed)

    // ── Pure accessors (v3 Phase 3) ──────────────────────────────────────────

    /** Pure class lookup: reads the fqnIndex directly via AllowUnsafe.
      *
      * Valid only after `open` returns (Ready state). After close, returns whatever the heap state happens to be (closed-state enforcement
      * is Body-only, Phase 4).
      */
    private[kyo] def pureClass(fqn: String): Maybe[Tasty.Symbol] =
        // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
            case _                        => Maybe.Absent
        end match
    end pureClass

    /** Pure package lookup: reads the packageIndex directly via AllowUnsafe. */
    private[kyo] def purePackage(fqn: String): Maybe[Tasty.Symbol] =
        // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => Maybe(s.packageIndex.get(fqn).orNull)
            case _                        => Maybe.Absent
        end match
    end purePackage

    /** Pure module lookup: reads the moduleIndex directly via AllowUnsafe. */
    private[kyo] def pureModule(name: String): Maybe[Tasty.ModuleDescriptor] =
        // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => Maybe(s.moduleIndex.get(name).orNull)
            case _                        => Maybe.Absent
        end match
    end pureModule

    /** Pure top-level classes: reads the topLevelClasses Chunk directly via AllowUnsafe. */
    private[kyo] def pureTopLevelClasses: Chunk[Tasty.Symbol] =
        // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.topLevelClasses
            case _                        => Chunk.empty
        end match
    end pureTopLevelClasses

    /** Pure packages: reads the packages Chunk directly via AllowUnsafe. */
    private[kyo] def purePackages: Chunk[Tasty.Symbol] =
        // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.packages
            case _                        => Chunk.empty
        end match
    end purePackages

    // ── End pure accessors ───────────────────────────────────────────────────

    /** All top-level class symbols (not packages). Effectful version retained for internal orchestration. */
    private[kyo] def allTopLevelClasses(using Frame): Chunk[Tasty.Symbol] < (Sync & Abort[TastyError]) =
        checkOpen.andThen:
            stateRef.get.map:
                case s: Classpath.State.Ready    => s.topLevelClasses
                case _: Classpath.State.Building => Chunk.empty
                case Classpath.State.Closed      => Chunk.empty

    /** All package symbols. Effectful version retained for internal orchestration. */
    private[kyo] def allPackages(using Frame): Chunk[Tasty.Symbol] < (Sync & Abort[TastyError]) =
        checkOpen.andThen:
            stateRef.get.map:
                case s: Classpath.State.Ready    => s.packages
                case _: Classpath.State.Building => Chunk.empty
                case Classpath.State.Closed      => Chunk.empty

    /** Errors accumulated during loading (soft-fail mode). Empty for clean classpaths or strict-mode (strict throws before returning). */
    private[kyo] def accumulatedErrors: Chunk[TastyError] =
        // Unsafe: state.get() - safe non-effectful read since errors are immutable after Phase C
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready    => s.errors
            case b: Classpath.State.Building => Chunk.from(b.errors)
            case Classpath.State.Closed      => Chunk.empty
        end match
    end accumulatedErrors

    /** Look up a JPMS module descriptor by module name (e.g., "java.base"). Effectful version retained for internal use. */
    private[kyo] def lookupModule(name: String)(using Frame): Maybe[Tasty.ModuleDescriptor] < (Sync & Abort[TastyError]) =
        checkOpen.andThen:
            stateRef.get.map:
                case s: Classpath.State.Ready => Maybe(s.moduleIndex.get(name).orNull)
                case _                        => Maybe.Absent

    /** All symbols in the classpath (classes + packages). */
    private[kyo] def allSymbols: Chunk[Tasty.Symbol] =
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
    sealed private[tasty] trait State derives CanEqual
    private[tasty] object State:

        /** Pre-open state: Phase B fibers are still constructing symbols. */
        final class Building(
            val errors: mutable.ArrayBuffer[TastyError]
        ) extends State

        /** Fully loaded and usable state. */
        final class Ready(
            val allSymbols: Chunk[Tasty.Symbol],
            val topLevelClasses: Chunk[Tasty.Symbol],
            val packages: Chunk[Tasty.Symbol],
            val fqnIndex: scala.collection.Map[String, Tasty.Symbol],
            val packageIndex: scala.collection.Map[String, Tasty.Symbol],
            val canonical: TypeArena,
            val errors: Chunk[TastyError],
            val moduleIndex: scala.collection.Map[String, Tasty.ModuleDescriptor]
        ) extends State

        /** Terminal state: scope has exited, all resolving accessors fail. */
        case object Closed extends State
    end State

    /** Allocate a new `Classpath` in Building state. Called before Phase B begins. */
    private[kyo] def allocate(using Frame): Classpath < Sync =
        AtomicRef.init[State](new State.Building(new mutable.ArrayBuffer[TastyError]())).map: ref =>
            new Classpath(ref)

    /** Transition from Building to Ready after Phase C completes.
      *
      * Sets the state to Ready. After this call returns, callers of `lookupClass`/`lookupPackage` can read the immutable maps directly.
      */
    private[kyo] def transitionToReady(
        cp: Classpath,
        allSymbols: Chunk[Tasty.Symbol],
        topLevelClasses: Chunk[Tasty.Symbol],
        packages: Chunk[Tasty.Symbol],
        fqnIndex: scala.collection.Map[String, Tasty.Symbol],
        packageIndex: scala.collection.Map[String, Tasty.Symbol],
        canonical: TypeArena,
        errors: Chunk[TastyError],
        moduleIndex: scala.collection.Map[String, Tasty.ModuleDescriptor]
    ): Unit =
        // Unsafe: atomic state write, called from single-threaded Phase C
        import AllowUnsafe.embrace.danger
        val ready = new State.Ready(allSymbols, topLevelClasses, packages, fqnIndex, packageIndex, canonical, errors, moduleIndex)
        cp.stateRef.unsafe.set(ready)
    end transitionToReady

    /** Transition to Closed. Called by Scope.ensure finalizer. */
    private[kyo] def close(cp: Classpath): Unit =
        // Unsafe: atomic CAS Classpath state -> Closed, called from Scope finalizer
        import AllowUnsafe.embrace.danger
        cp.stateRef.unsafe.set(State.Closed)
    end close

end Classpath
