# Kernel test-coverage audit: `kyo-kernel` (OLD) vs `kyo-kernel2` (NEW)

Scope: OLD = `kyo-kernel/{shared,jvm}/src/test/**` minus `kyo/proto*`, `pendingtest`, `proto2test`,
`proto3test`, `unboxedtest` (verified as `kyo.proto*` experiments by their imports). NEW =
`kyo-kernel2/{shared,jvm,jvm-native}/src/test/**`, excluding `kyo-kernel2/.claude/skills/**`.

The `handleCatching` block and everything held on `Effect.catching` are classified PARKED, not
MISSING, per the team lead's ruling that restoration is imminent.

Totals: OLD **452** live cases. NEW **785** live, **60** parked (`//` or `/* */`), **1** `ignore`d.

---

## 1. Per-file classification

| OLD file | cases | PORTED | PARKED | MISSING |
|---|---:|---:|---:|---:|
| `shared/kyo/KyoTest.scala` | 92 | 92 | 0 | 0 |
| `shared/kyo/KyoForeachCollTest.scala` | 48 | 48 | 0 | 0 |
| `shared/kyo/KyoForeachTest.scala` | 0 (fixture) | n/a | n/a | n/a |
| `shared/kyo/TestVariant.scala` | 0 (annotation) | n/a | n/a | n/a |
| `shared/kyo/kernel/ArrowEffectTest.scala` | 38 | 14 | 17 | **7** |
| `shared/kyo/kernel/ContextEffectTest.scala` | 11 | 0 | 0 | **11** |
| `shared/kyo/kernel/EffectTest.scala` | 8 | 6 | 2 | 0 |
| `shared/kyo/kernel/IsolateTest.scala` | 28 | 0 | 0 | **28** |
| `shared/kyo/kernel/LoopTest.scala` | 74 | 74 | 0 | 0 |
| `shared/kyo/kernel/PendingTest.scala` | 47 | 45 | 1 | **1** |
| `shared/kyo/kernel/internal/CanLiftTest.scala` | 11 | 11 | 0 | 0 |
| `shared/kyo/kernel/internal/ContextTest.scala` | 11 | 0 | 0 | **11** |
| `shared/kyo/kernel/internal/TracePoolTest.scala` | 8 | 0 | 0 | **8** |
| `shared/kyo/kernel/internal/TraceTest.scala` | 16 | 4 | 2 | **10** |
| `jvm/kyo/kernel/BytecodeTest.scala` | 4 | 3 | 1 (`ignore`) | 0 |
| `jvm/kyo/kernel/internal/SafepointTest.scala` | 55 | 13 | 0 | **42** |
| `jvm/kyo/kernel/internal/TracePoolConcurrencyTest.scala` | 1 | 0 | 0 | **1** |
| **total** | **452** | **310** | **23** | **119** |

Two cross-cutting shape changes affect every ported file. NEW extends
`org.scalatest.freespec.AnyFreeSpec` rather than `kyo.test.Test[Any]` (`build.sbt:772-804` omits
`.withKyoTest`), so the `.onlyJvm` / `.onlyJs` / `.notNative` / `.notWasm` / `.pendingUntilFixed`
vocabulary is gone. And `ArrowEffect.handle(tag, v)(f)` became
`ArrowEffect.handleCont(tag, v)(f, a => a)` at every call site.

---

## MISSING cases, enumerated

### `ArrowEffectTest.scala`, 7 cases

**`"non-Const inputs/outputs"` group, OLD `:280-320`, 3 cases.** Fixture:

```scala
sealed trait CustomEffect extends ArrowEffect[List, Option]
def customEffect(input: List[Int]): Option[Int] < CustomEffect =
    ArrowEffect.suspend[Int](Tag[CustomEffect], input)
```

- `:286` `"suspend and handle"`, `assert(result.eval == Some(1))`
- `:294` `"chained effects"`, `assert(result.eval == (Some(1), Some(4)))`
- `:307` `"handle with state"`, `ArrowEffect.handleLoop(Tag[CustomEffect], 0, effect)(...)`,
  `assert(result.eval == (Some(1), Some(5)))`

All 38 `extends ArrowEffect[...]` declarations in the NEW test tree are `ArrowEffect[Const[X], Const[Y]]`
or `ArrowEffect[Id, Id]` (the two `Id, Id` ones exist only in the bytecode pins,
`ArrowEffectBytecodeTest.scala:17`, `PendingBytecodeTest.scala:15`). No NEW test instantiates
`I[_]`/`O[_]` at a genuinely index-varying constructor.

**`"effects with variance" > "delimited continuation"` group, OLD `:596-691`, 4 cases.** Fixture:

