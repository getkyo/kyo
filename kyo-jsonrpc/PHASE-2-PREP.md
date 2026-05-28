# Phase 2 Prep: HandlerCtx and JsonRpcMethod

This document is the sole reference the Phase 2 implementation agent reads before writing any code. All signatures below are verbatim from the sources listed. Do not re-explore the upstream files unless a discrepancy is found; report it instead.

---

## 1. Verbatim API Signatures

### Fiber.Promise.init

Source: `kyo-core/shared/src/main/scala/kyo/Fiber.scala` lines 440-459.

```scala
opaque type Promise[A, S] <: Fiber[A, S] = IOPromise[Any, A < S]

object Promise:
    def init[E, A](using Frame): Promise[E, A] < Sync =
        initWith[E, A](identity)

    inline def initWith[E, A](using inline frame: Frame)[B, S](inline f: Promise[E, A] => B < S): B < (S & Sync) =
        Sync.defer(f(IOPromise()))

    extension [A, S](self: Promise[A, S])(using NotGiven[S <:< Abort[Any]])
        def complete(v: Result[Nothing, A < S])(using Frame): Boolean < Sync
        def completeDiscard(v: Result[Nothing, A < S])(using Frame): Unit < Sync
```

For `HandlerCtx.cancelled` the type is `Fiber.Promise[Unit, Sync]`. Construct via:

```scala
Fiber.Promise.init[Unit, Sync]  // returns Fiber.Promise[Unit, Sync] < Sync
```

Complete it via `promise.completeDiscard(Result.unit)` (Sync effect) to signal cancellation.

### Abort.fail

Source: `kyo-prelude/shared/src/main/scala/kyo/Abort.scala` line 57.

```scala
inline def fail[E](inline value: E)(using inline frame: Frame): Nothing < Abort[E]
```

### Abort.panic

Source: `kyo-prelude/shared/src/main/scala/kyo/Abort.scala` line 67.

```scala
inline def panic[E](inline ex: Throwable)(using inline frame: Frame): Nothing < Abort[E]
```

### Abort.run

Source: `kyo-prelude/shared/src/main/scala/kyo/Abort.scala` line 242.

```scala
def run[E](using Frame)[A, S, ER](
    v: => A < (Abort[E | ER] & S)
)(using ct: ConcreteTag[E], reduce: Reducible[Abort[ER]]): Result[E, A] < (S & reduce.SReduced)
```

`Abort.run[E]` captures panics in the `Result` output. From `runWith` (lines 215-226): a `Result.Panic` is included in the returned `Result[E, A]` when the handler throws. The output type is `Result[E, A]`, not `Result.Partial[E, A]`, so pattern-matching must cover `Success`, `Failure`, and `Panic`.

### Abort.catching

Source: `kyo-prelude/shared/src/main/scala/kyo/Abort.scala` line 547.

```scala
def catching[E](using Frame)[A, S](v: => A < S)(using ct: ConcreteTag[E]): A < (Abort[E] & S)
```

Converts thrown exceptions of type E to `Abort.fail(ex)` and all other exceptions to `Abort.panic(ex)`. Not needed directly for Phase 2 (the panic-catch path goes through `Abort.run[JsonRpcError]` + `Result.Panic` match), but included for completeness.

### Result constructors

Source: `kyo-data/shared/src/main/scala/kyo/Result.scala` lines 50-93.

```scala
object Result:
    inline def apply[A](inline expr: => A): Result[Nothing, A]  // catches throwable as Panic
    def succeed[E, A](value: A): Result[E, A]   // = Success(value)
    def fail[E, A](error: E): Result[E, A]      // = Failure(error)
    def panic[E, A](exception: Throwable): Result[E, A]  // = Panic(exception)
    def unit[E]: Result[E, Unit]                // shared singleton Success(())

// Pattern-match extractors:
case Success(a)       // a: A
case Failure(e)       // e: E
case class Panic(exception: Throwable)  // line 257
```

