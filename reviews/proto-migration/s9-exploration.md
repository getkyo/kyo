# S9: the fix space, explored

Tip: `b6b1334eab`. The mark on the snapshot from `fix-design.md` was applied, reviewed live, and
reverted on one ruling: nothing captured in a computation value may be mutable. A `Kyo.Park`
and a crossing arrow capture the snapshot, so the snapshot cannot carry the fact, and neither
can any object the park holds beside it. This document re-derives the fix under that rule.

## 1. What the debt is

A crossing dumps the regions above the answering handler into a snapshot `SC` and appends `SC`
to the handler's lane. The lane entry is a debt: if nobody installs `SC`, the regions in it were
dropped with the continuation, and the handler's exit must fire their `release`. The kernel
cannot observe a dropped value, so the owner's exit is the moment it decides, and "nobody
installed `SC`" is the test. S4 made the test correct for installs on the owner's own stack.
S9 is the same test being wrong for an install that happened in another evaluation.

The fact "installed" has to travel from the installing evaluation to the owner's drain. With
values ruled out, the only carrier is the evaluator's own state. That decides the shape.

## 2. Where a park can be installed

A park over `SC` is evaluated in exactly one of these ways.

| case | where | what the owner sees today | law |
|---|---|---|---|
| a | returned by the clause, directly or inside its computation, so on the owner's stack | S4's scan settles the lane | exactly once |
| b | a nested `.eval` on the owner's thread while the owner is live: inside the clause (the S9 pin) or inside any function the owner's evaluation calls | nothing; `release` after `done` | **the defect** |
| c | another thread while the owner is live, which needs the clause to block on that thread | nothing; `release` after `done` | blocking in a clause is banned; and the owner's exit racing the install is at-least-once under any carrier short of a refusing compare-and-set |
| d | after the owner exited: stashed, or handed to the scheduler and resumed later | the owner already released; the install re-enters: `release` then `done` | entry 9, ruled kept as the raw-hook face of at-least-once |
| e | never | the owner releases at exit | correct |

So the defect is exactly case b, and case b has a structural property the others do not: the
installing evaluation runs *inside* the owner's evaluation, as a call, on the same thread,
with the owner suspended until it returns.

## 3. The candidates

**A. A settled mark on the snapshot.** Applied and reverted. Rejected by the rule above.

**B. A debt cell per crossing, carried by the park beside the snapshot.** The park is a value;
same rejection, plus an allocation per crossing on the measured row.

**C. Rule raw hooks at-least-once across evaluations and flip the S9 pin.** This would make a
nested `.eval` inside a clause a semantic event: the same deterministic program fires one hook
when the clause returns the park and two when it evaluates it. A release with no abandonment
is a phantom, not an at-least-once on an abandon edge, and finding 15 covers only the latter.

**D. The thread's evaluation chain.** The pool is thread-local and is the one place a stack is
borrowed (`Eval.apply`, the single borrow site). It records the active stack; a borrowed stack
links to the one active at its borrow; `settle` falls through to the link when its own lanes
do not hold the debt. Case b is then found by construction: every live evaluation on a thread
is an ancestor of the current one, and the ancestor is suspended, so the write into its lane
has no race. Cases a, d, e are unchanged; case c stays what it is under every carrier.

**E. One stack per thread, nested evaluations as segments above a base.** The same fact by
sharing memory instead of linking. The touchpoints are the hot path: the three `stack.isEmpty`
tests become `depth == base`; `find` must stop at `base`, one more comparison per handler probe
on every crossing, or a parameter; the eval lane (`evalOwed`, `takeEvalOwed` at three sites)
becomes per segment with a save and restore; the trace epoch becomes per segment or the
dedupe of S6 crosses evaluations; `EffectTrace` and `Isolate` read `depth`; `clear` and the
release to the pool happen only at the outermost segment. D gives the same law with none of
that on the hot path.

D is the fix.

## 4. The shape of D

```scala
// object Stack, the thread-local Pool
final private class Pool:
    private var free   = new Array[Stack](4)
    private var size   = 0
    private var active = Maybe.empty[Stack]

    def borrow(): Stack =
        val stack =
            if size == 0 then new Stack
            else
                size -= 1
                val s = free(size)
                free(size) = null
                s
        stack.enclosing = active
        active = Maybe(stack)
        stack
    end borrow

    def release(stack: Stack): Unit =
        active = stack.enclosing
        stack.clear()
        if size == free.length then free = Array.copyOf(free, size * 2)
        free(size) = stack
        size += 1
    end release
```

```scala
// class Stack
private[internal] var enclosing = Maybe.empty[Stack]     // cleared by clear()

def settle(snapshot: Stack.Snapshot): Unit =
    if !settled(snapshot) then enclosing.foreach(_.settle(snapshot))
```

where `settled` is today's `settle` returning whether a lane held the debt. `Eval.scala` does
not change. `Snapshot` stays a `Span`. Recursion depth is the nesting depth of evaluations on
the thread.

Invariants, each true by structure:

- `enclosing` is on the same thread, because the pool is thread-local, and it is suspended in
  the call that borrowed this stack, so its lanes are touched by one thread at a time.
- It is cleared in `clear()`, which every release runs, so a pooled stack retains nothing.
- The scheduler's task loop is the only `Eval.partial` (S7), and it runs at the top of its
  thread, so a task's stack has no enclosing stack; the fiber path is untouched.
- JS and Native have one thread; the same code holds trivially.

Cost: nothing on the loop. `settle` runs once per resumed crossing and falls through only on a
miss, which is case b. One field per pooled stack; `borrow` and `release` already touch the
thread-local pool, so the two extra stores are on lines that already pay the lookup.

## 5. The laws, as pins

- ContextEffectTest, the S9 pin, unchanged: `List("done cfg 1")`. Goes green.
- StackTest, new: a debt owed on the enclosing stack is settled through the borrowed one
  (borrow A, borrow B, dump on A, `B.settle` empties A's lane); a debt owed nowhere in the
  chain settles nothing.
- ContextEffectTest, new: the S9 program with the nested eval placed in a `map` function of the
  body instead of the clause, since case b is not specific to clauses.
- Entry 9 unchanged. Multi-shot unchanged: each shot is an extent, a shot that never entered an
  inner region and abandons it releases it. The per-instance law and its flipping pin from
  `fix-design.md` are dropped with the mark that motivated them.
- Case c, if pinned at all, is pinned at its honest value, `done` then `release`, as the
  cross-thread face of at-least-once. A ruling: pin it, or leave it as the ruling's text.

## 6. Left alone, on purpose

`settleIn` copies the lane with `toIndexed` on every resume. The common case is LIFO, where
the resumed snapshot is the lane's last element and `lane.last eq snapshot` followed by
`dropRight(1)` settles it with no allocation (`Chunk.last` on an `Append` is direct,
`dropRight(1)` on an `Append` returns its inner chunk). That is a separate change with its own
number on `foreignCrossingsPayRotation`, not part of the fix.