```scala
sealed trait Delim[R, +S] extends ArrowEffect[[A] =>> Delim.Op[A, R, S], Id]
enum Op[A, R, -S]:
    case Shift[A, R, S](f: (A => R < (Delim[R, S] & S)) => R < (Delim[R, S] & S)) extends Op[A, R, S]
```

- `:623` `"multi shot"`, `k(42).map(a => k(42 + 1).map(b => a + b))`,
  `assert(v == (42 * 10) + ((42 + 1) * 10))`
- `:633` `"multi shot with other effect"`, a shift resuming through `TestEffect2`
- `:647` `"multiple shift with different effect sets"`, two shifts over disjoint rows, `assert(v == 46)`
- `:672` `"short circuiting"`, a shift that discards `k`, folded over a list

`grep -rE "Delim|Shift"` over the NEW test tree: 0 hits. NEW does test multi-shot continuations
(`ArrowEffectTest.scala:316`, `:762`, `EvalTest.scala:746-876`) but never through a variant,
higher-kinded effect whose operation type carries the continuation.

**`"effects with variance" > "flow effect with dynamic tags"` group, OLD `:694-789`, 3 cases.** Fixture:

```scala
sealed trait Flow[+In, -Out] extends ArrowEffect[Flow.Op[In, Out, *], Id]
def emit[V: Tag](value: V): Unit < Flow[Any, V] = ArrowEffect.suspend(Tag[Flow[Any, V]], Op.Emit(value))
def poll[V: Tag]: Maybe[V] < Flow[V, Nothing]   = ArrowEffect.suspend(Tag[Flow[V, Nothing]], Op.Poll())
```

- `:725` `"single poll"`, three source/expected rows over `Chunk`
- `:742` `"poll and emit"`, a `Loop` driving both operations, three rows
- `:763` `"multiple flows in the same computation"`, two independently-run `Flow` instances at
  different type arguments in one computation, four rows

`grep -E "Flow|Poll"`: 0 hits. This is the whole "effect trait with variance plus per-call-site
computed `Tag`" axis. NEW's only tag-subtyping coverage is `ArrowEffectTest.scala:438`
`"answers operations of a subtype effect"`, `:867` `"a union tag subsumes both effects the way regions
are found"`, and `StackTest.scala:163,169`, all on invariant, ground effect types.

Partially missing, not counted above: `handle > "execution is tail-recursive"`, OLD `:97`. The result
assertion is ported (`ArrowEffectTest.scala:65,353` `"deep sequential operations are stack safe"`, 100k
iterations), but OLD's second assertion is not:

```scala
val depth = (new Exception).getStackTrace().size
...
assert(maxDepth - minDepth <= 10)
```

`getStackTrace` appears in the NEW tree only inside `EffectTraceTest.scala` and
`EffectTracePhysicalTest.scala`. No NEW test measures frame depth.

### `ContextEffectTest.scala`, 11 cases (whole file)

`ContextEffect` does not exist in `kyo-kernel2/shared/src/main`; the only reference is
`Effect.scala:75` `// Waits on ContextEffect, which this kernel does not have.` No NEW file, no parked
block for this file.

`:21` `"suspend"` / `:29` `handle > "const value"` / `:35` `"single effect"` / `:41` `"two effects"` /
`:56` `"three effects"` / `:74` `"ifUndefined behavior"` / `:80` `"ifDefined behavior"` / `:94`
`"multiple uses of the same effect"` / `:106` `"nested effects"` / `:118` `"effect order preservation"` /
`:136` `"with transformation"`

Nearest NEW coverage is one case asserting the pattern is expressible without the primitive,
`ArrowEffectTest.scala:279`:

```scala
"context effects arise from answering handlers" in {
    def provide[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)
    assert(Eval(provide(42)(ask.map(_ + 1))) == 43)
    assert(Eval(provide(1)(provide(2)(ask))) == 2)
}
```

That covers innermost-binding-wins and nothing else: no `ifDefined`/`ifUndefined`, no ordering, no
const-value shortcut.

### `IsolateTest.scala`, 28 cases (whole file)

`grep -r "Isolate"` over `kyo-kernel2/shared/src/main`: nothing.

