# Phase 4 v3 Prep

Phase 4 adds an explicit `ClasspathClosed` check to `Symbol.body` before `_bodyMemo.get()` is called, plus one regression test in `TreeUnpicklerTest`.

**Status as of HEAD (4b1d041f9)**: Phase 4 changes are present in the working directory but NOT committed. The unstaged diff covers exactly the two files described in the Phase 4 plan.

---

## Verbatim current `body` accessor implementation (HEAD commit, before unstaged Phase 4 diff)

```scala
def body(using Frame): Tree < (Sync & Abort[ReflectError]) =
    import Name.asString
    origin match
        case Symbol.JavaOrigin =>
            Abort.fail(ReflectError.NotImplemented("body not available for Java symbols"))
        case o: Symbol.TastyOrigin =>
            if !home.isAssigned then stub("Symbol.body")
            else
                home.get().checkOpen.andThen:
                    if o.bodyStart == 0 || o.bodyEnd == 0 || kind == SymbolKind.Package then
                        Abort.fail(ReflectError.NotImplemented("body not available for this symbol kind"))
                    else
                        // Decode via Memo to cache; the Memo init lambda runs synchronously on first call.
                        // If the decode threw (corrupt bytes), convert to Abort.fail(MalformedSection).
                        // The try/catch runs before entering any kyo effect so exceptions become Either.
                        // Unsafe: Memo.get() is an unsafe-tier helper; AllowUnsafe is embraced here.
                        import AllowUnsafe.embrace.danger
                        val decoded: Either[ReflectError, Tree] =
                            try Right(_bodyMemo.get())
                            catch
                                case ex: kyo.internal.reflect.tasty.TreeUnpickler.DecodeException =>
                                    Left(ReflectError.MalformedSection(
                                        "ASTs",
                                        s"body decode failed for '${name.asString}': ${ex.getMessage}"
                                    ))
                                case ex: ArrayIndexOutOfBoundsException =>
                                    Left(ReflectError.MalformedSection(
                                        "ASTs",
                                        s"body truncated for '${name.asString}': ${ex.getMessage}"
                                    ))
                                case _: IllegalStateException =>
                                    // Thrown when a mmap-backed ByteView is read after its arena was closed.
                                    Left(ReflectError.ClasspathClosed)
                        decoded match
                            case Right(t) => Sync.defer(t)
                            case Left(e)  => Abort.fail(e)
    end match
end body
```

---

## Exact location for state check injection

The Phase 4 check is injected **after** `import AllowUnsafe.embrace.danger` and **before** `val decoded = try Right(_bodyMemo.get())`. Specifically, the check replaces the `val decoded = ...` block with a conditional: if closed, return `Abort.fail(ClasspathClosed)` immediately; otherwise, execute the existing `val decoded` try/catch block as the else branch.

The full working-directory diff for `Reflect.scala` (already present as unstaged change):

```diff
-                                import AllowUnsafe.embrace.danger
-                                val decoded: Either[ReflectError, Tree] =
-                                    try Right(_bodyMemo.get())
-                                    ...
-                                decoded match
-                                    case Right(t) => Sync.defer(t)
-                                    case Left(e)  => Abort.fail(e)
+                                import AllowUnsafe.embrace.danger
+                                // Unsafe: Reading classpath state under AllowUnsafe to detect closed classpath
+                                // before body decode; state transitions are monotonic (Closed is terminal) so
+                                // a stale read returns a conservative result.
+                                if home.get().stateRef.unsafe.get() == kyo.internal.reflect.query.Classpath.State.Closed then
+                                    Abort.fail(ReflectError.ClasspathClosed)
+                                else
+                                    val decoded: Either[ReflectError, Tree] =
+                                        try Right(_bodyMemo.get())
+                                        ...
+                                    decoded match
+                                        case Right(t) => Sync.defer(t)
+                                        case Left(e)  => Abort.fail(e)
```

Note: `body` retains `home.get().checkOpen.andThen:` at the top. Phase 4 does NOT remove that check; it adds the second, AllowUnsafe-based check before the Memo access. The design rationale is that `checkOpen` handles the case where `body` is called before the classpath is ready (Building state), while the direct `stateRef` check provides a lower-latency path for the Closed state immediately before the decode.

