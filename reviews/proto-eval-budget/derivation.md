# Derivation: an eval starts with its own safepoint budget

## The defect

Running the three proto suites in one JVM hangs at `ArrowEffectTest:969`, the double-boxed-value
test, spinning at 100% CPU in the `Defer` arm of the eval loop.

Pre-existing, three ways:

- the baseline hangs at the identical test when given `-Xss1g` so that it reaches it; normally the
  `StackOverflowError` ends the suite at test 17 and nothing ever gets there;
- `parallelExecution := false` hangs identically, so it is not a race between suites;
- each suite alone passes that test, so it needs a preceding suite in the same JVM.

## The mechanism

`Safepoint`'s depth guard bounds strict recursion: `enter` decrements a per-thread budget and
returns false once it is spent, at which point `map` builds a deferral instead of applying strictly.
`exit` gives the budget back on the strict path. A throw between them leaks one, and a spent budget
is only refilled by the `exit`s of enters that succeeded.

So a thread whose budget is exhausted enters a fixed point. `map` over a settled value takes
`!Safepoint.enter(slot)` and defers; the eval's settled arm applies the deferral's continuation;
that continuation is the same `map`, which checks `enter` again, still fails, and builds another
deferral. Neither side is wrong on its own and together they never terminate.

The suites accumulate leaks across tests in one JVM, which is why order matters and why one suite
alone is fine.

## The equation

An eval is a fresh evaluation. The budget bounds recursion *within* one eval, so it belongs to the
eval, not to whatever ran on the thread before it. The kernel already says this: `Safepoint.save`
installs a fresh budget and returns the old, `Safepoint.restore` puts the old one back, and **the
reference kernel's eval already does exactly this** at its boundary:

```scala
val saved = Safepoint.save(slot)
...
finally Safepoint.restore(slot, saved)
```

The proto has both operations and calls neither. This is the skill's "when stuck, read the CPS
sibling": the answer is not designed here, it is already demonstrated in the tree.

## Every piece maps to a value the kernel already has

| piece | existing value |
|---|---|
| this thread's budget slot | `Safepoint.get()` |
| a fresh budget for this eval | `Safepoint.save(slot)` |
| the caller's budget put back | `Safepoint.restore(slot, saved)` |
| the counter corrected to the true depth | `Safepoint.reset(slot)` |

Nothing new. No fork.

## Surface

`kyo/proto/kernel/internal/Eval.scala`, `apply` only:

- resolve the slot, save at entry, restore in a `finally`;
- **reset at the guard**. Saving at entry gives each eval its own budget, which fixes the leak
  crossing eval boundaries. It does not fix the leak *within* one eval: a throw leaves every strict
  application between it and the guard without its matching `exit`, so an extent that recovers many
  times drains its own budget and reaches the same fixed point. The guard is where the true recursion
  depth is known to be zero, so the counter is corrected there.

Nothing else changes, and no other file is touched.

The `finally` is what makes a nested eval leave the enclosing one's budget as it found it, and what
keeps a throw from leaking the eval's own.

## Why this is its own change

It fixes a defect that predates the region stack and is independent of it: the hang reproduces on
the baseline. Keeping them separate means each can be judged on its own, and it is why the region
stack's surface deliberately excluded this.

## Evidence required

- the three suites in one JVM, which is the thing that could not be done before;
- each suite still passing on its own, so the fix did not buy the combined run at the cost of a
  single one;
- the demo's recorded scenario values;
- clean batch build;
- the recovery path measured over a range of depths, since the within-eval leak is a scaling defect
  and a single depth cannot show it.