`:15` `derive > "creates an isolate for context effects"` / `:23` `"fails compilation for non-context
effects"` / `:28` `"fails compilation for non-context effect traits"` / `:34` `isolate application >
"no context effect suspension"` / `:41` `"allows access to context"` / `:50` `"isolates runtime effect"` /
`:60` `"nested isolates"` / `:78` `"restoring"` / `:99` `"with non-context effect"` / `:117` `"preserves
outer effects"` / `:136` `residual effects > "supports residual effects in S type parameter"` / `:142`
`"allows using residual effects within isolate"` / `:161` `"preserves residual effects after isolate
application"` / `:175` `"supports subclasses of residual effects"` / `:200` `context inheritance >
"should propagate only non-isolated effects"` / `:217` `variance > Remove (invariant) > "cannot accept
supertypes"` / `:226` `"cannot accept subtypes"` / `:237` `Keep (contravariant) > "accepts subtypes"` /
`:243` `"does not accept supertypes"` / `:254` `Restore (covariant) > "accepts supertypes"` / `:260`
`"does not accept subtypes"` / `:271` `mixed variance > "contravariant Keep with covariant Restore"` /
`:277` `"variance preserved through andThen"` / `:286` `"complex intersection types"` / `:293` `"all
three variance interactions"` / `:302` `nest > "tunnels effects through isolation"` / `:313` `"allows
effect handling between nest and flatten"` / `:330` `"transforms Remove to Restore in type signature"`

The variance sub-block is 10 compile-time assertions on a three-parameter type's variance, with no
equivalent anywhere in NEW.

### `PendingTest.scala`, 1 case

`handle > "works with functions that return effects"`, OLD `:192-200`:

```scala
"works with functions that return effects" in {
    val effect: Int < TestEffect = TestEffect(1)
    val result = effect.handle { v =>
        TestEffect.run(v).map { x =>
            TestEffect.run(TestEffect(1))
        }
    }
    assert(result.eval == 2)
}
```

OLD's `handle` group has 15 cases; NEW's (`PendingTest.scala:504-635`) has 14, and every other one maps
by name. This is the only case asserting that a `handle` block whose body itself yields a computation
collapses correctly.

### `ContextTest.scala`, 11 cases (whole file)

`Context` (the internal per-drive map) does not exist in NEW. Structural successor is `Stack`, freshly
covered by `StackTest.scala` (46 cases), but `Context.inherit`'s isolation semantics have no NEW
analogue.

`:12` `empty > "should be empty"` / `:16` `"should not contain any tags"` / `:23` `contains > "should
return true for contained tags"` / `:28` `"should return false for non-contained tags"` / `:35`
`getOrElse > "should return value for contained tags"` / `:40` `"should return default for
non-contained tags"` / `:47` `set > "should add new values"` / `:52` `"should update existing values"` /
`:58` `"multiple effects"` / `:71` `inherit > "should keep non-isolated effects"` / `:84` `"should
remove isolated effects"`

### `TracePoolTest.scala`, 8 cases, and `TracePoolConcurrencyTest.scala`, 1 case

`TracePool` does not exist in NEW.

`:9` `"borrow and release traces"` / `:17` `"reuse released traces"` / `:25` `"replenish from global
pool when local pool is empty"` / `:33` `"clear traces when returning to the pool"` / `:52` `"borrow
should never return null"` / `:70` `"size management should be correct"` / `:92` `"release should
properly clear traces"` / `:107` `"sequential borrow/release should maintain pool integrity"` / (jvm)
`:9` `"borrow should never return null under concurrency"`

Risk here is lower than the count suggests. OLD's `TracePool` was a two-level pool with a shared
`MpmcUnsafeQueue` global tier (`kyo-kernel/jvm/src/main/.../TracePool.scala:30`), so the concurrency
case was load-bearing. NEW's `Stack.Pool` is purely `ThreadLocal` with no cross-thread tier
(`Stack.scala:334-359`), so there is no race to test. `StackTest.scala:401-419` `"the pool"` covers
borrow/return and distinctness. Genuinely untested on the successor: pool growth
(`free = Array.copyOf(free, size * 2)`, `Stack.scala:347`) and the borrow-when-empty path.

### `TraceTest.scala`, 10 cases

The mechanism changed completely. OLD recorded frames eagerly into a per-`Safepoint` ring buffer
(`pushFrame` / `saveTrace` / `withTrace` / `TracePool`); NEW reconstructs them lazily at the throw
boundary from the failing node and the eval stack (`EffectTrace.scala:14-20`).

- `:69` `"jvm" > "repeated frames"` and the three render-collapsing cases: `:166` `"Trace.render
  collapses consecutive identical frames into one line with a (xN) count"`, `:181` `"Trace.render omits
  the count suffix for a single occurrence"`, `:190` `"Trace.render counts only consecutive repeats, not
  all occurrences"`. `grep -E 'repeat|consecutive|dedup'` over `EffectTrace.scala`: nothing. NEW has no
  run-length collapsing; a recursive loop now renders one line per frame up to the cap.
- `:151` `"Trace.render emits orderedElements toString newest-first for a golden snapshot"`. No golden
  snapshot anywhere in NEW. Related: the ported cases (`EffectTraceTest.scala:140-166`,
  `EffectTracePhysicalTest.scala:29`) assert method-name membership and file name, where OLD asserted a
  byte-exact rendering including interleaved source text and `file:line` (`assertTrace`, OLD `:315-328`).
