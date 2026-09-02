# Proto kernel test suites: internal-API audit

Read-only audit of every proto kernel test file against the rule: **a test may use an internal API
only when that internal is the test's own subject.**

Public surface, as defined for this audit: everything in `kyo.proto` and `kyo.proto.kernel` that is
not `private[kyo]` or `private[kernel]`. Reachable-by-contract for tests: `Eval.partial`,
`Eval.release`, and requesting a stop on the current thread (`Safepoint.stop` plus
`Safepoint.deadline`, as the `requestStop` helper does). Everything else in
`kyo.proto.kernel.internal` is internal.

Files read in full: 38 test files under `kyo-kernel/shared/src/test/scala/kyo/proto`,
`kyo-kernel/jvm-native/src/test/scala/kyo/proto`, `kyo-kernel/js-wasm/src/test/scala/kyo/proto`,
`kyo-kernel/jvm/src/test/scala/kyo/proto`, and
`kyo-kernel/shared/src/test/scala/outsidekyo/ProtoKernelTest.scala`.
`kyo-kernel/shared/src/test/scala/outsidekyo/KernelTest.scala` was skipped: it exercises the old
`kyo.kernel`, not the proto kernel.

Nothing was compiled or run. Every claim below is from reading the sources.

---

## 1. Summary table

`Subject` is the source the file's name pins. `Cases` counts `"..." in { }` blocks. `Flagged` counts
cases with at least one out-of-subject internal use. The private `eval` helper is excluded from
`Flagged` and reported separately in section 5.

| File | Subject | Cases | Flagged | Internals involved |
|---|---|---|---|---|
| `shared/.../kyo/proto/ArrowTest.scala` | `Arrow` | 33 | 2 | `Safepoint.get/save/enter/restore/period`, `evalNow` |
| `shared/.../kyo/proto/KyoTest.scala` | `Kyo` companion | 92 | 0 | none |
| `shared/.../kyo/proto/KyoForeachTest.scala` | fixtures only | 0 | 0 | none |
| `shared/.../kyo/proto/KyoForeachCollTest.scala` | `Kyo` companion | 48 | 0 | none |
| `shared/.../kyo/proto/LoopTest.scala` | `Loop` | 91 | 0 | none |
| `shared/.../kernel/ArrowEffectMaskTest.scala` | `ArrowEffect.Mask` | 23 | 1 | `Pending` |
| `shared/.../kernel/ArrowEffectTest.scala` | `ArrowEffect` | 184 | 14 | `Safepoint.period/get/enter/exit/save/restore`, `Kyo.Suspend`, `Pending`, `evalNow` |
| `shared/.../kernel/ContextEffectTest.scala` | `ContextEffect` | 23 | 0 | none |
| `shared/.../kernel/EffectBracketTest.scala` | `Effect.bracket` | 88 | 14 | `Kyo.Park`, `Pending`, `evalNow` |
| `shared/.../kernel/EffectTest.scala` | `Effect` | 12 | 5 | `Arrow.Transform`, `Kyo.Defer` |
| `shared/.../kernel/IsolateTest.scala` | `Isolate` | 51 | 1 | `Stack.Snapshot` |
| `shared/.../kernel/PendingTest.scala` | `Pending` / `<` | 92 | 15 | `Safepoint.period/get/save/enter/restore`, `evalNow` |
| `shared/.../internal/CanLiftTest.scala` | `CanLift` | 13 | 0 | none |
| `shared/.../internal/ContextTest.scala` | `Context` | 14 | 0 | `Context` (in-subject) |
| `shared/.../internal/DebuggerTest.scala` | `Debugger` | 7 | 1 | `Pending` |
| `shared/.../internal/EffectTraceTest.scala` | `EffectTrace` | 31 | 1 | `Kyo.SuspendArrow` |
| `shared/.../internal/EvalCaptureTowerTest.scala` | the evaluator | 3 | 2 | `Safepoint.period` |
| `shared/.../internal/EvalTest.scala` | the evaluator | 109 | 20 | `Kyo.Park`, `Pending`, `Nested.unnest`, `Eval.apply`, `Safepoint.State/get/save/restore/period`, `evalNow` |
| `shared/.../internal/HandlerTest.scala` | `Handler` (plumbing) | 16 | 16 | `Handler.*`, `Kyo.handle`, `Kyo.Suspend`, `Stack`, `Pending`, `Safepoint.get`, `EffectTrace` |
| `shared/.../internal/ImplicitsTest.scala` | `Implicits` | 19 | 0 | none |
| `shared/.../internal/NestedTest.scala` | `Nested` | 7 | 1 | `evalNow` |
| `shared/.../internal/StackTest.scala` | `Stack` | 35 | 0 | `Stack`, `Handler` (as `push` argument) |
| `jvm-native/.../kernel/ArrowEffectThreadingTest.scala` | `ArrowEffect` | 3 | 0 | dead import of `Eval` |
| `jvm-native/.../kernel/ContextEffectThreadingTest.scala` | `ContextEffect` | 3 | 3 | `evalNow` |
| `jvm-native/.../kernel/EffectThreadingTest.scala` | `Effect.bracket` | 2 | 2 | `evalNow` |
| `jvm-native/.../internal/EffectTracePhysicalTest.scala` | `EffectTrace` | 3 | 0 | `EffectTrace` (in-subject) |
| `jvm-native/.../internal/EffectTraceThreadingTest.scala` | `EffectTrace` | 1 | 0 | `EffectTrace` (in-subject) |
| `jvm-native/.../internal/EvalThreadingTest.scala` | the evaluator | 6 | 5 | `evalNow` |
| `jvm-native/.../internal/ReportTest.scala` | `Report` | 2 | 0 | `Report` (in-subject) |
| `jvm-native/.../internal/SafepointTest.scala` | `Safepoint` | 10 | 1 | `evalNow` |
| `jvm-native/.../internal/SafepointUnstartedThreadTest.scala` | `Safepoint` | 1 | 0 | `Safepoint` (in-subject) |
| `jvm-native/.../internal/StackThreadingTest.scala` | `Stack` | 1 | 0 | `Stack` (in-subject) |
| `js-wasm/.../internal/SafepointTest.scala` | `Safepoint` | 1 | 0 | `Safepoint` (in-subject) |
| `jvm/.../kernel/ArrowEffectBytecodeTest.scala` | `ArrowEffect` | 3 | 0 | none |
| `jvm/.../kernel/PendingBytecodeTest.scala` | `Pending` / `<` | 5 | 0 | none |
| `jvm/.../internal/EvalConcurrencyTest.scala` | the evaluator | 1 | 0 | helper only |
| `jvm/.../internal/SafepointConcurrencyTest.scala` | `Safepoint` | 10 | 2 | `evalNow` |
| `shared/.../outsidekyo/ProtoKernelTest.scala` | the kernel from outside | 67 | 0 | none, structurally |
| **Total** | | **1110** | **106** | |

