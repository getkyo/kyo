# Proto kernel: the lifting defect, and the nested-region stack overflow

Two separate items. The first is fixed and merged onto `worktree-effervescent-painting-backus`
(commit `31a7b4bde9`). The second is diagnosed and specified, and needs your ruling before I touch
the evaluator.

---

# 1. The lifting defect (fixed)

## Why removing `>: A` broke delivery

The bound removal changed how a raw value reaches a pending position, and the change is not uniform
across the sources. Inside `object <` the opaque type is transparent, so the compiler sees `A < S` as
`A | Pending[A, S]` and a raw `A` still conforms to it structurally: the value passes bare, with no
conversion and no nesting. Outside that companion (`Eval`, `KyoInternal`) the alias is abstract, `A`
no longer conforms, and the compiler inserts the implicit lift, which nests a payload.

So every site that relied on subsumption split into two behaviours depending on which side of the
companion it sits on, and only the sites outside the companion changed. That is why the failures were
all payload-shaped: a computation held as data acquired a second `Nested` wrapper that the single
unnest at delivery could not strip, and it surfaced as a `ClassCastException` wherever the value was
finally used.

## The three sites, in two opposite directions

| site | held | wanted | was | now |
|---|---|---|---|---|
| `Eval.scala` trailing continuation | union currency | union currency | cast to raw `A`, lift re-nested it | passes as it stands |
| `Eval.scala` settled region | union currency | raw payload for `done` | passed the union through | `Nested.unnest` once |
| `KyoInternal.scala` handler entry, settled arm | union currency | raw payload for `done` | `v.asInstanceOf[A]` | `Nested.unnest` once |

The discriminator throughout is whether the value in hand is raw or already union currency. Raw to
union is what the nest is for and the lift firing there is correct; union to raw is the unnest; union
to union must not convert at all.

## The inventory is complete, not sampled

I compiled with `-Xprint:typer` and enumerated every application of the implicit lift in the proto
sources rather than eyeballing them: Loop 9, Eval 8, ContextEffect 4, Arrow 4, ArrowEffect 3,
KyoInternal 2, Handler 1, Effect 1. Exactly one of them receives union currency (the trailing
continuation above). Every other one converts a genuinely raw value: arrow inputs, resolved context
values, the `a => a` done clauses of the no-done overloads, and unit returns from `release`. For
comparison the reference evaluator lifts twice where the proto's lifted eight times, which is what
pointed at the evaluator first.

`Effect.unitValue` carried a comment claiming the `>: A` bound; it now resolves through the lift's
primitive arm, and the comment was corrected to say so.

## Verified state

- `PendingTest` 63 of 63, green. Was 11 failures.
- `ArrowEffectTest` 88 of 88, green, when the forked JVM has stack for the one deep-nesting test.
  Was: aborted after 17 tests, which had been hiding the other 71.
- `EvalTest` 51 of 54. The three failures share one cause and are item 3 below.
- Reference `kyo.kernel.ArrowEffectTest` 168 of 168, unaffected.
- The demo runs every scenario with its recorded values intact, bracket guarantees included:
  83, -1, -1, 4221, -9, 991.

---

# 2. The nested-region stack overflow

## It is pre-existing, and that is proven rather than assumed

At `eabef556e0`, before anything in this window, `ArrowEffectTest` aborts with the identical
`StackOverflowError` after the identical 17 tests. The bound removal did not cause it; the abort was
merely hiding the other 71 tests, which is why it looked new.

## Measurements

Each measured on a thread with an explicit stack size, binary-searching the depth, ceiling 400000.

| shape | proto | reference |
|---|---|---|
| nested regions, 1 MB | 1426 | 400000, no failure |
| nested regions, 2 MB | 10290 | |
| nested regions, 4 MB | 21213 | |
| sequential operations in one region, 1 MB | 400000, no failure | |

Roughly 700 bytes of Java stack per open region. The 1 MB figure moved between runs (1804, then
1426): at that size much of the drive is still interpreted, and interpreted frames are far fatter
than compiled ones. The noise does not matter; the shape does. Proto nesting is O(depth) in Java
stack, the reference is O(1), and proto iteration is already flat.