- `:144` `"bug #1172 null frames"`, `safepoint.pushFrame(null.asInstanceOf[Frame]); safepoint.enrich(new
  Exception)`. `grep 1172` over NEW: 0 hits. The new walker reads `Frame`s off arrows and nodes rather
  than a ring, so the specific null path may be unreachable, but nothing asserts it.
- `:218` `"withTrace writes the advanced index back into the trace so frames pushed during the run
  survive"`, `:238` `"withTrace folds the written-back index into [0, 2*maxTraceFrames) so a long-lived
  fiber cannot overflow"`, `:270` `"the folded canonical index is output-preserving against render and
  always in range"`. These guard a long-lived-fiber index-overflow bug. The new mechanism uses a cap
  instead, covered by `EffectTraceTest.scala:293-306` `"the cap"` and `:111` `"a chain past the cap
  reports the drop"`: a different property, comparable protection.
- `:87` `"js" > "only eval"`, `:102` `"js" > "with effects"`, both
  `.onlyJs.pendingUntilFixed("JS does not preserve source file/line positions, so Kyo trace frames land
  at the wrong stack position")`, and `:132` `"no trace if exception is NoStackTrace" > "js"`. NEW has no
  JS-targeted test anywhere, and `kyo-kernel2` cross-builds to JS and Wasm (`build.sbt:773`). The known
  JS defect OLD documented in-suite is now undocumented in the test tree.

Ported from this file: `:39`/`:51` (effect frames on a throw) to `EffectTraceTest.scala:71`, `:140-166`,
`EffectTracePhysicalTest.scala:29`; `:125` (NoStackTrace, jvm) to `EffectTraceTest.scala:102`; `:202`
(`"pushFrame skips the shared internal placeholder frame"`) to `EffectTraceTest.scala:163` `"skip the
internal frame placeholder"`.

### `SafepointTest.scala`, 42 cases

`Safepoint` was re-founded. OLD was a per-thread object threaded as a `using` parameter carrying an
interceptor, an `ensure` stack, and the trace ring. NEW (`Safepoint.scala:1-179`) is a static slot table
indexed by thread id carrying only a budget and a stop flag; there is no implicit `Safepoint` parameter.

**Thread affinity and leakage, 12.** `:19` `"does not allow capturing across threads"` / `:33` `"allows
resuming in the same thread"` / `:45` `"suspends when Safepoint is from a different thread"` / `:64`
`"handles nested deferrals correctly"` / `:76` `"propagates Safepoint through flatMap"` / `:93`
`"maintains different Safepoints across forks"` / `:107` `"no leak between forked executions"` / `:128`
`"no new Safepoint for nested eval calls"` / `:142` `"capture Safepoint in closures"` / `:159` `"forced
runtime leak"` / `:171` `"forced runtime leak + eval"` / `:183` `"no NPE when safepoint leaks across
threads (#1095)"`

Nearest NEW coverage: `EvalTest.scala:890` `"a nested eval shares the thread's stack and sees none of
the outer regions"`, `EvalTest.scala:780` `"resumes on another thread"`, and slot-ownership cases at
`SafepointConcurrencyTest.scala:136,185,258`. The #1095 regression has no NEW guard.

**Interceptors, 6.** `:199` `immediate > "use the interceptor"` / `:211` `"eval removes the
interceptor"` / `:223` `"restore previous interceptor"` / `:246` `propagating > "through suspensions"` /
`:267` `"restores previous interceptor after completion"` / `:300` `"example logging interceptor"`

`Safepoint.Interceptor` does not exist in NEW; there is no hook to observe or veto step entry. The
replacement is the stop-slot protocol, covered by `SafepointTest.scala:10-31` (3 cases) and
`SafepointConcurrencyTest.scala:22-278` (8 cases).

**`ensure` with an interceptor, 6.** `:524` `"passes ensure function to Interceptor"` / `:543` `"calls
removeFinalizer on completion"` / `:558` `"handles nested ensures"` / `:578` `"interceptor can call the
ensure function multiple times but it evaluates once"` / `:605` `"executes ensure function without
interceptor"` / `:629` `"executes ensure function without interceptor even on exception"`

**`ensure`'s error parameter, 3. This is a real semantic gap, not a mechanism swap.** `:655` `"receives
Absent on normal completion"` / `:666` `"receives Present with exception on failure"` / `:682` `"with
nested ensures passes correct exception to each handler"`

```scala
"receives Present with exception on failure" in {
    var receivedValue: Maybe[Error[Any]] = null
    ...
    assert(receivedValue.get == Panic(exception))
}
```