`ProtoKernelTest` sits in package `outsidekyo`, so it cannot name a `private[kyo]` symbol at all. It
is the reference for what the kernel looks like when only the public surface is reachable, and it
covers 67 cases without once needing an internal.

---

## 2. Per-case rows

### `shared/src/test/scala/kyo/proto/ArrowTest.scala`

`Arrow.Transform` at lines 22-32 builds the `inc`/`double` fixtures. `Transform` is `private[kyo]`,
but it is a member of `Arrow`, which is this file's subject, so it is in-subject. Worth noting that
`Arrow.apply` already returns an `Arrow.Step`, which extends `Transform`, so `Arrow[Int](_ + 1)` is
the public spelling of the same fixture.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "defers when the budget is drained and still completes" (163) | `Safepoint.get/save/enter/restore`, `evalNow` | Applying an arrow with the budget exhausted produces a deferral rather than a value, and that deferral still evaluates to the answer. | **No public path.** Draining the budget is only expressible through `Safepoint`. Nothing in `kyo.proto` lets a test reach the budget. See section 3. |
| "stays budget-balanced across repeated direct calls" (176) | `Safepoint.period()`, `evalNow` | Repeated `f(v, cont)` calls do not leak budget: after `period * 3` calls the next one is still on the fast path. | **No public path** for the loop bound. `Safepoint.period()` is `private[kyo]` and `maxStackDepth` is `private[kernel]`. |

```scala
// ArrowTest.scala:163-174
"defers when the budget is drained and still completes" in {
    val f     = Arrow[Int](_ + 1)
    val slot  = Safepoint.get()
    val saved = Safepoint.save(slot)
    while Safepoint.enter(slot) do ()
    try
        val v = f(41, Arrow.id[Int])
        assert(v.evalNow.isEmpty)
        assert(v.eval == 42)
    finally Safepoint.restore(slot, saved)
```

```scala
// ArrowTest.scala:176-183
"stays budget-balanced across repeated direct calls" in {
    val f = Arrow[Int](_ + 1)
    var i = Safepoint.period() * 3
    while i > 0 do
        discard(f(1, Arrow.id[Int]))
        i -= 1
    assert(f(41, Arrow.id[Int]).evalNow == Maybe(42))
}
```

### `shared/src/test/scala/kyo/proto/kernel/ArrowEffectMaskTest.scala`

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a settled computation passes through mask and run untouched" (266) | `Pending` | Masking a settled value allocates no node. | Rewritable. The observable law is that no clause runs and the value survives: keep `assert(eval(runAsk(Mask.run[Ask](masked))(997)) == 42)` and add a `var clauseRuns = 0` counter in the `Mask.run` handler asserted to stay `0`. Drop the `isInstanceOf` line. |

```scala
// ArrowEffectMaskTest.scala:266-271
"a settled computation passes through mask and run untouched" in {
    val masked = Mask[Ask](42: Int < Ask)
    assert(!masked.isInstanceOf[kyo.proto.kernel.internal.Pending[?, ?]])

    assert(eval(runAsk(Mask.run[Ask](masked))(997)) == 42)
}
```

The two parking cases at lines 170 and 181 use only `requestStop` and `Eval.partial`, both inside
the granted contract. They are clean.

### `shared/src/test/scala/kyo/proto/kernel/ArrowEffectTest.scala`

`ArrowEffect.handleFirst`, `ArrowEffect.dispatchFirst` and `ArrowEffect.FirstSuspended` are all
`private[kyo]`, so they fall outside the public surface as defined, but they are members of
`ArrowEffect`, this file's subject. They are in-subject and not flagged. See section 3 for the
ruling this needs.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "stays in force across a budget bounce with a trailing transform" (510) | `Safepoint.period()` (via `Period`, line 31) | A region survives the budget rescue that fires in the middle of it. | **No public path** for the loop bound. |
| "the operation clause receives the operation reified at its own tag" (567) | `Kyo.Suspend` | `handleContOperation` hands the clause the operation carrying the tag it was suspended at, not the handler's tag. | **No public path.** The clause receives an `X < E`; reading its tag requires the node. The law could instead be pinned behaviorally: re-suspend the operation under a handler at the original tag and check it is answered there. That is a weaker but public test. |
| "a handler stepping a rescue at the exact budget boundary floats it outward" (1216) | `Safepoint.period()` | A rescue landing exactly on a handler boundary does not get trapped inside the region. | **No public path** for the boundary. |
| "settled inputs pass through strictly" (1240) | `evalNow` x6 | A settled input never builds a node: `handleCont`, `handleLoop`, `handleLoopState` and their no-`done` overloads all return the value directly. | Rewritable once `evalNow` is ruled on. Public alternative: assert the clause never ran (a counter stays `0`) and the result is `42` via `.eval`. |
| "a parked stateful region resumes with its state and done" (1317) | `evalNow` | A stop mid-region parks, and the resume carries the state into `done`. | Rewritable: the resumed value `12 * 1000 + (10 + 11) * 2` already proves the park happened and the state survived. Drop the `evalNow` assertion or gate it on the ruling. |
| "a settled input applies the done clause strictly" (1565) | `evalNow` | `handleFirst` on a settled input takes `done` without a node. | Same as above. |
| "a park taken mid answer loop resumes in a fresh full eval" (1852) | `evalNow` | The loop-handler fast path yields to a stop. | Rewritable: the resumed answer already carries the proof. |
| "a park taken mid answer loop resumes in a fresh full eval" (1941) | `evalNow` | Same law on the `handleCont` fast path. | Same. |
| "a stop arriving during eager construction inside a slice reifies and parks the chain" (2031) | `evalNow` | A stop during construction reifies the map chain rather than running it to completion. | Rewritable: `built >= 50 && built <= 52` already pins the park; the `evalNow` line is redundant. |
| "a recovery catches past the budget rescue" (2160) | `Safepoint.period()` | A recovery clause still sees a throw raised on the far side of a budget rescue. | **No public path** for the bound. |
| "a recovery catches past the budget inside a stateful region" (2165) | `Safepoint.period()` | Same, inside a stateful region. | **No public path.** |
| "a recovery guards a stateful region across a park" (2185) | `Pending` | A recovery installed outside a stateful region still catches after the region parks and resumes. | Rewritable: `assert(eval(parked) == "caught")` is the law; the `isInstanceOf[Pending]` line adds nothing a counter cannot say. |
| "a throwing release in a nested eval does not disarm the enclosing slice" (2242) | `Pending` | A throwing release inside a nested eval leaves the outer slice armed. | **Duplicate.** Byte-for-byte the same case as `jvm-native/.../SafepointTest.scala:122`, where the `Safepoint` internals are in-subject. Delete here. |
| "a throwing release on the completing path leaves the caller's safepoint state intact" (2272) | `Safepoint.get/enter/save/restore/exit` | A throwing release does not perturb the caller's budget. | **Duplicate.** Same case as `jvm-native/.../SafepointTest.scala:152`. Delete here. |