`Result.Panic` is `case class Panic(exception: Throwable) extends Error[Nothing]`. Extract the throwable via `case Result.Panic(t) =>` and access the message via `t.getMessage`.

### Structure.encode and Structure.decode

Source: `kyo-schema/shared/src/main/scala/kyo/Structure.scala` lines 44 and 57.

```scala
def encode[A](value: A)(using schema: Schema[A], frame: Frame): Structure.Value =
    schema.toStructureValue(value)

def decode[A](value: Structure.Value)(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
    schema.fromStructureValue(value)
```

`Structure.decode` returns a plain `Result[DecodeException, A]` (not a `< Abort[...]`). To lift the failure into `Abort[JsonRpcError]`, pattern-match explicitly:

```scala
Structure.decode[In](params) match
    case Result.Success(in)  => ... // proceed with decoded value
    case Result.Failure(e)   => Abort.fail(JsonRpcError.invalidParams(e.getMessage))
    case Result.Panic(t)     => Abort.fail(JsonRpcError.invalidParams(t.getMessage))
```

The `Panic` arm on decode handles edge cases in the schema machinery. Do NOT use `Abort.panic` here; use `Abort.fail(JsonRpcError.invalidParams(...))` per the spec.

### Existing JsonRpcMethod shape from kyo-ai-plugin

Source: `git show kyo-ai-plugin:kyo-http/shared/src/main/scala/kyo/JsonRpc.scala` lines 105-168.

The kyo-ai-plugin `JsonRpcMethod` uses `Json.Value` for params and does NOT have `HandlerCtx`. The Phase 2 version changes two things:

1. `handle` takes `(params: Structure.Value, ctx: HandlerCtx)` instead of `params: Json.Value` alone.
2. The trait gains `kind: JsonRpcMethod.Kind`.

The `(Async & Abort[JsonRpcError]) <:< S` evidence pattern is preserved verbatim:

```scala
// From kyo-ai-plugin line 141, preserved in Phase 2:
val ev = summon[(Async & Abort[JsonRpcError]) <:< S]

// Widening call in handle (kyo-ai-plugin line 163, adapted for Structure.Value):
ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(computation)
```

`liftContra` is a Scala 3 stdlib method on `<:<`. The project uses Scala 3.8.3 (`build.sbt` line 8), so it is available.

### Schema[A] summon

```scala
val capturedSchemaIn:  Schema[In]  = summon[Schema[In]]
val capturedSchemaOut: Schema[Out] = summon[Schema[Out]]
```

Capture at construction time so the handler closure does not re-summon at every invocation.

---

## 2. File:line Anchors

| Item | Location |
|---|---|
| `Fiber.Promise` opaque type definition | `kyo-core/shared/src/main/scala/kyo/Fiber.scala:440` |
| `Fiber.Promise.init[E, A]` | `kyo-core/shared/src/main/scala/kyo/Fiber.scala:449` |
| `Fiber.Promise.completeDiscard` | `kyo-core/shared/src/main/scala/kyo/Fiber.scala:478` |
| `Abort.fail` | `kyo-prelude/shared/src/main/scala/kyo/Abort.scala:57` |
| `Abort.panic` | `kyo-prelude/shared/src/main/scala/kyo/Abort.scala:67` |
| `Abort.run[E]` | `kyo-prelude/shared/src/main/scala/kyo/Abort.scala:242` |
| `Abort.catching[E]` | `kyo-prelude/shared/src/main/scala/kyo/Abort.scala:547` |
| `Result.Panic` case class | `kyo-data/shared/src/main/scala/kyo/Result.scala:257` |
| `Result.succeed / fail / panic` factories | `kyo-data/shared/src/main/scala/kyo/Result.scala:75-93` |
| `Structure.encode[A]` | `kyo-schema/shared/src/main/scala/kyo/Structure.scala:44` |
| `Structure.decode[A]` | `kyo-schema/shared/src/main/scala/kyo/Structure.scala:57` |
| `Sync.defer` | `kyo-core/shared/src/main/scala/kyo/Sync.scala:49` |
| `class Closed` | `kyo-core/shared/src/main/scala/kyo/Closed.scala:5` |
| `JsonRpcError` (Phase 1 produced) | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala` |
| `JsonRpcError.internalError` factory | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala:34` |