`Effect.bracket`'s release is `A => Any < Any` (`Effect.scala:90`): it receives the acquired resource and
nothing about the outcome. `Stack.drainFinalizers(cause: Throwable)` takes the failure but uses it only
to suppress release-time throws onto it (`StackTest.scala:503`). A release that must distinguish success
from failure (commit versus rollback, the canonical bracket use) cannot be written against this surface.
Unlike every other removal in the tree, this one carries no parked test and no note.

**`Safepoint.State` bit-packing, 14 of 15.** `:709` `init > "creates a state with the current thread id,
zero depth, and zero frame index"` / `:720` `depth > "initial"` / `:725` `"two increments"` / `:730`
`"increments and decrements"` / `:737` `threadId > "returns the correct thread id"` / `:746`
`hasInterceptor > "returns false by default"` / `:751` `"returns true when set"` / `:756` `"does not
affect other bits when set"` / `:767` `withDepth > "updates the depth correctly"` / `:776` `"handles
depth changes with interceptor set"` / `:786` `withInterceptor > "sets hasInterceptor to true"` / `:795`
`"sets hasInterceptor to false"` / `:804` `"does not affect depth when toggling interceptor"` / `:815`
`combined > "maintains correct state after multiple operations"` / `:830` `"handles rapid toggling of
interceptor"`

NEW's `State` is a different word (depth guard, budget, armed bit; `Safepoint.scala:50-73`) with exactly
one test: `SafepointTest.scala:33` `"the budget flows through enter, exit, save, and restore"`. Untested
on the new word: the `armed` bit's independence from the depth field (the analogue of `:756` and `:804`),
and `Safepoint.arm` (`Safepoint.scala:144`) has zero references in the test tree.

**Ported from this file, 13.** OLD's `Safepoint.ensure` became `Effect.bracket` (`Effect.scala:90-105`)
plus `Stack`'s finalizer array (`Stack.scala:22, 266-303`). NEW's `EffectTest.scala:413-790` `"bracket"`
group (33 cases) covers strictly more ground.

| OLD | NEW |
|---|---|
| `:351` `"executes cleanup after successful completion"` | `EffectTest.scala:415` `"releases after the use completes, not at the boundary"` |
| `:360` `"executes cleanup after exception"` | `:430` `"releases when the use throws, and the exception still propagates"` |
| `:370` `"nested ensures"` / `:467` `"nested ensure executes all thunks"` | `:536` `"nested brackets release innermost first"`, `StackTest.scala:425` |
| `:383` `"cleanup functions execute in reverse order"` | `StackTest.scala:438` `"drain innermost first"` |
| `:396` `"works with effects"` | `:444` `"releases when the use suspends and the continuation is answered"` |
| `:409` `"executes cleanup when effect fails"` | `:549` `"nested brackets both release when the inner use throws"` |
| `:423` `"works with defer"` | `:576` `"the release itself may be a deferred computation"` |
| `:433` / `:484` `"executes thunk only once"` | `:529` `"releases exactly once when the use completes and the drive then ends"`, `StackTest.scala:448` `"one that already ran is a no-op at the drain"` |
| `:444` / `:455` `"executes thunk on normal completion"` / `"on exception"` | `:415`, `:430` |
| `:496` `"executes thunk only once with map"` | `:566` `"sequential brackets each release"` |

---

## PARKED cases, grouped by what they wait on

**Waiting on partial evaluation and the Bracket-and-Park work, 13.**

`ArrowEffectTest.scala:1226-1332` `"handlePartial"` (10 cases, inside `/* */`):

> `// Parked with the removal of ArrowEffect.handlePartial: the partial`
> `// handler returns with the IOTask integration design. Restore then.`

`ArrowEffectTest.scala:447` `"installed after a partial evaluation answers the parked operation"` and
`:591` `"a parked stateful region resumes with its state and done"`:

> `// Waiting on partial evaluation, which lands with the Bracket and Park work`
> `// (see reviews/BRACKET-PARK-DESIGN.md).`

`SafepointConcurrencyTest.scala:81` `"an evaluation yields to a stop requested from another thread"`:

> `// Waiting on partial evaluation: a stop is observable only through a drive that can hand back a`
> `// parked value, which lands with the Bracket and Park work (see reviews/BRACKET-PARK-DESIGN.md).`
> `// Until then the stop protocol itself is covered by the cases above and by SafepointTest.`

Covers OLD `ArrowEffectTest.scala:322,341,359,379,403` (`handlePartial`, 5) and `:578` (`handlePartial on
Nested`). Partly compensated one layer down: `EvalTest.scala:950-995` covers `Eval.partial` directly,
where `:968` `"the stop function ends the slice"` is the analogue of OLD `:379` and `:979` `"partial
completes when nothing stops"` of OLD `:322`. Not covered: the ArrowEffect-level park-and-rehandle
behaviour.

**Waiting on a first-operation region primitive (`handleFirst`), 14.**