```scala
// ArrowEffectTest.scala:567-579
"the operation clause receives the operation reified at its own tag" in {
    var seen         = List.empty[String]
    val v: Int < Ask = ask.map(_ + 1)

    val r: Int < Any = ArrowEffect.handleContOperation(Tag[AskSub], v)(
        [X] =>
            (operation, _) =>
                val suspend = operation.asInstanceOf[kyo.proto.kernel.internal.Kyo.Suspend[?, ?, ?, ?]]
                seen = suspend.tag.show :: seen
                -1
```

Also: `import kyo.proto.kernel.internal.Nested` at line 9 is never used. Dead import.

### `shared/src/test/scala/kyo/proto/kernel/EffectBracketTest.scala`

Every flagged case here uses the same two shapes: an `isInstanceOf[Kyo.Park[?, ?]]` structural pin
after `Eval.partial`, or `.evalNow.isEmpty` for the same purpose. In both cases the surrounding
assertions already carry the law; the pin is redundant confirmation that a park happened.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "releases when a parked remainder is abandoned" (80) | `Kyo.Park` | Abandoning a parked bracket runs its release exactly once with the abandonment signal. | Rewritable: `assert(released == 0)` before the release and `assert(released == 1)` after already pin it. Drop line 89. |
| "a resumed parked bracket completes and releases with Absent" (95) | `Kyo.Park` | Resuming a parked bracket completes it and releases with `Absent`. | Rewritable: the `Absent` outcome and the resumed result carry the law. |
| "a stop landing as the acquire settles still installs the region" (121) | `Kyo.Park` | A stop arriving exactly at the acquire boundary still installs the finalizer region. | Rewritable: the release outcome after abandonment is the law. |
| "the acquire is not guarded before it settles" (165) | `Pending` | An acquire that has not settled owes no release. | Rewritable: `released == 0` after `Eval.release` is the law. |
| "a park after a crossing resume still owes the bracket" (205) | `Kyo.Park` | A park taken after a foreign crossing still carries the obligation. | Rewritable: the release count after abandonment is the law. |
| "a release failure on abandonment is suppressed onto the holder's signal" (327) | `Kyo.Park` | A throwing release on abandonment attaches to the signal, not the computation. | Rewritable: `signal.getSuppressed` is the law. |
| "a bracket held across two parks releases once on completion" (375) | `Kyo.Park` x2 | Two consecutive parks do not duplicate the release. | Rewritable: `released == 1` is the law. The two pins only assert that both `Eval.partial` calls parked, which the surrounding side effects already show. |
| "two stacked brackets abandoned together release innermost first" (468) | `Kyo.Park` | Ordering of stacked releases on abandonment. | Rewritable: the recorded order is the law. |
| "abandoning an isolated child releases its bracket" (548) | `Kyo.Park` | A bracket owned by an isolated child releases on abandonment. | Rewritable: the release count is the law. |
| "a slice stopped inside a bracket releases once, at the end" (684) | `evalNow` (lines 698, 703) | Repeated `Eval.partial` steps of a stopped slice release exactly once, at the end. | Blocked on the `evalNow` ruling: `evalNow` is the loop condition of the driver at 698-703, so a rewrite needs a public way to ask "did this settle". |
| "a use that fails after a resume still releases once with the failure" (729) | `evalNow` | A failure after a resume still releases once, with the failure. | Rewritable: the release outcome is the law. |
| "a park inside nested brackets carries both releases" (751) | `evalNow` | Both releases survive a park. | Rewritable: the release order and count are the law. |
| "a park evaluated twice refuses the second evaluation after release" (769) | `evalNow` | A parked value is single-use after its release. | Rewritable: the refusal is the law. |
| "a release that throws during abandonment does not silence the others" (896) | `evalNow` | A throwing release does not stop the remaining ones. | Rewritable: the recorded log is the law. |

```scala
// EffectBracketTest.scala:684-710, the one case whose driver is built on evalNow
"a slice stopped inside a bracket releases once, at the end" in {
    ...
            @tailrec def run(v: Int < Any, steps: Int): Int =
                v.evalNow match
                    case Present(x) => x
                    case Absent =>
                        run(Eval.partial(v).asInstanceOf[Int < Any], steps + 1)
```

### `shared/src/test/scala/kyo/proto/kernel/EffectTest.scala`

`Effect.deferInline` at line 62 is `private[kyo]` but is a member of `Effect`, this file's subject.
In-subject, not flagged.

The `inc` and `double` fixtures at lines 27-35 are built as `Arrow.Transform`, which is
`private[kyo]` and belongs to `Arrow`, not to `Effect`. That is an out-of-subject internal, and it
is mechanical to remove: `Arrow[Int](_ + 1)` and `Arrow[Int](_ * 2)` are the public spellings and
produce an `Arrow.Step`, which is a `Transform`.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "runs the value into its continuation" (113) | `Arrow.Transform` | `Effect.defer(v, cont)` applies the continuation to the value. | Rewritable: `Arrow[Int](_ + 1)`. |
| "defers a pending value" (117) | `Arrow.Transform` | The two-argument `defer` waits for a pending value. | Rewritable: same. |
| "runs both continuations in order" (121) | `Arrow.Transform` | The three-argument `defer` composes in order. | Rewritable: same. |
| "collapses an identity second continuation into the one-continuation node" (126) | `Arrow.Transform`, `Kyo.Defer` | `defer(v, cont, Arrow.id)` collapses to the one-continuation node. | **Pins plumbing.** The collapse is an allocation detail with no observable consequence; the value is `2` either way. Either delete the case or keep the `Kyo.Defer` pin and accept it as a deliberate structural test, marked as such. |
| "the four-argument form runs its three continuations in order" (134) | `Arrow.Transform` | The four-argument `defer` composes in order. | Rewritable: `Arrow[Int](...)`. |