## The mechanism

The proto evaluator holds the set of installed handlers in the Java call stack. One open region is
one live `region` frame plus the `loop` frame that entered it:

    loop(v, contA, contB, ctx)
      case Handle =>
        def region(st, v, cont, ctx) =
          loop(v, cont, Arrow.id, ctx) match      // (A) drives the interior, in scrutinee position
            case foreign suspension  => rebuild
            case own-tag suspension  => answer; region(...)      // (B) tail position
            case settled res         => handler.done(st, res)
        val res = try region(st0, kyo.value, Arrow.id, bound)    // (C) guard spans the extent
                  catch { recover }
        loop(res, kyo.cont, contA.chain(contB), ctx)

(B) is why sequential operations are flat: answering an operation and continuing is a genuine tail
call, so one region iterates without growing the stack. (A) is why nesting is not: the interior is
driven in scrutinee position, so the frame must stay live to inspect the result and apply `done`,
and an inner `Handle` reached from there recurses into its own `region`. (C) pins it further, since
the guard has to remain active for the whole extent.

The reference has no `region` method at all. It runs one flat `@tailrec def loop(curr)` over an
explicit heap `Stack`: entering a region pushes an entry, a suspension does `stack.find(tag)` to
locate its handler, and a settled value pops and applies the completion. Its nesting depth is bounded
by heap rather than by stack.

## Why there is no smaller fix

The installed-handler set is a dynamic scope whose size is the nesting depth, so it is unbounded. Any
representation of it in the Java call stack is bounded by the Java stack; the only way to remove the
bound is to move the scope to the heap. A trampoline that returns "I reached a Handle" to an outer
driver still has to remember the chain of pending regions, which is that heap stack under another
name. So this is not a case where a cheaper variant trades correctness for scope: there is one fix,
and it is the reference's design.

## What the fix touches

The port has a proven target in `kyo/kernel/internal/{Eval,Stack}.scala`, but it is a rewrite of the
proto evaluator's core, and these five pieces are the substance of it:

1. The drive becomes one flat `@tailrec` loop over a region stack; `region` disappears.
2. The extent guards stop being Java `try`/`catch` around a recursive call and become explicit
   unwinding over that stack, consulting each region's `recover` with its install-time state,
   innermost first. That is the semantics you directed this session, re-expressed on a new carrier.
3. `ctx` stops being a parameter restored by frame exit and is saved per entry, restored on pop.
4. The foreign-suspension `reenter` path closes over `st`, `handler`, and `ctx` today; those come off
   the stack entry instead.
5. `release` composition on abandonment moves onto the same unwinding.

It also changes the allocation profile, which is the property you have been reviewing node by node,
so it needs re-measurement against the JMH rows rather than a compile-and-tests pass.

## What I recommend, and the ruling I need

Do it, and port the reference's structure rather than inventing a third one: the semantics are
already settled, the target exists in-tree, and the kernel guidance is explicit that when the proto
needs many lines where the reference needs one, the delta is a representation cost to pay knowingly.

I have not started it because it rewrites the machine you have been designing with me and it moves
the guard semantics you ruled on directly onto a new carrier. Say go and I will take it, in one
piece, with the JMH rows measured before and after.

---

# 3. The eval boundary does not reject an unhandled suspension

The three remaining `EvalTest` failures are one cause: `Eval.apply` returns an unanswered suspension
instead of rejecting it. `<.eval` then hands the caller a `Pending` node typed as a value, and it
fails as a `ClassCastException` at an arbitrary later point rather than at the boundary that could
name it.

The reference's entry is `Eval.apply[A, S](v: A < S): A` and it throws
`Unexpected pending effect while handling <tag>`. The proto's returns `A < S`, which is what makes
passing the suspension through expressible. In main sources the only caller is `<.eval` at row `Any`,
so narrowing the contract would not disturb anything there.

This is the unhandled-suspension fork already on the open list, and it carries two decisions: whether
`Eval.apply` narrows to returning raw `A` as the reference does, and what the message says (the
ported tests assert "unhandled suspension", the reference says "Unexpected pending effect"). Both
change a contract, so they are yours.