`ArrowEffectTest.scala:881-1053` (13 cases, inside `/* */`):

> `// A first-operation region needs a clause that receives the continuation and`
> `// ends the region with its own result type. handleCont keeps the region`
> `// installed and handleLoopState answers with a value, so the old helper`
> `// (built on a stateful handleLoop whose clause received the continuation)`
> `// has no primitive to stand on here.`

`EffectTest.scala:206` `"failure in a map after a first region"`:

> `// A first-operation region answers once and carries the resumed remainder out through Loop.done, so`
> `// its clause needs the continuation in hand. That is handleFirst, which this kernel does not have:`
> `// handleCont keeps the region installed and handleLoopState answers with a value, and neither`
> `// substitutes. Parked with the rest of that family.`

Covers OLD `ArrowEffectTest.scala:120,140,161,175,193` (`handleFirst`, 5) and `:463` (`handleFirst on
Nested`).

**Held on `Effect.catching`, restoration imminent, 15.**

`ArrowEffectTest.scala:1129-1230` `"handleCatching"` (13 cases, inside `/* */`):

> `// This kernel has no handleCatching: recovery from a throw inside a region`
> `// is not an ArrowEffect primitive here.`

Main-source note (`ArrowEffect.scala:277-278`): *"Answers operations while recovering from a throw
raised inside the region. Wants the same unwind mechanism as Effect.catching; see the note there."*

`EffectTraceTest.scala:333` `"the effect frames of a throw are carried through a catching guard"` and
`:340` `"a second crossing rewrites the spliced trace rather than duplicating it"`:

> `// Effect.catching is not in this kernel yet. These are the previous kernel's cases that`
> `// depend on it, kept as the specification for the port.`

That comment is stale relative to the current tree: `Effect.catching` exists at `Effect.scala:66` and is
live-tested at `EffectTest.scala:141-324`. Covers OLD `ArrowEffectTest.scala:213,224,235,248`
(`handle.catching`, 4) and `:551` (`handleCatching on Nested`).

**Waiting on the IOTask integration design (operation inspection), 8.**

`ArrowEffectTest.scala:1060-1126` `"dispatchFirst"`. No OLD ancestor.

> `// Parked with the removal of ArrowEffect.dispatchFirst: the operation`
> `// inspection returns with the IOTask integration design. Restore then.`

**Waiting on a `ContextEffect` replacement, 6.**

`EffectTest.scala:352-410` `"detach"` (4 cases):

> `// Parked with the removal of ContextEffect and Effect.detach from kyo-kernel2.`
> `// Restore against the replacement design.`

`PendingTest.scala:798` `nested computations > "multiple operations"` (covers OLD
`PendingTest.scala:432`):

> `// Parked with the removal of ContextEffect from kyo-kernel2. Restore against`
> `// the replacement design, together with "multiple operations" below.`

**Parked with no stated reason, 2.**

`EffectTest.scala:328` `"defer with catching"` (OLD `EffectTest.scala:93`) and `:339` `"combining
multiple effects"` (OLD `:104`). Both compose `Effect.defer` with `Effect.catching`; both primitives
exist and are individually tested in NEW. These read as collateral from the `detach` removal rather than
deliberate parks, and are worth re-enabling to see what happens.

**`ignore`d, 1.**

`PendingBytecodeTest.scala:36`, covering OLD `BytecodeTest.scala:32` `"map"`:

```scala
// disabled while the map expansion shape is under active iteration; re-pin
// once the design settles
"map" ignore {
    val sizes = methodBytecodeSize[TestMap]
    assert(sizes == Map("test" -> 22, "arrow" -> 9, "run" -> 114))
}
```

OLD pinned `Map("test" -> 26, "anonfun" -> 11, "mapLoop" -> 151)`. `map` is the hottest user-facing
expansion in the codebase and its compiled size is currently unpinned.

**Recorded as a known limit rather than pending work, 1.**

`EffectTraceTest.scala:205` `"carries the deferred site"`:

> `// Known limit, recorded rather than guarded: a throw from the body of `Effect.defer` happens`
> `// while the drive reads the node's payload, which is the one path into user code the attach`
> `// sites do not cover. Guarding it would put a try region on the deferral arm, the hottest`
> `// arm of the drive, to describe a failure on a surface that carries no frame of its own`
> `// (Kyo.Defer declares no `frame`). The exception propagates correctly; it arrives without`
> `// effect frames.`

**Deleted rather than parked, with a rationale.** `EvalTest.scala:986-989`:

> `// Two cases from an earlier design are gone rather than parked: a slice took a row that could still`
> `// name unhandled effects, so an operation without a handler ended the slice and something installed`
> `// later answered it. `partial` takes `A < Any` now, the same row a full evaluation takes, so an`
> `// operation with no handler has none anywhere and is a bug at both entry points.`