```scala
// EffectTest.scala:126-132
"collapses an identity second continuation into the one-continuation node" in {
    val node = Effect.defer(1: Int < Any, inc, Arrow.id[Int])
    node match
        case d: Kyo.Defer[?, ?, ?, ?] => assert(d.contB eq Arrow.id[Int])
        case other                    => fail(s"expected a deferral node, got $other")
    assert(eval(node) == 2)
}
```

### `shared/src/test/scala/kyo/proto/kernel/IsolateTest.scala`

`Isolate.internal.Contextual` is `private[kernel]` inside `private[kyo] object internal`, but
`internal` is a member of `Isolate`, this file's subject. In-subject, not flagged.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a region exited before the merge is not joined" (480) | `Stack.Snapshot` | A context region that exited before the restore is not joined. | Rewritable. The annotation names the shape of `Contextual.Transform[Int]`. Write `val captured: contextual.Transform[Int] < Any = ...` and the `Stack` import disappears. |

```scala
// IsolateTest.scala:480-493
"a region exited before the merge is not joined" in {
    var joins = 0
    val captured: (Stack.Snapshot, Stack.Snapshot, Int) < Any =
        ContextEffect.handle(Tag[Bind])(...)(contextual.capture(st => contextual.isolate(st, read)))
```

### `shared/src/test/scala/kyo/proto/kernel/PendingTest.scala`

Dead imports: `kyo.proto.kernel.internal.Eval` (line 9), `.Nested` (line 10), `.Pending` (line 11).
None of the three names appears anywhere else in the file.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "evalNow builds its receiver once" (164) | `evalNow` | `evalNow` on a settled map runs the body exactly once. | **The case is about `evalNow`.** No public twin exists; it stands or falls with the ruling in section 3. |
| "evalNow returns a settled value" (369) | `evalNow` | `evalNow` is present for a settled value. | Same. |
| "evalNow is absent for a suspended computation" (373) | `evalNow` | `evalNow` is absent for a suspension. | Same. |
| "evalNow returns a payload unwrapped" (377) | `evalNow` | `evalNow` unwraps a boxed payload. | Same. |
| "the extension surface applies to a val of nested type" (578) | `evalNow` | The whole `<` extension surface works on a `val` of nested type. | Rewritable: drop line 583; the other five assertions in the case cover the surface. |
| "construction past the safepoint budget rescues instead of overflowing" (608) | `Safepoint.period()` | Building a map chain longer than the budget rescues instead of overflowing. | **No public path** for the bound. A hard-coded number larger than any plausible budget would work but silently decouples from the flag. |
| "a long map tower on a rescued computation evaluates in bounded stack" (615) | `Safepoint.period()` | A rescued computation still evaluates a million-deep tower. | **No public path.** |
| "map over a nested value denied by the budget defers the wrapped value" (695) | `Safepoint.get/save/enter/restore` (via `drainedBudget`, 687-693) | With the budget drained, `map` on a nested value defers rather than unwrapping. | **No public path.** Draining the budget requires `Safepoint`. |
| "flatMap over a nested value denied by the budget defers the wrapped value" (701) | same | Same for `flatMap`. | **No public path.** |
| "flatten over a doubly nested value denied by the budget strips exactly one level" (707) | same | Same for `flatten`. | **No public path.** |
| "andThen over a nested value denied by the budget discards it unevaluated" (715) | same | Same for `andThen`. | **No public path.** |
| "unit over a nested value denied by the budget discards it unevaluated" (721) | same | Same for `unit`. | **No public path.** |
| "a doubly nested value denied by the budget strips exactly one level under map" (727) | same | Same, doubly nested. | **No public path.** |
| "evalNow accepts nested computations" (873) | `evalNow` | `evalNow` on a nested computation yields the inner computation. | **The case is about `evalNow`.** Ruling-dependent. |
| "depth leaked by throwing maps resets at the eval loop" (888) | `Safepoint.period()` | Throwing maps do not permanently leak budget depth. | **No public path** for the bound. |

```scala
// PendingTest.scala:687-693, the drainedBudget helper six cases share
def drainedBudget[A](f: => A): A =
    val slot  = Safepoint.get()
    val saved = Safepoint.save(slot)
    while Safepoint.enter(slot) do ()
    try f
    finally Safepoint.restore(slot, saved)
end drainedBudget
```

### `shared/src/test/scala/kyo/proto/kernel/internal/DebuggerTest.scala`

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a session leaves a park and resume's result untouched" (93) | `Pending` | An installed debugger changes nothing about a park and its resume. | Rewritable: `assert(eval(p) == 42)` is the law; the `isInstanceOf[Pending]` line adds only that the stop landed, which the `requestStop` call already arranged. |

```scala
// DebuggerTest.scala:93-105
"a session leaves a park and resume's result untouched" in {
    ...
        val p = Eval.partial(v)
        assert(p.isInstanceOf[Pending[?, ?]])
        assert(eval(p) == 42)
```

### `shared/src/test/scala/kyo/proto/kernel/internal/EffectTraceTest.scala`

`EffectTrace`, `carrier`, `.elements` and `.dropped` are all in-subject.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a failure of the walk itself leaves the original failure travelling" (348) | `Kyo.SuspendArrow` | If reading a node's frame throws during the trace walk, the walk gives up and the original failure travels untouched. | **No public path.** The case needs a node whose `frame` accessor throws. Nothing on the public surface can build one; `ArrowEffect.suspend` always supplies a real `Frame`. This is a legitimate internal test of `EffectTrace`'s own failure containment and reads as in-subject in spirit, since the node is only a prop for the `EffectTrace` behavior under test. Recommend keeping it and recording it as a deliberate exception. |

```scala
// EffectTraceTest.scala:348-357
"a failure of the walk itself leaves the original failure travelling" in {
    val unreadable =
        new Kyo.SuspendArrow[Const[Unit], Const[Int], Ask, Any, Int, Any]:
            def tag            = Tag[Ask]
            def input          = ()
            override def frame = throw new IllegalStateException("frame read failed")
            def cont           = Arrow.id[Int]
    val ex = intercept[Throwable](eval(unreadable.asInstanceOf[Int < Any]))
    assert(carrier(ex).toList.flatMap(_.elements.toList).isEmpty)
}
```

