# Phase 06 Decisions

## INV-009: OnceCell idempotence detection

**Finding addressed:** C2 - OnceCell.get() CAS race allows both threads to run init(); the one losing the CAS discards its value. Implementation assumed (but did not enforce) idempotence.

**Approach chosen: `.equals()` for equality check, not `!=` operator**

Scala 3's strict equality check rejects `AnyRef != AnyRef` at compile time (E172). Options considered:

1. Use `!=` on `AnyRef` - rejected by compiler with E172 (strict equality).
2. Cast to `Any` before `!=` - would work but requires an extra cast.
3. Use `.equals()` directly - idiomatic Java interop, avoids the strict equality restriction, works correctly for value types (Int, String) boxed into AnyRef and for reference types. Chosen.

**Detection flag: `OnceCell.debugIdempotent`**

Read once from `java.lang.System.getProperty("kyo.tasty.OnceCell.debug", "false")` at object-initialization time. No runtime overhead in production (flag is `val`, JIT can constant-fold it).

Visibility: `private[kyo]` - accessible to tests in the `kyo` package, not part of the public API.

**Test design: jvmOnly for Thread-based tests**

Tests 5 and 6 use raw `java.lang.Thread` and `CopyOnWriteArrayList`. These are JVM-only. JS and Native are single-threaded and the CAS-race path is structurally unreachable on those platforms. Tests tagged with `jvmOnly` from the existing `Test.scala` harness. JS/Native still compile (the `jvmOnly` tag is defined in shared `Test.scala` and skips at runtime, not at compile time).

Tests 2, 3, and 4 are cross-platform and exercise the basic get/cache/flag behavior without Thread.

**init() body uses explicit statement separation, not semicolons**

The `counter.incrementAndGet()` call in Test 3's init lambda returns `Int`, which would be the last expression. Two separate lines are used to increment and return 7, satisfying the no-semicolons rule.

**Scala anonymous class syntax for Runnable**

`new Thread(() => ...)` fails when the lambda body returns a non-Unit value (e.g., `CopyOnWriteArrayList.add` returns `Boolean`). Used `new Thread(new Runnable: def run(): Unit = ...)` to match the JVM Runnable interface without SAM inference ambiguity.

## Phase 06 verify fix: replace Thread-based tests with kyo.Async.foreach

**Problem:** Tests 5 and 6 used `new Thread(...)`, `.start()`, `.join()`, `CopyOnWriteArrayList` directly.
The Scala.js linker rejects ANY `java.lang.Thread` reference in `shared/` sources, even inside
`taggedAs jvmOnly` tests, because the linker processes all class bodies before tag-based skipping.

**Fix approach: kyo.Async.foreach with 8 fibers, concurrency = 8**

Both tests rewritten to use `Async.foreach(1 to 8, concurrency = 8)` inside `in run { ... }`.
The `run` helper in `BaseKyoCoreTest` discharges `Abort[Any] & Async & Scope`, so the effect row unifies.

Test 5 (same-winner): `Sync.Unsafe.defer(cell.get())` inside each fiber. `AllowUnsafe` is
provided by `Sync.Unsafe.defer`'s context-function parameter, no import needed. Results collected
as `Chunk[Int]`; asserted with `.forall(_ == 99)` rather than `toSet == Set(99)` to avoid a
strict-equality `Set[Any]` vs `Set[Int]` mismatch under `-Yexplicit-nulls`.

Test 6 (debug/non-idempotent): Each fiber wraps its computation in
`Abort.run[IllegalStateException] { Abort.catching[IllegalStateException] { Sync.Unsafe.defer(...) } }`
so the collected type is `Chunk[Result[IllegalStateException, AnyRef]]` uniformly for both the
`if debugIdempotent` and `else` branches. This avoids an if/else type-unification failure that
arose when the two branches returned different effect rows. The explicit ascription
`.map { (results: Chunk[Result[IllegalStateException, AnyRef]]) => ... }` pins the type for the
`eq`-based reference-identity check (`val values: Chunk[AnyRef] = successes.map(_.getOrThrow)`).

**Verification results**

- `project kyo-tasty; testOnly kyo.OnceCellTest` (JVM): 6/6 passed.
- `project kyo-tastyJS; Test/fastLinkJS`: success, no Thread linker errors.
- `project kyo-tastyNative; Test/compile`: success.
- HEAD: `a57dde403` (unchanged, no commit made per instructions).

## Invariants produced

- INV-009: `OnceCell.init` lambdas are idempotent; concurrent first-callers compute the same value modulo equality. Debug mode (`-Dkyo.tasty.OnceCell.debug=true`) throws `IllegalStateException` when a CAS-losing thread's value differs from the winner.
