package kyo.internal.tasty.query

import kyo.*
import kyo.internal.tasty.symbol.SymbolId
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
    private[kyo] def pureClass(fqn: String)(using AllowUnsafe): Maybe[Tasty.Symbol] =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
            case _                        => Maybe.Absent
        end match
    end pureClass

    /** Pure package lookup: reads the packageIndex directly via AllowUnsafe. */
    private[kyo] def purePackage(fqn: String)(using AllowUnsafe): Maybe[Tasty.Symbol] =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => Maybe(s.packageIndex.get(fqn).orNull)
            case _                        => Maybe.Absent
        end match
    end purePackage

    /** Pure module lookup: reads the moduleIndex directly via AllowUnsafe. */
    private[kyo] def pureModule(name: String)(using AllowUnsafe): Maybe[Tasty.ModuleDescriptor] =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => Maybe(s.moduleIndex.get(name).orNull)
            case _                        => Maybe.Absent
        end match
    end pureModule

    /** Pure top-level classes: reads the topLevelClasses Chunk directly via AllowUnsafe. */
    private[kyo] def pureTopLevelClasses(using AllowUnsafe): Chunk[Tasty.Symbol] =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.topLevelClasses
            case _                        => Chunk.empty
        end match
    end pureTopLevelClasses

    /** Pure packages: reads the packages Chunk directly via AllowUnsafe. */
    private[kyo] def purePackages(using AllowUnsafe): Chunk[Tasty.Symbol] =
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
    private[kyo] def accumulatedErrors(using AllowUnsafe): Chunk[TastyError] =
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
    private[kyo] def allSymbols(using AllowUnsafe): Chunk[Tasty.Symbol] =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready    => s.allSymbols
            case _: Classpath.State.Building => Chunk.empty
            case Classpath.State.Closed      => Chunk.empty
        end match
    end allSymbols

    /** Read the full fqnIndex as Map[String, SymbolId] for case-class construction (Phase 06 bridge). */
    private[kyo] def readFqnIdMap(using AllowUnsafe): Map[String, SymbolId] =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.fqnIndex.map { case (fqn, sym) => fqn -> sym.id }.toMap
            case _                        => Map.empty

    /** Read the full packageIndex as Map[String, SymbolId] for case-class construction (Phase 06 bridge). */
    private[kyo] def readPackageIdMap(using AllowUnsafe): Map[String, SymbolId] =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.packageIndex.map { case (fqn, sym) => fqn -> sym.id }.toMap
            case _                        => Map.empty

    /** Read the full moduleIndex as Map[String, Tasty.ModuleDescriptor] for case-class construction (Phase 06 bridge). */
    private[kyo] def readModuleIndex(using AllowUnsafe): Map[String, Tasty.ModuleDescriptor] =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.moduleIndex.toMap
            case _                        => Map.empty

    /** Read the canonical TypeArena for case-class construction (Phase 06 bridge). */
    private[kyo] def readCanonical(using AllowUnsafe): kyo.internal.tasty.type_.TypeArena =
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.canonical
            case _                        => kyo.internal.tasty.type_.TypeArena.canonical()

    /** Look up a symbol by SymbolId index.
      *
      * plan: phase-05; provides O(1) SymbolId->Symbol lookup for Subtyping and show. Phase 06 replaces this with the case-class field
      * accessor once allSymbols becomes an IndexedSeq. Returns a sentinel Unresolved symbol for id.value == -1 or out-of-range ids.
      *
      * Uses AllowUnsafe.embrace.danger internally: the allSymbols Chunk is write-once (set on transition to Ready and never modified), so
      * reading it without an explicit effect row is safe.
      */
    private[kyo] def symbol(id: SymbolId): Tasty.Symbol =
        // flow-allow: §839 case 3; allSymbols is immutable after Ready transition; no race possible.
        import AllowUnsafe.embrace.danger
        stateRef.unsafe.get() match
            case s: Classpath.State.Ready =>
                val idx = id.value
                if idx >= 0 && idx < s.allSymbols.size then s.allSymbols(idx)
                else Classpath.sentinelUnresolved
            case _ => Classpath.sentinelUnresolved
        end match
    end symbol

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
    )(using AllowUnsafe): Unit =
        val ready = new State.Ready(allSymbols, topLevelClasses, packages, fqnIndex, packageIndex, canonical, errors, moduleIndex)
        cp.stateRef.unsafe.set(ready)
    end transitionToReady

    /** Transition to Closed. Called by Scope.ensure finalizer. */
    private[kyo] def close(cp: Classpath)(using AllowUnsafe): Unit =
        cp.stateRef.unsafe.set(State.Closed)
    end close

    /** Sentinel symbol returned by Classpath.symbol for out-of-range or unassigned ids.
      *
      * plan: phase-05 bridge; Phase 06 replaces with a direct array index on an IndexedSeq. id = SymbolId(-1) so callers can detect the
      * sentinel via sym.id.value == -1.
      */
    private[kyo] val sentinelUnresolved: Tasty.Symbol =
        Tasty.Symbol.make(Tasty.SymbolKind.Unresolved, Tasty.Flags.empty, Tasty.Name("<unresolved>"))

    /** Sentinel internal Classpath instance used as the initial value of the internalCp bridge field in Tasty.Classpath.
      *
      * Phase 07 removes the internalCp field and this sentinel.
      */
    private[kyo] val sentinelInternal: Classpath =
        // flow-allow: §839 case 3; module-load singleton init; Frame.internal is required for val initialization inside package kyo.
        given Frame = Frame.internal
        import AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(AtomicRef.init[State](State.Closed).map(ref => new Classpath(ref)))
    end sentinelInternal

end Classpath