### `shared/src/test/scala/kyo/proto/kernel/internal/EvalCaptureTowerTest.scala`

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a capture across an inner region keeps the region as an entry" (29) | `Safepoint.period()` (via `Reach`, line 20) | A capture taken at half the budget still records the inner region as an entry. | **No public path.** The whole point is to sit at a fraction of the budget. |
| "a deep capture is multi-shot" (46) | `Safepoint.period()` | A capture at half the budget is still multi-shot. | **No public path.** |

Case at line 22 uses a hard-coded `20000` and is clean.

### `shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala`

`Eval.partial` and `Eval.release` are contract-reachable and not flagged; `requestStop` at 542-546 is
likewise granted.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "an eval inside a map evaluates its argument rather than nesting it" (90) | `Eval.apply` | A nested eval inside a map evaluates rather than nesting, and an unhandled suspension inside it is a bug. | Rewritable: the value is `Int < Any`, so `ask.asInstanceOf[Int < Any].eval` is the public spelling. |
| "parks on a pending stop and the parked value resumes to the same answer" (549) | `Kyo.Park`, `.entries.regions` | A stop parks the region, the park carries exactly one region, and resuming twice gives the same answer. | **Partly no public path.** `assert(!afterRan)` and the two `eval(parked) == 42` assertions are the law and stand alone. `entries.regions == 1` has no public equivalent: the region count of a park is not reachable. |
| "a stop already pending returns the input before the slice starts" (569) | `Nested.unnest`, `Kyo.Park`-adjacent identity | A stop pending before the slice returns the input untouched. | Rewritable: `back eq input` is the law; `Nested.unnest[Int](Eval.partial(back))` can be `Eval.partial(back).eval` since the row is `Any`. |
| "a stateful region parked mid-loop resumes at the parked state" (584) | `Kyo.Park`, `.entries.state(0)` | A stateful region parks carrying its in-flight state. | **Partly no public path.** `eval(parked) == 12` is the law and stands alone. `entries.state(0) == 2` reads the parked state directly and has no public equivalent. |
| "a resumed region restores its parked binding" (602) | `Kyo.Park` | A resumed park restores the binding it captured, not the one at the resume site. | Rewritable: `eval(resumed) == (11, 11)` under an enclosing handler bound to `100` is the law. Drop line 613. |
| "a nested eval inside a slice runs unarmed and completes despite the pending stop" (619) | `Eval.apply`, `Nested.unnest`, `Pending`, `Kyo.Park` (negative) | A nested eval inside an armed slice runs unarmed and completes. | Rewritable: `nested == 41` plus `eval(parked) == 42` is the law. The `!isInstanceOf[Kyo.Park]` line pins that the outer slice parked without regions, which is a structural detail; drop it or replace with a public observation. `Nested.unnest[Int](Eval(...))` becomes `.eval` since the row is `Any`. |
| "a throw during a slice still consumes the stop at the boundary" (635) | `Pending`, `Nested.unnest` | A throw inside a slice still consumes the pending stop, so the next slice runs. | Rewritable: `ran == true` after the next `Eval.partial` is the law. |
| "a parked value owes its regions' releases innermost first" (655) | `Kyo.Park`, `.entries.regions` | Releases owed by a park run innermost first. | **Partly no public path.** `log == List("inner", "outer")` is the law. `entries.regions == 2` has no public equivalent. |
| "abandoning a park with no outstanding releases is a no-op" (714) | `Pending` | Releasing a park that owes nothing changes nothing. | Rewritable: `eval(p) == 42` after `Eval.release` is the law. |
| "a context binding owes its release through the public surface" (735) | `Kyo.Park` | A context binding held by a park owes its release. | Rewritable: `log == List(7)` is the law. The title already claims the public surface; the `Kyo.Park` pin contradicts it. |
| "a throwing release does not starve the ones after it" (793) | `Kyo.Park` | A throwing release does not stop the ones after it. | Rewritable: the log and `cause.getSuppressed` are the law. |
| "a release rethrowing the signal itself does not self-suppress" (826) | `Kyo.Park` | Rethrowing the signal from a release does not self-suppress. | Rewritable: `cause.getSuppressed.isEmpty` is the law. |
| "chain onto a parked value composes" (858) | `Kyo.Park` | Mapping over a parked value composes with the resume. | Rewritable: `eval(parked.map(_ * 10)) == 420` is the law. |
| "a nested eval shares the thread's stack and sees none of the outer regions" (875) | `Eval.apply` | A nested eval does not see the outer regions. | Rewritable: `Eval[Int, Any](x)` where `x: Int < Any` is `x.eval`. |
| "a region recovering across a foreign crossing leaves the budget where it found it" (935) | `Safepoint.State/get/save/restore` | Recovery across a crossing is budget-neutral. | **No public path.** Sampling the budget requires `Safepoint`. |
| "evalNow is present only for settled values" (1127) | `evalNow` | `evalNow` discriminates settled from suspended. | **The case is about `evalNow`.** Ruling-dependent, and it duplicates `PendingTest:369`/`:373`. |
| "a throw escaping a root eval leaves the safepoint depth unchanged" (1381) | `Safepoint.get/save/restore` | Fifty throws through a root eval leave the depth where it started. | **No public path.** |
| "the budget rescues rather than overflowing" (1400) | `Safepoint.period()` | A chain four times the budget rescues instead of overflowing. | **No public path** for the bound. Duplicates `PendingTest:608`. |
| "partial completes when nothing stops" (1408) | `evalNow` | `Eval.partial` returns the finished value when nothing stops. | Ruling-dependent. Alternative: `Eval.partial(...).eval == 42` plus a counter proving the handler ran once. |
| "a computation held as a value passes through a parked slice intact" (1412) | `evalNow` | A payload crosses a park by identity. | Rewritable: `out eq payload` is the law. |

```scala
// EvalTest.scala:559-566, the shape repeated across ten cases
val parked = Eval.partial(answerAsk(21)(body))
assert(parked.isInstanceOf[Kyo.Park[?, ?]])
assert(!afterRan)
assert(parked.asInstanceOf[Kyo.Park[?, ?]].entries.regions == 1)
assert(eval(parked) == 42)
```

### `shared/src/test/scala/kyo/proto/kernel/internal/HandlerTest.scala`

