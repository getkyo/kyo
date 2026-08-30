# Derivation: nested regions cost heap, not stack

## The defect

`ArrowEffect` "handles nested per recursion step in bounded stack" aborts with a
`StackOverflowError`. Pre-existing: at `eabef556e0`, before anything in this window, the suite
aborts identically after the same 17 tests.

Measured on threads with an explicit stack, binary-searching the depth, ceiling 400000:

| shape | proto | reference |
|---|---|---|
| nested regions, 1 MB | 1426 | 400000, no failure |
| nested regions, 2 MB / 4 MB | 10290 / 21213 | |
| sequential operations in one region, 1 MB | 400000, no failure | |

About 700 bytes of stack per open region. Iteration inside a region is already flat, because
answering an operation and continuing is a genuine tail call at `region`'s own recursive call.
Nesting is not, because the interior is evaluated in scrutinee position of a `match`:

```scala
def region[T](st: VX, v: T < (EX & S), cont: Arrow[T, AX, EX & S], ctx: Context): Y < S =
    loop(v, cont, Arrow.id, ctx) match      // the frame must survive to inspect the result
```

so an inner `Handle` reached from there recurses into its own `region`, and the enclosing frame
stays live to apply `done`. The set of installed handlers is therefore held in the Java call
stack, and its depth is the Java stack's depth.

## The equation

A region is already a value in this kernel. `Kyo.Handle(value, handler, state, cont)` is "the
region installed around `value`, whose result flows through `cont`", and the foreign-crossing arm
already turns an *open* region back into that value:

```scala
Kyo.handle[EX, AX, Y, S & S2, VX](k0.head(x, k0.tail), handler, st).chain(cont2)
```

So a computation with N regions open around it is a right-nested chain of `Handle` values:

```
Handle(Handle(… Handle(interior, hN, stN, contN) …, h2, st2, cont2), h1, st1, cont1)
```

and the eval's job is: evaluate the innermost thing; when it settles, complete the enclosing
regions from the inside out; when it suspends, let the innermost region whose tag matches answer
it, reifying each region it crosses on the way out.

The chain is a value, so it can live on the heap. **The stack is that chain, unrolled.** That is
the whole change: nothing about what a region means changes, only where the chain is kept.

## Every piece maps to a value the kernel already has

| piece | existing value |
|---|---|
| the handler that answers for a region | `Kyo.Handle.handler` |
| the state the region threads | `Kyo.Handle.state`, advanced by `Loop.Continue2` |
| what follows a region | `Kyo.Handle.cont`, composed with the eval's registers |
| a region reified back into a value | the foreign-crossing rebuild, unchanged |
| completing a region | `Handler.done` |
| ending a region early | the `Loop` done outcome, which bypasses `done` |
| failing a region | `Handler.recover` |
| abandoning a region | `Handler.release`, on the node, untouched by this change |

**The ambient context has no counterpart in `Handle`**, which is the one piece that needed
resolving rather than reading off. It is not a new piece: a `HandlerContext` region *is* a
binding, so the `ctx` map is the evaluator's memoization of the bindings the region chain already
implies, kept as a map for O(1) lookup instead of walking the chain per read. Recomputing it from
the chain would be correct and is rejected on cost. Storing it per entry is therefore the same
information the chain already carries, and the entry column is a cache, not an invention.

## What the equation says about the loop's type

`loop(v, contA, contB, ctx): C < S` is a *composition* signature: it says the result is `v` with
`contA` then `contB` applied. Once a region's continuation moves onto the stack that sentence stops
being true, because the call now runs the machine to completion rather than computing that
composition. The signature has to say what the method does:

```scala
def apply[A, S](v: A < S): A < S =
    def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S
```

The interior's row is just this call's `S2`, so entering a region is a tail call with no cast, and
the eval's answer type is unchanged by how deep the machine currently is. Erasing the result to a
carrier type instead is the failure `rulings.md` records under Types and safety.

## Surface

Changes:

- `kyo/proto/kernel/internal/Stack.scala`, new. Four arrays and a size.
- `kyo/proto/kernel/internal/Eval.scala`, inside `apply`:
  - `loop`'s signature, per the section above, and `@tailrec` on it so the tail-call property the
    change rests on is checked rather than claimed;
  - the `Handle` arm: install and continue, replacing `region`;
  - the settled arm: complete the innermost region and continue;
  - the `Suspend` arm: after the registers absorb, ask the regions;
  - **the guard, replacing `run`**. The per-region `try` dies with `region`, so the extent guard moves
    to the eval. It is a loop rather than a recursion, because a recovery that resumed by evaluating
    the rest of the eval from inside its own catch would cost a frame per recovered region, which is
    the dependency this change exists to remove, reintroduced one level over. `run`'s existing job,
    answering a context read that no region answered, folds into the same loop because both are "the
    eval continues after the loop returned or threw". This is a change to `run` and is declared as one.
  - three file-level imports: `Maybe.Absent`, `Maybe.Present`, `scala.annotation.tailrec`.