---

## State enum verbatim (with Closed case)

From `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala`:

```scala
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
        val errors: Chunk[ReflectError],
        val moduleIndex: Map[String, Reflect.ModuleDescriptor]
    ) extends State

    /** Terminal state: scope has exited, all resolving accessors fail. */
    case object Closed extends State
end State
```

The correct name is `Classpath.State.Closed` (case object, accessed as `kyo.internal.reflect.query.Classpath.State.Closed`). This is what the working-directory Phase 4 change uses at line 640 of `Reflect.scala`.

`stateRef` is declared as `private[kyo] val stateRef: AtomicRef[Classpath.State]` in the `Classpath` class. The `body` accessor reads it via `home.get().stateRef.unsafe.get()` which is accessible because `stateRef` is `private[kyo]` and `Reflect.scala` is in the `kyo` package.

---

## Pattern for "open in Scope, escape Symbol, close, assert closed-state error"

The working-directory Test 10 implements this pattern:

```scala
"sym.body after Scope close returns Abort.fail(ClasspathClosed)" in run {
    val captureResult: Result[ReflectError, Reflect.Symbol] < Async =
        Scope.run:
            Abort.run[ReflectError]:
                openPlainClassCp.flatMap: cp =>
                    cp.findClass("kyo.fixtures.PlainClass") match
                        case Present(classSym) =>
                            val memberWithBody = classSym.declarations.find: s =>
                                s.origin match
                                    case o: Reflect.Symbol.TastyOrigin => o.bodyStart > 0 && o.bodyEnd > 0
                                    case _                             => false
                            memberWithBody match
                                case Some(sym) => Kyo.lift(sym)
                                case None      => Abort.fail(ReflectError.NotImplemented("no member with body in PlainClass"))
                        case Absent =>
                            Abort.fail(ReflectError.NotImplemented("PlainClass not found in fixture"))
    captureResult.flatMap:
        case Result.Success(sym) =>
            // Scope has exited; classpath is closed. body must return ClasspathClosed.
            Abort.run[ReflectError](sym.body).map:
                case Result.Failure(ReflectError.ClasspathClosed) =>
                    succeed
                case Result.Failure(e) =>
                    fail(s"Expected ClasspathClosed but got: $e")
                case Result.Success(_) =>
                    fail("Expected ClasspathClosed but body decode succeeded on closed classpath")
                case Result.Panic(t) =>
                    throw t
        case Result.Failure(ReflectError.NotImplemented(msg)) =>
            // No member with a body found; treat as a test infrastructure limitation.
            pending
        case Result.Failure(e) =>
            fail(s"Failed to capture symbol: $e")
        case Result.Panic(t) =>
            throw t
}
```

Key structural points:
- `Scope.run` is the synchronous boundary. When it returns, all `Scope.ensure` finalizers (including `InternalClasspath.close`) have run. No timing dependency.
- `Abort.run` wraps the scope-interior work to convert `Abort[ReflectError]` to `Result`.
- `Kyo.lift(sym)` escapes the symbol reference out of the Scope after capture.
- After `Scope.run` returns, `stateRef` is `State.Closed` (set by `Classpath.close` in the `Scope.ensure` finalizer).
- `sym.body` is called OUTSIDE `Scope.run`, after the classpath is definitively closed.

The same structural idiom appears in the existing TreeUnpicklerTest Test 7 and QueryApiTest "Phase 3 Test 4" (parents post-close) and "Phase 4 Test 4" (companion post-close).

---

## Test data: fixture with a body

**PlainClass** is the correct fixture. Evidence:

1. `AstUnpicklerTest` Test 18 ("pass 1 on PlainClass.tasty: Method symbol body slice (bodyStart, bodyEnd) is non-zero") proves that `PlainClass.tasty` contains at least one `SymbolKind.Method` with `bodyStart > 0 && bodyEnd > bodyStart`.