Per the brief, `Handler` is plumbing with no public existence, so every case here needs a
public-surface twin or a plumbing verdict. All sixteen are flagged. The recurring finding is that a
public twin already exists elsewhere in the suite, which makes most of these cases duplicates rather
than new coverage.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "applies to a settled region result" (73) | `Handler.ContHandler.done` | `done` transforms the region result. | Rewritable: `ArrowEffect.handleCont(tag, 41: Int < Ask)([C] => (_, c) => c(0), _ + 1)` and assert `42`. Already covered by `ArrowEffectTest:463`. |
| "sees the state it is given" (77) | `Handler.LoopHandler.done` | `done` sees the final state. | Rewritable: `handleLoopState(tag, 30, settled)(clause, (s, a) => s + a)`. Covered by `ArrowEffectTest:618`. |
| "recover declines by default" (82) | `Handler.ArrowHandler.recover` | A handler with no recovery clause declines and the failure unwinds. | Rewritable: use the `handleCont` overload without `recover` and assert the throw escapes. Covered by `ArrowEffectTest:1007`. |
| "answers through the eval and completes with done" (88) | `Kyo.handle`, `Handler.ContHandler` | A region answers its operations and applies `done`. | Rewritable: this is exactly `ArrowEffect.handleCont`. Duplicate. |
| "threads the loop handler's state through every answer" (93) | `Kyo.handle`, `Handler.LoopHandler` | A loop handler's state threads across answers. | Rewritable: `ArrowEffect.handleLoopState`. Duplicate of `ArrowEffectTest:609`. |
| "ends with the value a loop clause answers done with" (99) | `Kyo.handle`, `Handler.LoopHandler` | `Loop.done` in a clause ends the region and bypasses `done`. | Rewritable. Duplicate of `ArrowEffectTest:627`. |
| "a settled body skips the handler except for done" (104) | `Kyo.handle` | A settled body reaches `done` without the clause running. | Rewritable. Duplicate of `ArrowEffectTest:451`. |
| "threads a settled answer through the continuation" (118) | `Handler.LoopHandler.answers`, `Safepoint.get` | The fast answer loop applies the continuation to a settled answer and commits the new state. | **Plumbing.** `answers` takes an armed flag and a `Safepoint.Slot`; nothing public can call it. The observable law is covered at coarser granularity by `ArrowEffectTest:1772` ("the answer fast path"). Verdict: delete, or keep as an explicitly-marked plumbing test. |
| "maps the continuation over a pending answer" (126) | `Handler.LoopHandler.answers`, `Pending` | A pending answer is mapped rather than applied eagerly. | **Plumbing.** Same reasoning. Public twin: `ArrowEffectTest:193` ("a clause answers effectfully"). |
| "a throwing clause keeps the state it reached and rethrows at the deferred step" (137) | `Handler.LoopHandler.answers` | A clause throw commits the state it reached and defers the rethrow. | **Plumbing** at this granularity. Public twin: `ArrowEffectTest:1831` ("a throw after settled answers recovers with every commit already made"). |
| "a failing continuation application reports the state after the answer" (145) | `Handler.LoopHandler.answers` | A throw in the continuation reports the post-answer state. | **Plumbing.** Public twin: `ArrowEffectTest:1920`. |
| "a cont handler's failure carries its continuation" (155) | `Handler.ContHandler.answering`, `Stack.borrow/release`, `Pending`, `EffectTrace` | A throw in a cont clause attaches a trace naming the clause's frames. | Rewritable: this is exactly what `EffectTraceTest:194` ("a throw in a handler clause carries the suspension and its region") already pins through `ArrowEffect.handleCont`. Duplicate. |
| "a loop handler's failure carries the suspension" (167) | `Handler.LoopHandler.running`, `Stack`, `Kyo.Suspend` | A throw in a loop clause attaches a trace naming the suspension. | Rewritable: `EffectTracePhysicalTest:38` pins the same through `ArrowEffect.handleLoop`. Duplicate. |
| "delivers a done outcome as the region's value" (182) | `Handler.LoopHandler.clauseDispatch` | A `Loop.done` outcome becomes the region's value. | Rewritable: `handleLoop` with a `Loop.done` clause. Duplicate of `ArrowEffectTest:53`. |
| "re-enters the region on a continue outcome" (187) | `clauseDispatch` | A `Loop.continue` outcome re-enters the region. | Rewritable: `handleLoop` with a clause that answers and a body that suspends again. Duplicate of `ArrowEffectTest:46`. |
| "defers a pending outcome and dispatches it once settled" (193) | `clauseDispatch` | A pending outcome defers and dispatches once settled. | Rewritable: `EvalTest:1157` ("a deferred clause outcome resolves before the region continues") pins this publicly. Duplicate. |

```scala
// HandlerTest.scala:118-124, the shape no public caller can reach
"threads a settled answer through the continuation" in {
    val k   = Arrow[Any]((x: Any) => x.asInstanceOf[Int] + 1)
    val out = statefulHandler.answers(5, (), k, armed = false, Safepoint.get(), Frame.internal)
    val c   = continued(out)
    assert(c._1 == 6)
    assert(c._2.asInstanceOf[Int] == 6)
}
```

```scala
// HandlerTest.scala:155-165
"a cont handler's failure carries its continuation" in {
    val stack = Stack.borrow()
    try
        val cont = Arrow[Int](_ + 1)
        val ex   = intercept[Boom](throwingContHandler.answering((), cont, node, stack))
```

### `shared/src/test/scala/kyo/proto/kernel/internal/NestedTest.scala`

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "passes a settled value through unwrapped" (22) | `evalNow` | `Nested.nest` of a settled value does not box. | Rewritable: `assert(v.eval == 42)` alongside `assert(!boxed(v))`, matching the sibling case at line 28 which already uses `.eval`. The inconsistency between the two adjacent cases is the tell. |

### `shared/src/test/scala/kyo/proto/kernel/internal/StackTest.scala`

No out-of-subject use. `Handler.LoopHandler` and `Handler.ContextHandler` fixtures at lines 24-53
are constructed because `Stack.push(handler, state, cont)` demands a `Handler` argument. That makes
`Handler` part of `Stack`'s own signature, so the fixtures are in-subject by necessity.

