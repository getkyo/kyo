# Pure-arrow detection: derivation

Tip: `e6a1dc9449`. Part of TODO 3 (`Implicits.scala:17`), by ruling. Companion to the liftings
decision, which stays open until swap phase 1 with the rule "delete if the compiler's list of
dependent sites is empty or small".

## 1. What "pure" means here

A function passed to `map`, `flatMap`, or `andThen` whose body is an application of the one lift,
`Implicits.lift`. The lift's parameter is a plain `A` and `CanLift` refuses computation types, so a
detected body statically returns a plain value: it cannot suspend and cannot hand the evaluator a
computation to continue with. That is the whole definition; nothing is inferred about side effects.

Precedent: `Stream.mapPure` (kyo-prelude, PR #1268) is the same distinction made explicit at the
library level, with `NotGiven[V2 <:< (Any < Nothing)]` as the evidence, because overload resolution
could not pick the pure `map` for a lambda once the methods took `VV >: V`. The kernel can make the
distinction at the call site without a second method, because `map` takes `inline f` and the tree is
available.

## 2. What a settled map costs today, and why

`map` on a settled `self` (`Pending.scala:21`) runs `run(self, Arrow.id)`:

1. `v.isInstanceOf[Pending[?, ?]]`, false;
2. `Safepoint.get()`: `Thread.currentThread()`, the `home` hash, an `AtomicReferenceArray` plain
   read, and `resolve` on a miss;
3. `Safepoint.enter(slot)`: a `depths` read, a decrement, the guard test, a write;
4. `cont.head(f(Nested.unnest(v)), cont.tail)` with `cont = Arrow.id`: the `unnest` type test, two
   virtual calls to `Id`, `Id.apply`'s own type test on its `cont`;
5. `Safepoint.exit(slot)`: a read, an increment, a write.

The budget in 3 and 5 bounds JVM recursion through a *returned computation*: `loop(i + 1)` returned
from a `map` body constructs the next `map` inside this one's frame (`deepRecursionPaysRescuesOnly`,
`fusionPastBudgetPaysRescuesOnly`), and when the guard bit clears the map defers instead, turning
the recursion into a `Defer` node the evaluator trampolines. It also carries preemption: a `Stop`
noticed by `get()` drains the budget so the next `enter` fails, the map defers, and the evaluator's
`Defer` arm parks on `stopped`.

For a pure `f` at a direct call site neither job exists. `f` returns a value, so no computation is
constructed inside this frame through its result; `cont` is `Id`, so there is no chain to continue.
Any recursion through computations goes through an impure map, which keeps the budget, so the
evaluator's preemption granularity is unchanged: a chain of pure maps on settled values is finite
work that returns a value, the same as any plain expression.

The law the fast path specializes: `map` on a settled argument is application. The general path
already delivers `f(a)` when the budget allows; the pure path delivers it without asking.

## 3. The detection

A macro in kyo-data, cross-module on purpose: a file that summons a same-module macro is suspended
to the retry run, and `Pending.scala` is a core inlined-from file (the `StaleSymbolException`
cascade the skill records). The `CompileTimeFlag` macro for the debugger gate is the precedent.

```scala
package kyo.internal

object InlineBody:
    transparent inline def isCallTo[F](inline f: F, inline method: String): Boolean =
        ${ isCallToImpl('f, 'method) }
```

`isCallToImpl` peels `Inlined`, `Typed`, and single-expression `Block` wrappers, takes a lambda
(`Block(List(DefDef), Closure)`) to its body or a by-name argument as itself, and answers true when
the body is an application of the method named `method`, in either of the two shapes the inliner
leaves: the call itself (`Apply(Apply(TypeApply(fn, _), List(arg)), List(evidence))` with
`fn.symbol.fullName == method`), or an `Inlined(Some(call), _, _)` whose `call.symbol.fullName`
is `method`. Anything else answers false: a function value, an eta-expansion, an `If` or `Match`
whose branches lift separately, a body already typed as a computation. False is always safe; it is
today's path. True cannot be wrong, by section 1.