### kyo-ai-plugin anchors (all at `git show kyo-ai-plugin:kyo-http/shared/src/main/scala/kyo/JsonRpc.scala`)

| Item | Line |
|---|---|
| `sealed trait JsonRpcMethod[+S]` | 105 |
| `private[kyo] def handle(params: Json.Value)...` (old single-param signature) | 117 |
| `object JsonRpcMethod` | 121 |
| `def apply[In: Schema, Out: Schema, S]` | 133 |
| `val ev = summon[(Async & Abort[JsonRpcError]) <:< S]` | 141 |
| `private[kyo] def handle(params: Json.Value)(using fr: Frame)` impl | 149 |
| `ev.liftContra[...]apply(computation)` widening call | 163 |

**No `decodeOrFail` helper exists in kyo-ai-plugin.** The decode failure path is an inline `match` on `Structure.decode[In](params)` inside `handle`. Phase 2 reuses this pattern directly.

---

## 3. Edge Cases and Gotchas

### 3.1 Panic handling: message placement

`DESIGN.md §20 invariant 9` says: "Panic message in `error.data`, generic message in `error.message`."

The `JsonRpcError.internalError` factory signature (Phase 1 produced file, line 34):

```scala
def internalError(cause: String, data: Maybe[Structure.Value] = Absent)(using Frame): JsonRpcError =
    JsonRpcError(-32603, cause, data)
```

The `cause` argument becomes `error.message`. To satisfy invariant 9, the call in `handle` MUST be:

```scala
case Result.Panic(t) =>
    Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
```

NOT `JsonRpcError.internalError(t.getMessage, ...)` (which would leak the panic message into `error.message`).

**IMPORTANT**: `IMPLEMENTATION.md` line 150 contains a copy-error. It writes `JsonRpcError.internalError(t.getMessage, Present(Structure.encode(...)))`. This would set `error.message = t.getMessage`, which contradicts DESIGN.md §20 invariant 9. The correct call is `JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage)))`. Follow DESIGN.md; ignore the IMPLEMENTATION.md version of this argument.

The panic-wrapping pattern wraps only the handler call, after the decode succeeds:

```scala
Abort.run[JsonRpcError](handler(in, ctx)).map:
    case Result.Success(out) =>
        Structure.encode[Out](out)(using capturedSchemaOut, fr)
    case Result.Failure(e) =>
        Abort.fail(e)
    case Result.Panic(t) =>
        Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
```

Do NOT wrap the decode step in `Abort.run[JsonRpcError]`; the decode failure is handled by matching on `Result[DecodeException, _]` directly (Section 3.3 below).

### 3.2 The `<:< S` evidence widening

The evidence `ev: (Async & Abort[JsonRpcError]) <:< S` is captured at construction time. Inside `handle`, the computation has type `Structure.Value < (S & Abort[JsonRpcError])`. The widening call is:

```scala
ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(computation)
```

This works because `<` is contravariant in its effect row `S`. `ev` proves `(Async & Abort[JsonRpcError]) <:< S`, so by contravariance, `Structure.Value < (S & Abort[JsonRpcError])` is a subtype of `Structure.Value < ((Async & Abort[JsonRpcError]) & Abort[JsonRpcError])`, which simplifies to `Structure.Value < (Async & Abort[JsonRpcError])`. `liftContra` applies that substitution.

Both `apply` overloads use the same `ev` pattern and the same widening call. The no-ctx overload wraps its handler before capture:

