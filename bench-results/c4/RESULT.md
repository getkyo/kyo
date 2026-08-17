# C4: the mechanism is real, the location in the candidate is not

Candidate 8, and the only one of the ten whose deliverable is a failing test rather than a number. It
is in the list deliberately, to test whether the harness can handle such a candidate at all.

**Status: diagnosed by reading, NOT yet reproduced.** By this project's own rule that is a hypothesis,
not a result. The reproduction is the next step and has not been run.

## What C4 claims

> `Safepoint.exit` is unguarded, so every exception unwinding through a delivery leaks budget
> permanently.

## What the delivery arms actually look like

Eight sites in `Pending.scala` and `ArrowEffect.scala`, all this shape:

```scala
if !Safepoint.enter(slot) then Arrow.Bind(v, arrow.chain(next))
else
    val out = step.head(f(res), step.tail)   // arbitrary user code
    Safepoint.exit(slot)
    out
```

`enter` decrements the depth, `exit` increments it back, and a throw out of `step.head` skips the
`exit`. So far the candidate is right.

## Why guarding those eight sites is the wrong fix

It is not an oversight that they are unguarded, and the owner confirmed it is by design. A
`try`/`finally` at each of the eight would put an exception handler on the hottest path in the
kernel, at the one place per suspension that every delivery runs through. The design instead puts a
single guard at the evaluation boundary, and `Eval.partial` has exactly that:

```scala
val saved = Safepoint.save(slot)
Safepoint.arm(slot)
try loop(v.asInstanceOf[A < Any], armed = true, ...)
finally Safepoint.restore(slot, saved)
```

One handler, at the boundary, restoring unconditionally on any unwind. Every skipped `exit` inside
that drive is made whole when the throw passes through it. That is the correct shape and it is
already there.

## Where the hole is

`Eval.partial` is the *partial* path. The root entry has no such guard:

```scala
def apply[A](v: A < Any): A =
    Nested.unnest[A](loop(v, armed = false, neverStop))
```

No `save`, no `try`, no `finally`, no `reset`. Every top-level `.eval` goes through this, so a throw
that escapes the root unwinds past every skipped `Safepoint.exit` with nothing restoring the depth.

The slot is per thread (`local.set(Integer.valueOf(slot))`), so the loss is not scoped to the
computation that threw. It persists on that thread and is paid by every later, unrelated computation
scheduled onto it, which is the permanent accumulation C4 describes, reachable through the root entry
and not through the partial one.

The consequence is not a wrong answer. `enter` returning false is the ordinary budget-exhausted path
and simply parks, allocating an `Arrow.Bind` instead of taking the fast path. So the symptom is a
thread that gets progressively slower and allocates progressively more after throws, with no
correctness failure to point at, which is the kind of thing that is found years late.

## The fix

One boundary guard at `Eval.apply`, matching the one `Eval.partial` already carries. `arm` is not
wanted at the root (`armed = false` there), so it is a `save`/`finally restore`, or a `reset` in a
`finally`.

Owner's direction, verbatim: *"eval should reset it at the root of the execution if it doesn't."*

## What this says about the harness, which is why C4 is in the list

The harness has **no path for this candidate at all**. Every shape it models ends in a `Run`, a
`Comparison` and a `Verdict` over benchmark rows. A candidate whose deliverable is a failing test
produces none of those, and `bench compare` has nothing to compare.

Two honest observations rather than one:

1. The diagnosis above came from reading source, and the harness contributed nothing to it. That is
   allowed here only because the deliverable is a test, and the test has not been written yet.
2. The *symptom* is measurable and the harness can express it exactly: a leaked depth forces parks,
   parks allocate `Arrow.Bind`, and `gc.alloc.rate.norm` is exact and per-operation. A row that throws
   N times and then measures allocation would show it. So the harness could carry the confirmation
   even though it cannot carry the diagnosis.

## Next step, not yet taken

Write the reproduction in the throwaway worktree: measure the budget, throw N times through a
delivery from a root `eval`, measure again, assert it is unchanged. It must fail for the right reason
before any edit.
