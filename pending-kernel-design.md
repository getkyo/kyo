# Pending kernel redesign

A design for a new effect kernel prototyped in `kyo-kernel/shared/src/main/scala/kyo/proto/pending.scala`.
This document records the architecture, the rationale behind each choice, and the open
implementation work.

## Goals

1. **Full stack safety.** No configuration of user code (deep `map`/`flatMap` chains, deeply
   resumed continuations, mutual recursion through effects) should overflow the JVM stack.
2. **Lower compile time and code size.** Reduce the depth and compounding of `inline`
   expansion that the current kernel relies on for continuation fusion.
3. **Runtime performance through JIT-friendly dispatch.** Keep the hot transitions
   monomorphic so the JIT can inline user code, which a global interpreter loop cannot.

## Core idea: split the continuation from the suspension

The current kernel fuses the continuation with the computation it continues: a `KyoContinue`
carries both the transformation function and the pointer to the suspended computation in one
object. Because they are bundled, the continuation is bound to that specific suspension and
cannot be lifted out and reused.

This matters for a common pattern: **repeated suspensions between two handlers**. A computation
handled by an outer handler often suspends the same inner effect many times before control
reaches the outer handler (for example a loop that emits or reads repeatedly). With the fused
representation, every resume/suspend cycle rebuilds a fused continuation; the shared "rest of
the work up to the next handler" cannot be factored out.

The new design separates them:

```
Continue(suspend, cont: Arrow)
```

`suspend` is the effect node; `cont` is an independent, immutable, composable `Arrow`. Mapping
over a suspended computation grows only the `Arrow` and leaves `suspend` untouched:

```
Continue.map(f) = Continue(suspend, Arrow.map(cont)(f))
```

So a suspended computation mapped N times is one `Continue` with an N deep `Arrow`, not N fused
nodes, and that `Arrow` is a plain value a handler can hold and re-apply across repeated
suspensions. Immutability of `Transform` and `AndThen` makes the sharing safe. This separation
is what enables both continuation reuse and strategic flattening of the continuation chain.

## Types

- `<[+A, -S]`: the pending computation. An unboxed union: a raw value `A`, a suspension
  `Kyo[A, S]`, or a `Nested[A]` marker.
- `Kyo[+A, -S]`: the suspension tree, `Suspend` or `Continue`.
  - `Suspend[I[_], O[_], E <: Effect[I, O], A]`: an effect operation with `input: I[A]`, a
    `tag: Tag[E]`, producing `O[A]` under effect `E`.
  - `Continue`: a `Suspend` plus a continuation `Arrow`.
- `Arrow[-A, +B, -S]`: the defunctionalized continuation. A `Transform` (one step), an
  `AndThen` (composition of two arrows), or a `Span[Any]` (a flattened chain, see below).
  - `Transform[-A, +B, -S]`: the atomic step, `run(v, cont): C < (S & S2)`.
  - `AndThen`: composition, with an identity short-circuit in `Arrow.map`.
  - `Span[Any]`: a strategically flattened array of steps for the drain loop.

## Unboxed pending and nesting

Pure values are stored raw with no wrapper, the same unboxed-union representation the current
kernel uses (`A | Kyo[A, S]`). The one problematic case is a payload that is itself a `Kyo` (a
nested pending, for example `(X < S) < S2`, or a `map` that yields a pending of a pending). Such
a value must be boxed in `Nested` so the outer pending does not read the inner `Kyo` as its own
suspension.

Current state: the `Nested` type and `unsafeGet` (which unwraps one level) exist, but `lift`
returns its argument raw and never constructs a `Nested`, so nested pendings currently collapse.
The boxing on `lift` is required and still missing (the current kernel does it in `LiftMacro`).

Note on reuse of the generic `Unboxed` primitive: it does not fit `<`. `Unboxed2.Type[+E, +A]`
is covariant in both parameters, but `<[+A, -S]` needs `-S` contravariant, and `<`'s case
`Kyo[A, S]` depends on both type parameters rather than a single "error" axis. So `<` uses a
bespoke `Nested`, hand-rolled rather than derived from `Unboxed`.

## JIT monomorphism: why inline `<.map` is the right call here

`<.map` is inline, and this is deliberate. It does the opposite of the current kernel's
continuation-fusing inline. Each `map` site emits one small `Transform` class whose `run` body
is `cont(f(v))` with `f` baked in. Two consequences:

1. At that site the concrete `Transform` type is fixed, so its `run` invocation is monomorphic
   and the JIT inlines both `run` and the baked `f`.
2. `run` takes the next arrow as a parameter rather than capturing a fixed successor, so the
   class stays identity-stable regardless of what follows it. The same class appears at the same
   program point every time, which is what call-site type profiling needs to specialize.

A global interpreter loop cannot get this. Every effect's continuation funnels through one
`continuation.apply(x)` bytecode location; thousands of distinct functions cross it, it goes
megamorphic, and the JIT keeps it a virtual call. This design replaces one megamorphic site with
many monomorphic ones.

The compile-time win is that the inline expansion is bounded per site (one small `Transform`
class), not the current kernel's expansion that compounds through the continuation chain.
"Reduce inlining" here means reduce the depth and compounding of expansion, not the count of
tiny specialized classes.

## Stack safety: two independent mechanisms