2. `QueryApiTest` "Phase 3: sym.declarations for PlainClass contains known field x" proves that the full-classpath flow (openPlainClassCp + findClass + declarations) populates the `declarations` slot for the `PlainClass` class symbol.

3. The constructor `<init>` symbol (kind Method) and any method bodies decoded during pass1 will be in `classSym.declarations`. The Phase 4 test finds the first declaration with `bodyStart > 0` via `declarations.find`.

`openPlainClassCp` is the existing helper in `TreeUnpicklerTest` that uses `MemoryFileSource` with `Embedded.plainClassTasty`.

---

## Anti-flakiness

The test is not timing-dependent because `Scope.run` is synchronous. The sequence is:

1. Enter `Scope.run` block.
2. `InternalClasspath.allocate` creates the classpath and registers a `Scope.ensure` finalizer via `Scope.ensure(Sync.defer(InternalClasspath.close(rawCp)))`.
3. `ClasspathOrchestrator.openInto` runs synchronously inside the fiber.
4. `findClass` and `declarations` are pure accessors (Phase 3), returning values without any suspension.
5. `Kyo.lift(sym)` wraps the escaped symbol.
6. `Scope.run` completes, running the `Scope.ensure` finalizer which calls `InternalClasspath.close(rawCp)` which sets `stateRef` to `State.Closed`.
7. `captureResult` is the `Result` value after scope exit.
8. `sym.body` is called next; at this point `stateRef` is `State.Closed` with 100% certainty.

No `sleep`, no `Async.sleep`, no race window. The test is fully deterministic.

---

## Concerns

**CONCERN-1: `pending` fallback is too lenient.**

If `classSym.declarations.find` returns `None` (no method with `bodyStart > 0`), the outer `Abort.fail(NotImplemented)` causes `captureResult` to be `Result.Failure(NotImplemented(...))`, which the test catches with `pending`. This silently passes. But `AstUnpicklerTest` Test 18 proves PlainClass always has at least one method with a body. The `pending` fallback exists as a safety valve for cross-platform environments where the fixture might somehow not decode, but it weakens the test. Consider replacing `pending` with `fail(s"No member with body found in PlainClass: $msg")` for a stricter test. The plan text does not mandate `pending`; the current implementation chose it. Given Phase 4 adds only 1 test, this concern is low-priority for Phase 4 but should be noted in the Phase 8 audit.

**CONCERN-2: `checkOpen` and direct `stateRef` check are both present after Phase 4.**

After Phase 4, `body` has two closed-state guards: `checkOpen` (returns `Abort.fail(ClasspathClosed)` via the Sync effect) and the new direct `stateRef.unsafe.get() == State.Closed` guard. `checkOpen` runs first (it is effectful and produces a `Sync & Abort[ReflectError]` computation). The direct check then runs inside the `AllowUnsafe` block. Both are correct; the direct check is the Phase 4 contract (provides the explicit "before Memo.get" guard specified by the plan). The `checkOpen` guard is not removed. This double-check is intentional defense in depth per the plan's non-goal: "in-flight decodes that race with close may observe a stale open window."

**CONCERN-3: Test 10 is structurally identical to existing Test 7.**

Test 7 ("sym.body after classpath close returns Abort.fail(ClasspathClosed)") uses the class symbol directly (kind Class, no body) and accepts both `ClasspathClosed` and `NotImplemented` as success outcomes. Test 10 finds a member with a body, making `ClasspathClosed` the only acceptable outcome (rejecting `NotImplemented` and `Success`). Test 10 is therefore strictly stronger and is the correct regression test for Phase 4. Test 7 remains as a complementary smoke test for the `checkOpen` path.

**CONCERN-4: Test count.**

Phase 4 adds 1 test. The baseline is 244 (JVM). After Phase 4 commit, the expected count is 245 (JVM). The plan summary table says 246 cumulative, based on the Phase 2 expectation of 245 (which was off by 1 due to the extra test deletion in Phase 2). The correct target is **245**, not 246. The Phase 5 no-op and Phase 6 rename add 0 tests each. The plan's summary table will need a correction note in the Phase 8 audit.