```scala
// The no-ctx overload: impl wraps handler internally:
def apply[In: Schema, Out: Schema, S](name: String)(handler: In => Out < S)(
    using Frame, (Async & Abort[JsonRpcError]) <:< S
): JsonRpcMethod[S] =
    apply[In, Out, S](name)((in, _ctx) => handler(in))
```

This delegates to the ctx overload, ensuring identical behavior and a single implementation path.

### 3.3 Two `apply` overloads: internal delegation

The no-ctx overload adapts `In => Out < S` to `(In, HandlerCtx) => Out < S` by discarding `_ctx`, then delegates to the ctx overload. This means there is only one `handle` implementation (inside the ctx overload's anonymous class), and the no-ctx overload creates zero duplication.

```scala
// Ctx overload: primary impl, creates RequestMethod or anonymous class
def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, HandlerCtx) => Out < S)(
    using Frame, (Async & Abort[JsonRpcError]) <:< S
): JsonRpcMethod[S] = new JsonRpcMethod[S] { ... }

// No-ctx overload: delegates
def apply[In: Schema, Out: Schema, S](name: String)(handler: In => Out < S)(
    using frame: Frame, ev: (Async & Abort[JsonRpcError]) <:< S
): JsonRpcMethod[S] =
    apply[In, Out, S](name)((in, _ctx) => handler(in))
```

### 3.4 `notification` factory

`notification` returns `JsonRpcMethod[S]` with `kind = Kind.Notification`. The handler produces `Unit < S`. In `handle`, the return value is always `Structure.Value.Null`, regardless of what the handler returns:

```scala
def notification[In: Schema, S](name: String)(handler: (In, HandlerCtx) => Unit < S)(
    using Frame, (Async & Abort[JsonRpcError]) <:< S
): JsonRpcMethod[S] = new JsonRpcMethod[S]:
    val kind = Kind.Notification
    ...
    private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using fr: Frame): Structure.Value < S =
        Structure.decode[In](params) match
            case Result.Success(in) =>
                // run handler, discard result, return Null
                Abort.run[JsonRpcError](handler(in, ctx)).map:
                    case Result.Success(_) =>
                        (Structure.Value.Null: Structure.Value)
                    case Result.Failure(e) =>
                        Abort.fail(e)
                    case Result.Panic(t) =>
                        Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
            ...
```

The engine (Phase 4) will discard this `Structure.Value.Null` for notifications and never send a response. The `handle` contract still requires a `Structure.Value` return so the signature is uniform.

After applying widening via `ev.liftContra`, the return type is `Structure.Value < (Async & Abort[JsonRpcError])`.

### 3.5 `HandlerCtx` constructor is `private[kyo]`

Users cannot instantiate `HandlerCtx`. The engine (Phase 4) constructs it. Tests 24 and 25 need to construct one to verify behavior directly.

The recommended workaround: add a `private[kyo]` factory in `HandlerCtx`'s companion:

```scala
private[kyo] def forTest(
    cancelled:    Fiber.Promise[Unit, Sync],
    requestId:    Maybe[JsonRpcId],
    extras:       Maybe[Structure.Value],
    progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
): HandlerCtx
```

This is `private[kyo]`, so the test (also in `package kyo`) can call it. No change needed for production code; the real constructor is the same thing. Alternatively the test can call the primary constructor directly since tests are in `package kyo`.

### 3.6 `progressSink` no-op when `Absent`

`HandlerCtx.progress(value)` delegates to `progressSink`:

```scala
def progress(value: Structure.Value)(using Frame): Unit < (Async & Abort[Closed]) =
    progressSink.fold(())(_(value))
```

`progressSink.fold(ifEmpty)(ifPresent)`:
- `ifEmpty = ()`: returns the unit value `()`, which has type `Unit`. Inside `< (Async & Abort[Closed])`, this is a pure value and satisfies the return type without any effects.
- `ifPresent = sink => sink(value)`: calls the sink, which has type `Structure.Value => Unit < (Async & Abort[Closed])`.

`Maybe.fold` is defined as `def fold[B](ifEmpty: => B)(ifDefined: A => B): B`. Both branches must return `Unit < (Async & Abort[Closed])`. The `()` expression has type `Unit` which is a subtype of `Unit < (Async & Abort[Closed])` via Kyo's implicit widening.

### 3.7 `private[kyo]` on `handle`, `schemaIn`, `schemaOut`

All three members are `private[kyo]`. Tests in `package kyo` can access them for verification. The supervision plan in IMPLEMENTATION.md line 183 requires confirming this.

### 3.8 sbt project name

From STEERING.md (Findings from impl, Phase 0): `withoutSuffixFor(JVMPlatform)` means the JVM project is unsuffixed. Verification commands:

```
sbt 'kyo-jsonrpc/Test/compile'            # JVM (NOT kyo-jsonrpcJVM)
sbt 'kyo-jsonrpcJS/Test/compile'          # JS
sbt 'kyo-jsonrpcNative/Test/compile'      # Native
sbt 'kyo-jsonrpc/testOnly *JsonRpcMethodTest'   # targeted JVM test run
```

### 3.9 Phase 1 files already produced

The following files exist from Phase 1 and must NOT be modified:

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala`

`JsonRpcError` already has `internalError`, `invalidParams`, `invalidRequest`, `cancelled` factories available. Phase 2 code uses them without re-defining.

---

## 4. Test Data Suggestions

All 8 Phase 2 tests are pure computation. No fibers, no timers, no `Thread.sleep`. Use `run` to dispatch Sync/Async effects and assert directly.

Tests extend `kyo.Test` (defined at `kyo-jsonrpc/shared/src/test/scala/kyo/Test.scala`), which extends `AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest`. All tests live in `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcMethodTest.scala`.

### Test 19: handler returns Out, result is encoded

```scala
"handler returns Out and result is encoded as Structure.Value" in run {
    val method = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("greet") { (n, _) =>
        s"$n done"
    }
    val ctx    = HandlerCtx.forTest(???, Absent, Absent, Absent)  // see Section 3.5
    val params = Structure.encode[Int](42)
    Abort.run[JsonRpcError](method.handle(params, ctx)).map { result =>
        assert(result == Result.Success(Structure.Value.Str("42 done")))
    }
}
```

Expected: `Result.Success(Structure.Value.Str("42 done"))`.

### Test 20: handler Abort.fail propagates unchanged

```scala
"handler Abort.fail propagates the failure without transformation" in run {
    val method = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("fail") { (_, _) =>
        Abort.fail(JsonRpcError.InvalidParams)
    }
    Abort.run[JsonRpcError](method.handle(Structure.encode[Int](1), ctx)).map { result =>
        assert(result == Result.Failure(JsonRpcError.InvalidParams))
    }
}
```

Expected: `Result.Failure(JsonRpcError.InvalidParams)`. The error object is the exact same instance, not a wrapped or re-coded error.

### Test 21: panic becomes InternalError with message in data

```scala
"handler panic produces InternalError with panic message in data" in run {
    val method = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("boom") { (_, _) =>
        Sync.defer(throw new RuntimeException("boom"))
    }
    Abort.run[JsonRpcError](method.handle(Structure.encode[Int](1), ctx)).map { result =>
        result match
            case Result.Failure(err) =>
                assert(err.code == -32603)
                assert(err.message == "Internal error")
                assert(err.data == Present(Structure.Value.Str("boom")))
            case other =>
                fail(s"Expected Failure, got: $other")
    }
}
```

Key: `err.message == "Internal error"` (NOT `"boom"`). The panic message `"boom"` appears ONLY in `err.data`.

### Test 22: decode failure produces invalidParams before handler runs

```scala
"params decode failure produces invalidParams before the handler body runs" in run {
    var handlerCalled = false
    val method = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("typed") { (_, _) =>
        handlerCalled = true
        "ok"
    }
    val badParams = Structure.Value.Str("not an int")
    Abort.run[JsonRpcError](method.handle(badParams, ctx)).map { result =>
        result match
            case Result.Failure(err) =>
                assert(err.code == -32602)   // InvalidParams
                assert(!handlerCalled)
            case other =>
                fail(s"Expected Failure, got: $other")
    }
}
```

### Test 23: notification kind and handle returns Null

```scala
"notification method has Kind.Notification and handle returns Structure.Value.Null" in run {
    val method = JsonRpcMethod.notification[Int, Async & Abort[JsonRpcError]]("ping") { (_, _) => () }
    assert(method.kind == JsonRpcMethod.Kind.Notification)
    Abort.run[JsonRpcError](method.handle(Structure.encode[Int](1), ctx)).map { result =>
        assert(result == Result.Success(Structure.Value.Null))
    }
}
```

### Test 24: extras forwarded verbatim from ctx

```scala
"HandlerCtx.extras is forwarded verbatim to the handler" in run {
    val extrasValue = Structure.Value.Record(Chunk("k" -> Structure.Value.Str("v")))
    val ctxWithExtras = HandlerCtx.forTest(???, Absent, Present(extrasValue), Absent)
    var observed: Maybe[Structure.Value] = Absent
    val method = JsonRpcMethod[Int, Unit, Async & Abort[JsonRpcError]]("obs") { (_, ctx) =>
        observed = ctx.extras
    }
    Abort.run[JsonRpcError](method.handle(Structure.encode[Int](1), ctxWithExtras)).map { _ =>
        assert(observed == Present(extrasValue))
    }
}
```

### Test 25: ctx.progress with progressSink Absent is a no-op

```scala
"ctx.progress with progressSink = Absent is a no-op" in run {
    val ctxNoProgress = HandlerCtx.forTest(???, Absent, Absent, Absent)
    Abort.run[JsonRpcError](
        ctxNoProgress.progress(Structure.Value.Null).map(_ => ())
    ).map { result =>
        assert(result == Result.Success(()))
    }
}
```

The call must not throw, fail, or perform any effects. `Result.Success(())` confirms clean return.

Note: `ctx.progress` returns `Unit < (Async & Abort[Closed])`, not `< (Async & Abort[JsonRpcError])`. The outer `Abort.run[JsonRpcError]` does NOT discharge `Abort[Closed]`. Structure the test to only discharge `Abort[Closed]` or run both effects separately. Since `progressSink = Absent`, no `Abort[Closed]` effect fires; pure `Unit` value propagates up and both effects are vacuously satisfied.

### Test 26: no-ctx overload is equivalent to ctx overload when ctx is ignored

```scala
"no-ctx overload produces identical encoded output as ctx overload ignoring ctx" in run {
    def handler(n: Int): String < (Async & Abort[JsonRpcError]) = s"result $n"
    val withCtx   = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("m")((n, _) => handler(n))
    val withoutCtx = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("m")(handler)
    val params    = Structure.encode[Int](7)
    for
        r1 <- Abort.run[JsonRpcError](withCtx.handle(params, ctx))
        r2 <- Abort.run[JsonRpcError](withoutCtx.handle(params, ctx))
    yield assert(r1 == r2)
}
```

Expected: both produce `Result.Success(Structure.Value.Str("result 7"))`.

---

## 5. Anti-Flakiness Deltas

All Phase 2 tests are pure computation (Sync/Async without real fibers, no timers).

1. `Abort.run[JsonRpcError]` is the single discharge point. Tests use it on the `handle` call site and pattern-match the `Result[JsonRpcError, Structure.Value]` explicitly. No catch-all `case _ =>`.

2. Tests that verify panic use `Sync.defer(throw ...)` inside the handler to ensure the throw is deferred (not eager). Without `Sync.defer`, the `throw` happens at construction time in some Scala compilation paths.

3. `HandlerCtx.forTest` requires a `Fiber.Promise[Unit, Sync]`. Construct via `Fiber.Promise.init[Unit, Sync]` and pass the result. Tests 19-23 that don't inspect `ctx.cancelled` can share a single `lazy val ctx` at the test class level.

4. `ctx.progress` returns `Unit < (Async & Abort[Closed])`. Test 25 discharges `Abort[Closed]` separately since it is not a `JsonRpcError`. Use `Abort.run[Closed](ctx.progress(Structure.Value.Null))` then assert `Result.Success(())`.

5. Tests that use `var handlerCalled` (Test 22) mutate state from within a deferred computation. This is fine inside `Sync.defer` or pure computation but requires the `run` harness to actually execute the computation before the assertion.

6. `Structure.Value.Null` (the Scala singleton) vs `null` (Java null): use `Structure.Value.Null`, never bare `null`.

---

## 6. Concerns

### C1 (Critical): IMPLEMENTATION.md panic data argument contradicts DESIGN.md

`IMPLEMENTATION.md` line 150 says the panic path calls `JsonRpcError.internalError(t.getMessage, Present(Structure.encode(...)))`. This sets `error.message = t.getMessage`, which violates `DESIGN.md §20 invariant 9` ("generic message in `error.message`") and the explicit test description in IMPLEMENTATION.md line 168 ("panic message in `data`, not in `message`").

The correct call is `JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage)))`. Follow DESIGN.md; the IMPLEMENTATION.md line 150 is a copy-paste error from the kyo-ai-plugin (which used `internalError(t.getMessage)` without the separate `data` field).

Test 21 is the definitive spec: `err.message == "Internal error"` and `err.data == Present(Structure.Value.Str("boom"))`.

### C2 (Minor): `notification` has only one overload (with ctx)

`IMPLEMENTATION.md` line 163 defines only `notification[In, S](name)(handler: (In, HandlerCtx) => Unit < S)`. There is no no-ctx notification overload. This is consistent with DESIGN.md §5 which shows one `notification` factory. Do not add a second overload.

### C3 (Minor): `Abort.run[JsonRpcError]` on the handler inside `handle` doubles the effect discharge

`handle` already has return type `Structure.Value < S`, and `S` contains `Abort[JsonRpcError]`. Wrapping with `Abort.run[JsonRpcError]` inside `handle` catches the panic and turns it back into `Abort.fail(JsonRpcError.internalError(...))`. The `Abort.run` here does NOT consume the outer `Abort[JsonRpcError]` effect; it consumes only the inner one introduced by the handler's local `Abort[JsonRpcError]`. After widening via `ev.liftContra`, the final return type is `Structure.Value < (Async & Abort[JsonRpcError])`, as declared.

If the compiler complains about redundant `Abort[JsonRpcError]` layers, the computation type inside the `Abort.run` call must be ascribed carefully. The pattern from kyo-ai-plugin (adapted) works because `Abort.run[JsonRpcError]` is applied to `handler(in, ctx)` which has type `Out < S`, and `S` satisfies `Async & Abort[JsonRpcError] <:< S`.

### C4 (Informational): `Closed` type

`HandlerCtx.progressSink` has type `Maybe[Structure.Value => Unit < (Async & Abort[Closed])]`. `Closed` is defined at `kyo-core/shared/src/main/scala/kyo/Closed.scala:5` as a class (not an object). `Abort[Closed]` is used by `Channel`, `Meter`, and `Scope` throughout kyo-core. The import `kyo.Closed` resolves from `kyo-core` which is already a dependency of `kyo-jsonrpc`.

### C5 (Informational): `RequestMethod` and `NotificationMethod` private impl classes

IMPLEMENTATION.md line 150 says to use "internal `private` impl classes `RequestMethod` and `NotificationMethod`". These are named private classes inside `object JsonRpcMethod`, not anonymous `new JsonRpcMethod[S] { ... }` blocks. Either approach compiles; named private classes make stack traces cleaner. Use named classes per the spec.