### `jvm-native/.../kernel/ContextEffectThreadingTest.scala`

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a parked computation resumes with its binding" (17) | `evalNow` | A park resumes carrying the binding it captured. | Rewritable: `eval(parked) == 42` is the law. |
| "a park holding a binding resumes on another thread with the binding it captured" (29) | `evalNow` (line 37, inside the `parked` helper) | The captured binding wins over the resume-site binding, across threads. | Rewritable: `enclosed == 11` under an enclosing binding of `100` is the law. |
| "concurrent resumes under different enclosures each keep the captured binding" (52) | `evalNow` | Same, concurrently, under two different enclosures. | Rewritable: `a == 11 && b == 11` is the law. |

### `jvm-native/.../kernel/EffectThreadingTest.scala`

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a cross-thread stop parks inside a bracket and abandonment releases" (14) | `evalNow` | A cross-thread stop parks inside a bracket; abandoning releases exactly once. | Rewritable: `sawUnreleased` is built from `released == 0 && p.evalNow.isEmpty`. The `released == 0` half alone is the law; `released == 1` at the end completes it. |
| "an abandonment racing a resume releases exactly once" (38) | `evalNow` | A resume racing an abandonment releases exactly once. | Rewritable: `released.get == 1` after 200 rounds is the law. |

### `jvm-native/.../internal/EvalThreadingTest.scala`

All five flagged cases use `.evalNow` as a "did the slice park" probe and nothing else internal.
`Safepoint.stop` and `Eval.partial` are both granted.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a preemption stop reifies and resumes with handler state" (57) | `evalNow` | A stop mid-loop reifies; resuming completes with the handler state intact. | Rewritable: `clauseRuns` bounds plus the final `100` are the law. |
| "a stop delivered between slices short-circuits" (77) | `evalNow` | A stop between slices returns the input by identity. | Rewritable: `r eq v` is the law. |
| "a cross-thread stop parks a running slice" (85) | `evalNow` | A cross-thread stop parks a running slice. | Ruling-dependent: `parked` is defined as `...evalNow.isEmpty` and is the only assertion. Needs a public "did this settle" or a rewrite to observe the park through a side effect. |
| "a cross-thread stop parks a settled spin with no suspensions" (104) | `evalNow` | Same for a spin with no suspensions. | Same. |
| "a stop the slice outruns is consumed at the boundary" (123) | `evalNow` | A stop the slice outruns is consumed at the boundary, not carried forward. | Rewritable: `Eval.partial(v).eval == 42` plus a counter proving the body ran once. |

### `jvm-native/.../internal/SafepointTest.scala`

`Safepoint` is the subject, so `stop`, `stopped`, `consumeStopped`, `beginSlice`, `endSlice`, `get`,
`enter`, `exit`, `save`, `restore`, `reset` and `period` are all in-subject here.

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "a stop addressed to a departed slice does not short-circuit the next evaluation" (91) | `evalNow` | A stop addressed to a slice that has ended does not affect the next evaluation. | Rewritable: `steps == 100` is the law. |

The two cases at 122 and 152 are the originals of the pair duplicated into
`ArrowEffectTest:2242`/`:2272`. Here the `Safepoint` state reads are in-subject; there they are not.

### `jvm/.../internal/SafepointConcurrencyTest.scala`

| Case (line) | Internal | Law pinned | Rewrite |
|---|---|---|---|
| "an evaluation yields to a stop requested from another thread" (82) | `evalNow` | An evaluation yields to a cross-thread stop within bounded attempts. | Rewritable: `yielded` is set from `out.evalNow.isEmpty`; a side-effect probe inside `burn` would serve. |
| "a stop delivered during a fast answer loop ends the slice" (109) | `evalNow` | A stop during the fast answer loop ends the slice in most of 50 rounds. | Same shape. |

Separately, line 14 hard-codes `private val Period = 512`, duplicating `maxStackDepth` rather than
reading `Safepoint.period()` the way the sibling `jvm-native` `SafepointTest` does at line 13. Line
208 asserts `remaining(i) == Period - 3`, which silently depends on that duplicate. Hygiene, not a
rule violation, but it will drift if the flag default changes.

---

## 3. Internals with no public equivalent

These are the internals the flagged cases reach for that nothing in `kyo.proto` or
`kyo.proto.kernel` can express. Each needs an owner decision: expose something, or accept the case
as a deliberate plumbing test.

**1. The safepoint budget (`Safepoint.period()`, `Safepoint.get/save/enter/exit/restore`, `Safepoint.State`).**
Twenty-one cases across seven files. Two distinct needs:

- *Sizing a loop to cross the budget.* `Safepoint.period()` is `private[kyo]`;
  `maxStackDepth = 512` in `internal/package.scala` is `private[kernel]`. The only reachable path is
  the string-keyed lookup `kyo.Flag.get("kyo.proto.kernel.internal.Safepoint.period")`, which
  `js-wasm/.../SafepointTest.scala:10` uses. That is a public API on `kyo.Flag`, but it still names
  the internal path as a string and returns an untyped flag, so it is not a real answer.
  Cases: `ArrowTest:176`, `PendingTest:608,615,888`, `ArrowEffectTest:510,1216,2160,2165`,
  `EvalCaptureTowerTest:29,46`, `EvalTest:1400`.
- *Draining or measuring the budget.* `PendingTest`'s `drainedBudget` helper and
  `ArrowTest:163` need the budget exhausted to force the deferral path;
  `EvalTest:935,1381` and `ArrowEffectTest:2272` need to read the depth to assert balance.
  There is no public equivalent of any kind.

**2. Park structure (`Kyo.Park.entries.regions`, `Kyo.Park.entries.state(i)`).**
Three cases: `EvalTest:549,584,655`. The number of regions a park carries and the state each one
holds are not observable from outside. The other assertions in those cases carry the behavioral law,
so these lines are pure structure. Note that the twenty-plus `isInstanceOf[Kyo.Park[?, ?]]` pins are
a *different* thing: they only ask "did this park", which `evalNow` or a side-effect probe answers.

**3. `Handler` in every form.**
`Handler.ContHandler`, `Handler.LoopHandler`, `Handler.ContextHandler`, and the methods `answers`,
`answering`, `running`, `clauseDispatch`, `done`, `recover`, plus `Kyo.handle`. `Handler` is
`private[kernel]` and has no public constructor: a user reaches it only by calling
`ArrowEffect.handle*` or `ContextEffect.handle*`. All sixteen `HandlerTest` cases live here. Eleven
of them have an exact public twin already in the suite (see the table in section 2); four
(`answers` at lines 118, 126, 137, 145) are only reachable at a coarser granularity through
`ArrowEffectTest`'s "the answer fast path" block; one (`clauseDispatch` at 193) is covered by
`EvalTest:1157`.