---

## Weakened assertions among the PORTED

Three cases pass their port but assert less than OLD did.

1. **`KyoTest.scala` `"toString"`** (OLD `:31`, NEW `:34`). OLD pinned the whole rendering including
   position and source text:

   ```scala
   assert(TestEffect1(1).map(_ + 1).toString ==
       "Kyo(kyo.KyoTest.TestEffect1, Input(1), KyoTest.scala:32:41, assert(TestEffect1(1).map(_ + 1))")
   ```

   NEW keeps only the prefix:

   ```scala
   val rendered = TestEffect1(1).map(_ + 1).toString
   assert(rendered.startsWith("Kyo(kyo.KyoTest.TestEffect1, Input(1), KyoTest.scala:"))
   ```

2. **`KyoTest.scala` `"eval widened"`** (OLD `:42`, NEW `:42`).
   `typeCheckFailure("TestEffect1(1).eval")("value eval is not a member of Int < KyoTest.this.TestEffect1")`
   became `assertTypeError("TestEffect1(1).eval")`. Any compile error now passes.

3. **`BytecodeTest.scala` `"handle"` to `ArrowEffectBytecodeTest.scala:42` `"handleCont"`.** OLD pinned
   three methods (`Map("test" -> 26, "anonfun" -> 8, "handleLoop" -> 283)`); NEW pins one
   (`Map("test" -> 44)`). The file's own scaladoc states the numbers "are measured against this kernel,
   not carried over", so this is deliberate, but the 283-byte `handleLoop` body is no longer covered by
   any pin.

Counterweight: two cases were strengthened. `KyoTest.scala:162,170` `"suspension at the start"` and
`"multiple effects"` were `.notNative.notWasm.pendingUntilFixed("deep effect suspension is not yet
stack-safe (StackOverflowError)")` in OLD and now run live with real values (`== 2 * n + 1`,
`== n + n / 32`), closing a documented OLD defect. `CanLiftTest` was ported case-for-case and
strengthened, with two cases added.

---

## 2. Kernel2 surface with no test at all

Determined by enumerating public and `private[kyo]` entry points in `kyo-kernel2/shared/src/main` and
grepping the NEW test tree for each.

- **The entire `Map` receiver block of `Kyo`**, `Kyo.scala:2189-2700`, roughly 25 overloads (`foreach`
  x2, `foreachConcat` x2, `foreachDiscard`, `filter`, `filterKeys`, `foldLeft`, `collect` x2,
  `collectAll`, `collectAllDiscard`, `findFirst`, `takeWhile`, `span`, `dropWhile`, `partition`,
  `partitionMap`, `scanLeft`, `groupBy`, `groupMap`, `shiftedWhile`). Zero test references.
  `KyoTest.scala:650-654` parameterises `collectionTests` over `Vector`, `Chunk`, `List`, `Set`, `Seq`,
  never `Map`. Untested in OLD too.
- **`Kyo.filterKeys`** (`Kyo.scala:2316`). No receiver-type analogue elsewhere, so it is the one method
  with no coverage by any route. Zero hits in either tree.
- **`Kyo.foreachConcat`**. One hit, `PendingExpansionSiteTest.scala:443`, an expansion-site compile check
  with a single small assertion. No behavioural test in either tree.
- **`<.finalizeResources`** (`Pending.scala:300`, `private[kyo]`). Zero references.
- **`Safepoint.arm`** (`Safepoint.scala:144`, `private[kyo]`). Zero references.
- **`Safepoint.slotCount` flag validation** (`Safepoint.scala:43-48`). The non-power-of-two rejection
  branch is never exercised.
- **`Safepoint.period` flag** (`Safepoint.scala:41`). Four test files hardcode `private val Period = 512`
  (`ArrowEffectTest.scala:26`, `SafepointTest.scala:8`, `SafepointConcurrencyTest.scala`,
  `EvalTest.scala`) instead of reading the flag. Changing the default silently invalidates
  `SafepointTest.scala:40` `assert(entered == Period)` and every budget-boundary case.
- **`Loop.forever`** (`Loop.scala:574`). One hit, `PendingExpansionSiteTest.scala:367`, a compile-site
  check. No behavioural test in either tree.
- **`Stack.Pool` growth** (`Stack.scala:347`). `"borrows are distinct while held"`
  (`StackTest.scala:412`) never pushes past the initial capacity of 4.
- **No JS- or Wasm-targeted test of any kind**, despite `build.sbt:773` cross-building to both. The
  shared corpus runs there; nothing asserts platform-specific behaviour, and NEW has no way to express
  `.onlyJs` since it dropped `kyo.test.Test`.
- **No frame-depth measurement anywhere** (see the `ArrowEffectTest.scala:97` note above).

---

