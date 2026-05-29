# Phase 02d prep

Phase name: Bridge Symbol.body through Sync.Unsafe.defer
Files to produce: 0
Files to modify: 1 (kyo-tasty/shared/src/main/scala/kyo/Tasty.scala)
Tests: 2 (in kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala)
Plan cites: ./05-plan.md §Phase 02d

## Verbatim API signatures

- `inline def defer[A, S](inline f: AllowUnsafe ?=> A < S)(using inline frame: Frame): A < (Sync & S)`
  at kyo-core/shared/src/main/scala/kyo/Sync.scala:138
  Inside `object Unsafe` nested in `object Sync`. Provides `AllowUnsafe` implicitly via `AllowUnsafe.embrace.danger` at line 140. This is the exact mechanism that replaces the freestanding `import AllowUnsafe.embrace.danger` inside `Symbol.body`.

- `def body(using Frame): Tree < (Sync & Abort[TastyError])`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:692
  The public accessor. Signature is unchanged by Phase 02d.

## File anchors

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`
  - Lines 692-739: `Symbol.body` method. The edit region is lines 698-735: the `TastyOrigin` branch that contains `import AllowUnsafe.embrace.danger` and the try/catch that calls `_bodyOnce.get()`.

Exact current import-danger site (Tasty.scala:700):
```scala
import AllowUnsafe.embrace.danger
```

Exact OnceCell read site (Tasty.scala:718):
```scala
try Right(_bodyOnce.get())
```

The `_bodyOnce` field is declared at Tasty.scala:548:
```scala
private[kyo] val _bodyOnce: kyo.internal.tasty.symbol.OnceCell[Tree] =
    new kyo.internal.tasty.symbol.OnceCell[Tree](() =>
        import AllowUnsafe.embrace.danger
        origin match
```

Note: the `_bodyOnce` init lambda (Tasty.scala:549-553) has its own separate `import AllowUnsafe.embrace.danger` at line 553. Phase 02d only touches the `Symbol.body` method (lines 692-739). The init lambda import is a distinct site and is NOT in scope for Phase 02d.

## Current structure of Symbol.body (to be modified)

```scala
// Tasty.scala:692-738 (current, abbreviated)
def body(using Frame): Tree < (Sync & Abort[TastyError]) =
    import Name.asString
    origin match
        case Symbol.JavaOrigin =>
            Abort.fail(TastyError.NotImplemented("body not available for Java symbols"))
        case o: Symbol.TastyOrigin =>
            // Unsafe: ... embraced here at the body accessor boundary ...
            import AllowUnsafe.embrace.danger           // LINE 700 -- REMOVE THIS
            if !home.isAssigned then stub("Symbol.body")
            else
                home.get().checkOpen.andThen:
                    if o.bodyStart == 0 || ... then
                        Abort.fail(TastyError.NotImplemented(...))
                    else
                        if home.get().isClosed then
                            Abort.fail(TastyError.ClasspathClosed)
                        else
                            val decoded: Either[TastyError, Tree] =
                                try Right(_bodyOnce.get())       // OnceCell read site
                                catch
                                    case ex: DecodeException => Left(TastyError.MalformedSection(...))
                                    case ex: ArrayIndexOutOfBoundsException => Left(TastyError.MalformedSection(...))
                                    case _: IllegalStateException => Left(TastyError.ClasspathClosed)
                            decoded match
                                case Right(t) => Sync.defer(t)
                                case Left(e)  => Abort.fail(e)
```

## Target structure after Phase 02d

The `import AllowUnsafe.embrace.danger` at line 700 is removed. The try/catch block that calls `_bodyOnce.get()` is wrapped in `Sync.Unsafe.defer { ... }`. The decoded `Either` unwrap stays outside the defer because it only dispatches to `Sync.defer`/`Abort.fail`, which are already pure kyo constructors.

Key structural decision: the `try/catch` and the `_bodyOnce.get()` call must be inside the `Sync.Unsafe.defer` block because `_bodyOnce.get()` requires `AllowUnsafe`. The `decoded match` that calls `Sync.defer(t)` or `Abort.fail(e)` is kyo-effect construction and should remain outside the `defer` block (or the `defer` block returns `Either` and the match sits after it, keeping the effect row clean).

Concrete target shape:

```scala
Sync.Unsafe.defer:
    try Right(_bodyOnce.get())
    catch
        case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
            Left(TastyError.MalformedSection("ASTs", s"body decode failed for '${name.asString}': ${ex.getMessage}"))
        case ex: ArrayIndexOutOfBoundsException =>
            Left(TastyError.MalformedSection("ASTs", s"body truncated for '${name.asString}': ${ex.getMessage}"))
        case _: IllegalStateException =>
            Left(TastyError.ClasspathClosed)
.flatMap:
    case Right(t) => Sync.defer(t)
    case Left(e)  => Abort.fail(e)
```

The `Sync.Unsafe.defer` block returns `Either[TastyError, Tree] < Sync`. The `.flatMap` (or `match` on the result) converts it to `Tree < (Sync & Abort[TastyError])`. Both type-check without any extra ascription.

## Edge cases and gotchas

- `_bodyOnce.get()` is the only unsafe call inside `Symbol.body`. The surrounding `home.get()` calls at lines 703 and 714 also use the `import AllowUnsafe.embrace.danger` in scope. After removing the import, these two `home.get()` calls must also be covered. They are `ClasspathRef` reads (also unsafe-tier); wrap the entire `else` branch content in `Sync.Unsafe.defer`, or ensure `home.get()` is covered by a nested defer.
  cited at Tasty.scala:703 and Tasty.scala:714 (both call `home.get()` under the embraced import).

- `Sync.Unsafe.defer` is `inline`, so it compiles away at each call site. No allocation cost.
  cited at kyo-core/shared/src/main/scala/kyo/Sync.scala:138 (`inline def defer`).

- The plan's BEFORE/AFTER pseudocode (05-plan.md:450-468) shows a simplified structure. The actual body (Tasty.scala:692-738) has a two-level nesting: an outer `if !home.isAssigned` guard and an inner `if home.get().isClosed` guard. The `Sync.Unsafe.defer` block must cover all unsafe reads, not just the `_bodyOnce.get()` leaf.

- `home.isAssigned` at line 701 is also an unsafe-tier call (same `ClasspathRef`). It too needs `AllowUnsafe`. Check whether `home.isAssigned` is annotated as unsafe or has a safe overload.
  cited at Tasty.scala:701 (`if !home.isAssigned`).

## Test-data suggestions

- A class with a trivially-decodable body (`def bar = 42`): confirms `_bodyOnce.get()` succeeds and the tree is cached on second call (reference equality).
- A symbol whose `bodyStart == 0`: confirms the `NotImplemented` path still fires before entering `Sync.Unsafe.defer`.
- A closed classpath: confirms `ClasspathClosed` is still surfaced after the refactor (the `IllegalStateException` catch inside `Sync.Unsafe.defer` still works).

## Anti-flakiness deltas

- The `_bodyOnce` init lambda runs synchronously on first access; subsequent calls return the cached value. No fiber-ordering risk inside `Sync.Unsafe.defer`.
- `SnapshotRoundTripJvmTest.scala:125` exercises the post-close path via `sym.body` after arena close; that test must remain green after Phase 02d. The `IllegalStateException -> ClasspathClosed` mapping inside the catch must stay inside the `Sync.Unsafe.defer` block.
  cited at kyo-tasty/jvm/src/test/scala/kyo/SnapshotRoundTripJvmTest.scala:125.

## Cross-platform notes

- Platforms: jvm, js, native.
- `OnceCell` and `ClasspathRef` are `shared/` types; no per-platform divergence in `Symbol.body`.
- Tests for Phase 02d target `kyo-tasty/shared/src/test/scala/kyo/TreeUnpicklerTest.scala` (shared), so all three platforms exercise the same code path.

## Concerns

- The plan's BEFORE pseudocode omits `home.get()` calls (lines 703, 714) that also require `AllowUnsafe`. The impl agent must audit every unsafe call in the method, not just `_bodyOnce.get()`. Recommendation: wrap the entire `TastyOrigin` branch body (lines 700-737) in a single `Sync.Unsafe.defer` block, then factor out the kyo-effect constructors (`Abort.fail`, `Sync.defer`) to the outside of the defer so the return type stays `Tree < (Sync & Abort[TastyError])`.

- Test placement: the plan says tests live in `TreeUnpicklerTest.scala`. That file does not appear to exist yet (not found in the test file list above). The existing `TastyTest.scala` is in `shared/` and is the closest host. The impl agent should either create `TreeUnpicklerTest.scala` (consistent with the plan) or extend `TastyTest.scala` (simpler). No separate `TastyBodyTest.scala` is warranted; the plan is explicit about `TreeUnpicklerTest.scala`.

- Cascade callsites: `Symbol.body` is the only public entry point; internal code does not call `.body` except via the public accessor. No cascade updates needed. `SnapshotRoundTripJvmTest.scala:161` calls `sym.body` in a test but its signature-side is unchanged. No callers need updating.

- The test "Symbol.body body has no import AllowUnsafe.embrace.danger" (plan test 2) is a source-substring check. It should grep `Tasty.scala` for the exact string inside the `Symbol.body` method only, not the entire file (the `_bodyOnce` init lambda at line 553 retains its own import).