**4. `Kyo.SuspendArrow` with a throwing `frame`.**
`EffectTraceTest:348`. Building a node whose `frame` accessor throws is the only way to exercise
`EffectTrace`'s own containment of a failing walk. No public constructor can produce one.

**5. `Kyo.Suspend.tag` from inside a `handleContOperation` clause.**
`ArrowEffectTest:567`. The clause receives an `X < E`; the tag it was suspended at is not exposed.

**6. `Kyo.Defer.contB`.**
`EffectTest:126`. The node-collapse optimization has no observable consequence.

**7. `evalNow`, the open question.**
`private[kyo] inline def evalNow: Maybe[A]` in `kernel/Pending.scala:251-256`. It is not in the
public surface as defined, and it is not in the scheduler contract the brief named, yet it is the
tool thirty-four cases across ten files use to ask "did this settle or park". `Eval.partial` returns
`A < Any`; without `evalNow` a test can only find out by evaluating, which destroys the thing it
wants to inspect, or by a side-effect probe.

Given that `evalNow` is `private[kyo]` for the same reason `Eval.partial` is (kyo-core reaches it),
the coherent options are:

- Rule `evalNow` into the scheduler contract alongside `Eval.partial` and `Eval.release`, which
  clears thirty-four cases at once and leaves them as written.
- Keep it internal, in which case those cases need a public probe. Most of them already assert the
  real law elsewhere in the same case and can simply drop the `evalNow` line; the exceptions are
  `EvalThreadingTest:85,104`, `SafepointConcurrencyTest:82,109`,
  `EffectBracketTest:684` and the four `PendingTest` cases whose subject *is* `evalNow`.

**8. `private[kyo]` members of public objects, used inside their own suite.**
Not violations under the subject rule, but they sit outside the public surface as defined, so the
owner may want an explicit ruling: `ArrowEffect.handleFirst`, `ArrowEffect.dispatchFirst`,
`ArrowEffect.FirstSuspended` (used throughout `ArrowEffectTest`), `Effect.deferInline`
(`EffectTest:60`), `Isolate.internal.Contextual` (throughout `IsolateTest`), and `Arrow.Transform`
(`ArrowTest`, in-subject; `EffectTest`, out-of-subject and flagged).

---

## 4. The private `eval` helper

Twenty-one files define a private `eval`. Fifteen define it as `v.eval`, the public extension, which
is fine. Six define it over internals:

| File | Line | Definition | Row |
|---|---|---|---|
| `shared/.../kernel/EffectBracketTest.scala` | 20 | `private def eval[A, S](v: A < S): A = Nested.unnest[A](Eval(v))` | general `S` |
| `shared/.../internal/EvalTest.scala` | 17 | same shape | general `S` |
| `shared/.../internal/HandlerTest.scala` | 18 | `private def eval[A](v: A < Any): A = Nested.unnest[A](Eval(v))` | `Any` |
| `shared/.../internal/DebuggerTest.scala` | 14 | same shape | `Any` |
| `jvm-native/.../internal/ReportTest.scala` | 13 | `private def eval[A, S](v: A < S): A = Nested.unnest[A](Eval(v))` | general `S` |
| `jvm/.../internal/EvalConcurrencyTest.scala` | 12 | same shape | general `S` |

`Nested.unnest` and `Eval.apply` are both internal, and `Eval.apply` is specifically not part of the
granted contract (only `partial` and `release` are).

The two `A < Any` versions (`HandlerTest`, `DebuggerTest`) are a mechanical substitution: replace the
body with `v.eval`, which does exactly the same thing (`<.eval` calls `Nested.unnest[A](Eval(...))`
internally, `Pending.scala:292-299`).

The four `[A, S]` versions need a decision. The signature is wider than the public `eval` extension,
which is `extension [A](inline v: A < Any)`. Every call site I read passes a row of `Any`, including
the ones whose *value* type is itself pending (`EvalTest:77` passes `(Int < Ask) < Any`, which the
public extension accepts with `A = Int < Ask`). If that holds across all call sites, narrowing to
`[A](v: A < Any)` and delegating to `v.eval` is mechanical. I did not compile, so this needs to be
confirmed rather than assumed.

One shape that will need attention when narrowing: `EvalTest` wraps `Eval.release`, which returns
`Unit`, in the helper (`discard(eval(Eval.release(parked, Boom)))` at lines 682, 706, 710, 726, 731,
753, 821, 853). The wrapper does nothing there; those calls reduce to
`Eval.release(parked, Boom)` with no `eval` at all.

Beyond the helper, three files carry dead internal imports that should go with the cleanup:
`PendingTest.scala:9-11` (`Eval`, `Nested`, `Pending`, none referenced),
`ArrowEffectTest.scala:9` (`Nested`, not referenced), and
`ArrowEffectThreadingTest.scala:7` (`Eval`, not referenced).

---

## 5. Counts

| | Count |
|---|---|
| Test files audited | 38 |
| Total cases | 1110 |
| Cases with an out-of-subject internal use | **106** |
| ...of which a public-surface rewrite exists today | **48** |
| ...of which the only internal is `evalNow`, so the verdict follows the ruling | **34** |
| ...of which no public path exists (pins plumbing or needs a new exposure) | **24** |
| Cases using an internal that IS their own file's subject | **117** |
| Cases touching no internal at all | 887 |

The 117 in-subject figure counts the suites whose whole purpose is an internal: `StackTest` (35),
`ContextTest` (14), `EffectTraceTest` (30 of 31), `SafepointTest` jvm-native (9 of 10),
`SafepointConcurrencyTest` (8 of 10), `DebuggerTest` (6 of 7), `NestedTest` (6 of 7),
`EffectTracePhysicalTest` (3), `ReportTest` (2), `StackThreadingTest` (1),
`SafepointUnstartedThreadTest` (1), `EffectTraceThreadingTest` (1), `SafepointTest` js-wasm (1). It
excludes the in-subject-but-not-public-surface uses listed as item 8 in section 3, which need their
own ruling.

Breakdown of the 24 with no public path:

| Internal | Cases |
|---|---|
| `Safepoint.period()` used to size a loop | 11 |
| `Safepoint` budget drained or measured | 10 |
| `Kyo.Park.entries` regions and states read | 3 |

`HandlerTest`'s sixteen cases are counted in the 48 rewritable, because a public twin exists for
every one of them; eleven are outright duplicates of tests already in the suite and the honest
disposition for those is deletion rather than rewriting.