The kernel side names the lift once:

```scala
// Pending.scala, object `<`
private inline val Lift = "kyo.proto.kernel.internal.Implicits.lift"
```

## 4. The change, step 1: the settled direct call site

```scala
inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
    def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) = // unchanged
    inline if InlineBody.isCallTo(f, Lift) then
        val v = self
        if v.isInstanceOf[Pending[?, ?]] then run(v, Arrow.id)
        else f(Nested.unnest(v))
    else run(self, Arrow.id)
end map
```

`flatMap` is the same text. `andThen` takes `f` by name, so the detection runs on the expression
and the settled branch is `f`. `B < S2` conforms to `B < (S & S2)` by the row's contravariance, so
no cast. The pending branch is today's `DeferWith`, untouched: its resume path is section 6.

Surface: `kyo-data/.../kyo/internal/InlineBody.scala` and its test; `Pending.scala`, three
methods; `PendingTest` and `PendingBytecodeTest`. Must not change: `Arrow`, `Effect.defer`, the
`DeferWith` node, `Eval`, `Safepoint`, the liftings (a lifted function value produces a lambda whose
body is `lift(f(a))`, so it is detected pure and the two features compose).

## 5. Pins and rows

- kyo-data `InlineBodyTest`: a lambda whose body is a call to the named method; a lambda returning
  an identifier; a function value; an eta-expanded method; a by-name expression; an `If` with lifted
  branches (false).
- PendingTest, "pure maps": under a drained budget (`Safepoint.enter` until it returns false, as
  SafepointTest does, restored with `save`/`restore`), `((1: Int < Any).map(_ + 1))` is settled and
  equals 2, while the same map with a body that is a pending identifier defers to a `Pending`; a pure
  map on a pending value still defers; `flatMap` and `andThen` likewise.
- PendingBytecodeTest "map": the sizes change (`test` gains the branch, `run` stays 95). The new
  numbers are read from `javap` on the first compile and pinned; the claim to add is that a pure
  settled map's `test` method references no `Safepoint` member.
- Rows reached: `evalFixedOverhead`, `fusionAllocatesNothing`, `fusionPastBudgetPaysRescuesOnly`,
  `uncachedValuesPayBoxingOnly`, `idleHandlerAddsNothing`, `continuationBodiesFuse`,
  `inlineLimitCostsTimeNotAllocation`, `inlineLimitKeepsZeroAllocation`,
  `userTypesSkipKernelWrapping`. Not reached: any row whose maps sit on a pending value at
  construction (`fusionAfterSuspension`, `trailingMapsStayLinear`, the `dynamicChain*` rows on a
  suspension), the suspension and handler rows. Expected direction: time down on every reached row,
  allocation unchanged (the fast path allocates nothing and removed nothing that allocated).
- Clean batch build after, since `Pending.scala` gains a cross-module macro summon at every `map`
  call site inside the kernel (`ArrowEffect`, `Isolate`, `Loop`, `Handler`, `EffectTrace`).

## 6. Step 2, not part of this change

The pending case and the resume path. A `DeferWith` built over a pending value applies `f` when the
evaluator delivers the settled value, through `run` with a non-`Id` continuation, so `f`'s result
feeds the next arrow and a chain of pure nodes recurses one JVM frame per node without the budget.
Removing the budget there needs a depth-1 application shape for pure chains (a loop over the pure
functions of a fused node, since `Function1.andThen` nests calls too), and fusing consecutive pure
`DeferWith` nodes at construction trades one `DeferWith` allocation for one fused node plus one
composed function. Both are measured on `fusionAfterSuspension`, `trailingMapsStayLinear`, and the
`dynamicChain*` rows; neither is taken on reading. `Arrow.apply` steps have the same shape and the
same depth question.