## 3. NEW-ONLY coverage

785 live cases against OLD's 452, grouped by what they exercise.

**Arrow as a first-class value: `kyo/ArrowTest.scala` (27 cases).** `Arrow` has no counterpart in OLD's
kernel main sources. Identity sharing and self-head/tail (`:29-46`), `Arrow.apply` multi-shot and
once-per-application semantics (`:48-82`), `Arrow.recursive` in bounded stack (`:84-100`), `chain`
composition order with head/tail decomposition (`:102-138`), frame attribution (`:140-153`), and
`toString` at every nesting level including a deep chain (`:167-195`).

**The eval stack: `kernel/internal/StackTest.scala` (46 cases).** Push/pop LIFO with chain flattening and
identity skipping (`:63-127`), handler lookup by tag including subtype/supertype directions and
innermost-wins (`:129-187`), per-entry state (`:189-217`), `truncate` (`:219-244`), `dump` and the
capture arrow it produces including the denormalize/normalize split (`:255-345`), tail dumps stopping at
the innermost handler and capping at half the safepoint period (`:348-385`), growth past initial capacity
(`:387`), the pool (`:401-419`), and finalizers (`:421-521`).

**The evaluator: `kernel/internal/EvalTest.scala` (87) and `EvalCaptureTowerTest.scala` (3).** The
largest new block. Clause-scope semantics (`:633-744`, 8 cases pinning that a clause's own suspension is
answered outside its region and that a clause cannot see handlers inside its own scope);
continuation-as-value semantics (`:746-876`, 6 cases including cross-thread resumption, resumption under
a later same-tag region, and per-shot map replay); effectful-answer remainder placement (`:382-458`, 6
cases); `KyoBugException` on unhandled operations (`:878-888`); stack cleanliness after a throw
(`:899-943`); partial evaluation (`:950-995`).

**Handler and box internals: `kernel/internal/HandlerTest.scala` (9), `kernel/internal/NestedTest.scala`
(7).** The `Handler` node as an arrow, stateful-handler successor semantics, and the `Nested` box's
one-level-per-lift discipline.

**Implicit conversions and rendering: `kernel/internal/ImplicitsTest.scala` (19).** Lift at each static
shape, lift rejections with message checks, lifted functions to arity four, and the `Render` instance.

**Expansion hygiene outside `package kyo`: `outsidekyo/PendingExpansionSiteTest.scala` (73).** Every
inline surface (combinators, all ten `handle` arities, the arrow constructors, the effect surface,
`Loop`, the lifts, the collection combinators) exercised from a foreign package, so a `private[kyo]`
symbol reached by an inline body fails here rather than downstream. OLD has no test outside
`package kyo*`.

**Resource brackets: `EffectTest.scala:413-790` (33).** Beyond the ten OLD `ensure` cases it subsumes:
budget-park spanning (`:618`), an acquire resumed twice owing a release per resume (`:606`), discarded
continuations releasing everything outstanding (`:506`), a clause that stops the computation still
releasing (`:465,:473,:481,:493`), release-throw suppression onto an already-failing drive (`:722`),
every release running when several throw (`:747`), deeply nested brackets in bounded stack (`:627`), and
a bracket inside a nested drive releasing at that drive's boundary (`:645`).

**New handler families: `ArrowEffectTest.scala:604-689` (10).** `handleWith`, `handleLoopWith`,
`handleLoopStateWith`, none of which exist in OLD.

**Region parking: `ArrowEffectTest.scala:805-872` (3).** A clause parking by returning and resuming by
rewrapping the continuation, sibling-region preservation across a park, and union-tag subsumption.

**Preemption protocol: `SafepointTest.scala` (4), `SafepointConcurrencyTest.scala` (8),
`SafepointUnstartedThreadTest.scala` (1).** Slot claiming under concurrency, dead-cell reuse, the
overflow slot's degraded behaviour (`:210`), stop idempotence, and a live thread that never evaluated
not being stoppable.

**Effect-trace reconstruction: `EffectTraceTest.scala` (24), `EffectTracePhysicalTest.scala` (3).**
Region-nesting naming innermost-first with each region named exactly once (`:213-279`), the cap and its
drop record (`:293-306`), walk-failure containment leaving the original failure travelling (`:308`), and
the suspension boundary the physical stack cannot cross (`EffectTracePhysicalTest.scala:47`).

**Loop constructor typing: `LoopTest.scala:736-771` (5).** Pins the type-argument shapes the old kernel's
call sites passed, plus that `continue` evaluates its state once at construction and a done payload that
is a computation held as a value stays data.

**Lift bytecode pins: `PendingBytecodeTest.scala:44-73` (4).** Lift of a primitive, a `String`, a
concrete class, and a generic value, each pinned to its expansion size. OLD had no lift pins.
