# Phase 24a Decisions

## Concurrency pattern: Async.foreach

All three tests use `kyo.Async.foreach(range, concurrency = N) { i => Sync.defer { ... } }` (or
`Sync.Unsafe.defer` where `AllowUnsafe` is required). This is the canonical kyo cross-platform
concurrency primitive. Raw `Thread` is banned: it fails the Scala.js linker.

## OnceCell T7: 64-fiber concurrent first-call (OnceCellTest)

`OnceCell[Long](() => System.nanoTime())` is shared across 64 fibers. `Sync.Unsafe.defer`
wraps the `cell.get()` call to satisfy the `AllowUnsafe` proof. After all fibers complete the
`Chunk[Long]` is checked: every element must equal `results(0)`. The cell's CAS guarantees
exactly one lambda execution; all 64 fibers return the same winning value.

## SingleAssign T7: 16-fiber concurrent set (SingleAssignTest)

Each fiber attempts `slot.set(fiberIndex)` inside `Abort.catching[IllegalStateException]` wrapped
in `Abort.run[IllegalStateException]`. This produces `Chunk[Result[IllegalStateException, Unit]]`.
The test asserts exactly one `Success` and 15 `Failure` values whose messages contain "already set"
(the actual message from `SingleAssign.scala` line 26: "SingleAssign already set").

`slot.get()` is called in the `.map` block after all fibers finish. The class-level
`import AllowUnsafe.embrace.danger` satisfies the `AllowUnsafe` proof for that call.

Expected winner: non-deterministic (any of the 16 fiber indices). The test only asserts
`winnerValue >= 0 && winnerValue < 16`, not a specific index.

## TypeArena T7: 8-fiber concurrent interning (TypeArenaTest)

TypeArena is NOT thread-safe and is designed for one-per-fiber allocation (per the scaladoc).
Each fiber creates its own `TypeArena.canonical()`, calls `arena.intern(t)` (public API; the
private `internRec` is only accessible inside `merge`), and returns `(internedRef, arena)`.

Because `intern` calls `map.getOrElseUpdate(key, t)` on an empty single-entry arena,
and `t` is passed as the value, each fiber gets back `t` itself (reference-equal to the original
`Tasty.Type.Named(sym)` object). All 8 returned refs are `eq` to `t`.

After all fibers finish, the 8 per-fiber arenas are merged sequentially into a canonical arena
(mimicking Phase C). The merge uses `concurrency = 1` implicitly via a plain `while` loop
in `.map`. The canonical arena must report `values.size == 1` (one structural entry).

## Platform notes

- JVM: all 3 new tests run and pass (23/23 in the three test classes).
- JS: jvmOnly-tagged TypeArena depth tests are ignored (3 ignored); all platform-neutral tests pass (20/20).
- Native: same as JS pattern (20/20 pass, 3 ignored).
- No `taggedAs` annotation was applied to any of the 3 new tests; they run on all platforms.