- `kyo/proto/kernel/internal/Handler.scala`, `recover`'s scaladoc only. It states the install-time
  state as the contract, and after this change no path honours it, so the sentence changes with the
  behaviour. Declared here because a contract in a doc comment is still a contract, and the first
  version of this derivation wrongly listed `Handler` as untouched.

Does **not** change: `KyoInternal` node classes, the `Handler` protocol's signatures, `ArrowEffect`,
`ContextEffect`, `Pending`, `Loop`, `Arrow`, `Effect`, `Eval.release`, `answerLoop`.

## New type

`Stack` is a new type, against the standing preference, so it needs an argument that an existing one
cannot serve. No existing type holds a growable heterogeneous sequence of open regions, and the
entries have to be reachable by the eval while a region is open, which a value in the pending union
is not. Its entries stay columnar rather than becoming a typed entry object: re-typing an array
element at the storage boundary is the sanctioned erasure-forced cast, the cast ladder names `Stack`
as its example, and a typed entry would allocate per region to buy what the ladder already permits.

## Concession: mutability

Two of them, and each carries the four parts.

**The region stack.** Justified because the region chain is the only thing whose depth was the Java
stack's. Scoped to one instance per `Eval.apply`, reachable from nothing that leaves the eval.
Protected because entries are written only by the eval's own arms and hold complete values, and a
nested eval builds its own. Pinned by `ArrowEffectTest` "handles nested per recursion step in bounded
stack" (the defect), `EvalTest` "a nested eval shares the thread's stack and sees none of the outer
regions" (per-eval scoping), and `EvalTest` "the captured continuation is multi-shot" with "each shot
of a multi-shot capture resumes from capture-time state" (a resumed shot re-installs from the node,
not from a stack an earlier shot mutated).

**The guard's loop.** Justified because the alternative costs a frame per recovered region. Scoped to
five locals in `apply`, none escaping. Protected because `out` is initialised from `v` so there is no
sentinel and no `Null`, and `settled` is the only exit. Pinned by `EvalTest` "regions that fail and
recover in sequence cost no stack", 10000 cycles, which fails on the recursive shape.

## Declared: the context an answered operation resumes with

The own-tag answer paths resume with the loop's **current** context where the baseline resumed with
the one the region was installed with. The baseline's `region` held `ctx` as a parameter that never
advanced, so a context read inside a region rebound the value for the interior and then lost it at
the next answered operation; here the loop's `ctx` carries the update forward.

The proto's own `ContextEffect` documentation says a read rebinds "the updated value for the rest of
that region's extent", which is what this does and what the baseline did not, so the change is toward
the stated contract rather than away from it. It is unobservable in this tree and therefore unpinned:
every `SuspendContext` the public surface can build carries `update(v) = v`, so no update is ever
non-identity, and a test would have to construct a node the surface cannot produce.

Raised as a declared change rather than left to be discovered.

## Ruled, not open

**Recover reads the working state.** Today `recover` is consulted with the state the extent was
installed with, and `done` with the state the loop has threaded. That split is not a contract: the
guard sits outside `region`, so the working state lives in a frame the throw has already destroyed
and `st0` is the only state in scope. With the guard at the eval, the working state is on the stack
and alive, so the honest reading is the live one, and preserving the old behaviour would mean
deliberately storing a second copy. The user's ruling:

> I can't see any scenario where we should keep the initial state. Both release and recover should
> get the current state no?

`release` already reads the current state, and does so structurally: a region only becomes
releasable by being reified into a node, and the reification writes the live state into it. So this
change makes `recover` match `release` rather than introducing a new rule.

Consequence to verify, not assume: the encoded-bracket demo grew a handler-owned `var fins`
precisely because a loop-state registry read empty at recover. Its recorded values (4221, -9, 991)
must be re-checked, and the `-1` recovery scenarios with them.

## Evidence required (tier three: this touches the evaluator)

- clean batch build, since the change could summon the lift in a kernel file;
- the three proto suites, with `ArrowEffectTest` passing at the default fork stack;
- the depth measurement repeated, showing nesting flat where it was 1426;
- the demo, with every recorded scenario value unchanged;
- the full benchmark class on both legs, back to back, in the form the skill's reporting section
  dictates. The change is on the eval's shared machinery, so every row is in scope, not a chosen
  subset.