1. **Pure chains** are made stack-safe by inline flattening: a long `map`/`flatMap` run over
   values collapses to straight-line code with no frames and no loop.
2. **Suspended continuations** are made stack-safe by the drain loop below.

Only the second is at risk in the current code. A recursive drain would reintroduce a frame per
continuation step on exactly the path that exists to prevent it.

## The eval loop: bounded monomorphic recursion plus a return-bounce

The strategy is a hybrid: recurse directly (`run -> cont`) so transitions stay monomorphic and
JIT-inlinable, but bound the recursion depth and bounce to a trampoline when the bound is hit.

This differs from the bounded loops in cats-effect and Monix. Those bound a flat central loop;
the bounding is for fairness and the `continuation.apply` site stays megamorphic even when
batched. Bounding direct `run -> cont` recursion keeps each transition's successor fixed by
program structure, so transitions stay monomorphic up to the bounce. This keeps JIT inlining on
the resumed-continuation path, not only on the pure path.

### Bounce as a return node, not an exception

When the step budget is exhausted, `run` returns a `Bounce(frontierValue, remainingCont)`
sentinel (a `Kyo`-shaped node) instead of calling its successor. Because every `run` is
`cont(f(v))` in tail position, a `Bounce` returned by the inner call is returned onward: it
propagates up through normal returns, unwinding the whole batch's frames. No `throw`, no tail
call optimization needed. The values already produced were passed forward, so the frontier is
captured exactly and nothing is recomputed.

A thin outer loop matches the node and re-enters `eval(frontierValue, remainingCont, freshBudget)`
on a clean stack. The only central, possibly megamorphic, transition is that outer re-entry,
paid once per `N` steps.

### Unifying the two reasons control leaves the recursion

A **suspension** (a real `Kyo` a step produced) and a **depth-bounce** both propagate up as
return nodes. The outer loop distinguishes them: a suspension yields to the handler, a
depth-bounce re-drives on a fresh stack. A depth-bounce is structurally a `Continue`-shaped
"here is the value, here is the rest," so it reuses the same representation and needs at most a
tag the loop recognizes.

### Budget threading via a `private[kyo]` overload

Thread the budget as an `Int` depth through a `private[kyo]` overload of `Arrow.apply`. The
public `apply` delegates to it with depth 0; the internal overload increments and checks the
depth as it drives the direct recursion, returning a `Bounce` when the depth reaches the limit.
This avoids a context object and any allocation: it is one predictable, not-taken branch plus an
`Int` increment per transition, cheaper than the megamorphic dispatch it removes.

Check placement: per-`Transform` is the natural spot and the branch is free once predicted.
Checking only at `AndThen` boundaries is fewer checks but lets a single fat `Transform` run
unbounded, so per-step is safer. `N` can be generous given the build's `-Xss10M`, and `1/N` sets
the amortized re-entry cost, which is the one knob to sweep.

## Where megamorphism relocates, and the monomorphism caveat

Megamorphism does not vanish, it relocates to the outer loop's re-entry, which sees every
continuation shape. That is paid once per `N` steps.

Monomorphism is per bytecode call-site. It holds for fixed straight-line structure. A combinator
reused from many places (a `map` inside a shared helper) has one `run -> cont` site that sees
several successors, so it degrades to bi- or megamorphic there. The JIT's inline cache tolerates
bimorphic, and those sites are the minority, which is why "most" transitions are monomorphic.
The benchmark's job is to confirm the hot straight-line paths dominate and the re-entry frequency
stays low.

## Strategic flattening (Span)

Fold `AndThen` chains into a flat `Span` array at points where it pays, for example before a
handler resumes the same suspension many times. Each resume then iterates a flat array instead of
walking a linked continuation. This amortization is impossible when the continuation is fused into
the suspension, and natural once it is a separable `Arrow`.

## Open work

1. **Implement the drain loop**: bounded direct recursion, the `Bounce` sentinel and its
   return-propagation, and the outer re-drive. The `private[kyo]` depth overload of `Arrow.apply`.
   The two current compile errors (`b.pool()`, the empty `AndThen` arm) are in this stub.
2. **Box in `lift`**: wrap `Kyo` and `Nested`-shaped values in `Nested`, decide chaining versus
   depth for multi-level nesting, and add a nested-pending test.
3. **Hide `Nested`** (make it `private[kernel]`), it currently leaks as a public top-level case class.
4. **Strategic flattening policy** for `Span`: when to flatten, and the drain over a flat array.
5. **Benchmark the thesis**: re-entry frequency, the loop's dispatch degree, and throughput on a
   repeated-suspension workload (a loop suspending one effect many times under an outer handler),
   compared against the current kernel.

## Comparison

- **Current kyo kernel**: inline `map`/`flatMap` fuse continuations. JIT-friendly, but the
  expansion compounds and inflates compile time and bytecode.
- **ZIO and cats-effect**: a central megamorphic run loop. The continuation `apply` site is not
  JIT-inlinable; bounding is for fairness and stays megamorphic.
- **This design**: per-site monomorphic `Transform` classes (JIT-friendly) plus a bounded
  monomorphic drain (stack-safe and JIT-friendly on the resumed path as well), with lighter
  compile-time expansion. The separation of continuation from suspension is what makes reuse and
  strategic flattening possible.
