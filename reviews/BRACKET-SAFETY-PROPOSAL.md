# Bracket resource safety: improvement proposal

Status: proposal. No production code has been changed. 14 failing tests are committed in
`kyo-kernel2/shared/src/test/scala/kyo/kernel/EffectTest.scala`, every one predicted before it was run.
Companion document: `reviews/BRACKET-MULTISHOT-PRIOR-ART.md`.

## Summary

Four defects in `Effect.bracket`'s resource guarantees, in descending order of how reachable they are.

| # | defect | reachability | fix |
|---|---|---|---|
| 1 | a fatal throwable runs **no release at all** | every interrupted fiber holding a bracket | build the outcome without `Result.Panic.apply`'s guard |
| 2 | a folded finalizer is lost when the value it feeds throws | plain `Effect.catching` plus a throwing map body | `dump()` bounds at any `Arrow.Region` |
| 3 | multi-shot releases after the first branch | `Choice`, `Batch.eval` over a bracketed resource | refuse at the fold for branching handlers |
| 4 | a continuation applied after its extent was released | a remainder driven past its eval | throw on traversing a spent finalizer |

Defects 1 and 2 are plain bugs with unambiguous correct behavior. Defects 3 and 4 are the multi-shot
problem, which the literature says has no complete timing-based answer; the proposal takes the dynamic
check both OCaml and Effekt independently settled on.

---

## Defect 1: a fatal throwable never releases

### Symptom

```
a fatal failure runs the release                                       List()  expected List("release")
a fatal failure runs the release and is not answered by a recovery     List()  expected List("release")
a fatal failure runs nested releases innermost first                   List()  expected List("inner", "outer")
a fatal failure thrown from a map after the acquire runs the release   List()  expected List("release")
```

Uniform: no release runs, regardless of nesting, throw site, or whether a recovery encloses it.

### Root cause

`Result.Panic.apply` rethrows a fatal rather than constructing (`kyo-data/shared/src/main/scala/kyo/Result.scala:268-272`):

```scala
def apply(exception: Throwable): Panic =
    if NonFatal(exception) then new Panic(exception)
    else throw exception
```

Every site that builds a release's outcome goes through it, so the throw happens **before** the release
runs:

- `Stack.scala:415`, `unwind`: `try f.run(Result.panic(current))`. The construction throws `current`, the
  catch's `if t ne current` guard sees the same object and does nothing, and the loop moves to the next
  entry. Every finalizer on the unwind path is skipped silently.
- `Stack.scala:442`, `escape`: identical shape, identical outcome.
- `Stack.scala:494`, `drainFinalizers`: `val outcome = Result.panic(...)` sits **outside the loop and
  outside any try**, so a fatal aborts the entire drain before the first release.

`Eval.scala:777` (`finalizeResources`) is safe: it always passes `Finalizer.Abandoned`, which is `NonFatal`.

Nothing in `kyo-data`'s `ResultTest` pins the rethrow, so the kernel depends on undocumented behavior of a
different module.

### Proposed fix

The guard exists so a fatal is never *captured as a value* and silently swallowed. Telling a release why
its extent ended is a different use: the fatal still propagates, and the release is only being informed.

Preferred: give the kernel a way to build the outcome that does not re-decide fatality, and use it at the
three sites. `new Result.Panic(ex)` bypasses the companion's guard, but relying on that is fragile and
invisible at the call site. A named kernel-internal constructor documenting why it bypasses is better.

Alternative, larger: change `Finalizer`'s release signature to carry `Throwable | Result[Nothing, B]`
rather than forcing a `Result`. This removes the dependency entirely but changes a public-facing type
(`Effect.bracket`'s release takes `Result[Nothing, B]`).

Either way `drainFinalizers` should also move its `outcome` construction inside the loop's `try`, so a
failure there cannot abort the whole drain.

### Risk

The behavior change is that fatals now reach user release code. A release that itself throws while being
told about a fatal must not replace it: the existing suppression logic (`Stack.scala:499-509`) already
handles that and is tested.

---

## Defect 2: a folded finalizer is lost when the value it feeds throws

### Symptom

```
a recovery outside the bracket runs after the release          recover | release       expected release | recover
nested brackets separated by a handler, innermost first        outer | inner           expected inner | outer
a release that throws on the completing path                   recover | outer release expected outer release | recover
a done transform that throws                                   recover | release       expected release | recover
```

Three consequences: the release does not run on the way down, it runs **after** a recovery that was
outside it, and it is told `Finalizer.Abandoned` instead of the failure. The second row **inverts
innermost-first**, which the kernel documents as an invariant.

### Root cause

`dump()`'s boundary stops only at `Handler` or `Recover` (`Stack.scala:236`). Its own comment explains why
a `Recover` must bound a fold:

> a recovery bounds a fold as a region does. Folded in, it leaves the stack, and the failure it guards
> against happens while the folded continuation's own argument is being evaluated, before anything applies
> it: the scope would be off the stack exactly when it is needed

That argument holds verbatim for a `Finalizer`, which is an `Arrow.Region` but not a `Recover`
(`Finalizer.scala:29`), so it gets folded and `head` advances past it (`Stack.scala:225`). Both
value-delivery arms then rethrow through `attachThrow` (`Eval.scala:676, 689`), which only attaches a
trace, unlike the clause-throw path which deliberately pushes the fold back first (`Eval.scala:549-553`).

A complete enumeration of `attachThrow` call sites confirms only those two lose a dumped continuation:
`Eval.scala:219, 270, 375` push `out.cont` back, `549-553` pushes and escapes, `669` has nothing to lose,
and `144` dumps nothing.

### Proposed fix

Make `dump()`'s boundary stop at any `Arrow.Region` rather than enumerating `Handler` and `Recover`. Both
of those already are `Region`s, so the predicate **shrinks**:

```scala
if i == size || i == reach || e.isInstanceOf[Arrow.Region[?, ?, ?]] then i
else boundary(i + 1)
```

The explicit `dump(pos)` overload (`Stack.scala:197`) is untouched, as it must be: a handler's continuation
has to carry the extent.

Rejected alternative: pushing `tail` back at `Eval.scala:676` and `689` before rethrowing. It works, but it
re-resolves any `Binding` through `resolveFrom` (`Stack.scala:383`) against a stack that has moved.

### Risk

Any bracket whose extent would previously have been folded into a no-arg `dump()` now bounds it instead,
so folds get shorter. That changes what a delivered value carries, and the existing suite is the check.

---

## Defect 3: multi-shot releases after the first branch

### Symptom

```
a resource shared by a multi-shot clause outlives every branch
  acquire | use 10 with 1 | release 1 | use 20 with 1
no branch of a multi-shot clause reads a resource that was already released
  branch 10 | branch 20 after release
no branch of a Choice-shaped clause reads a resource that was already released
  branch 1 | branch 2 after release | branch 3 after release
nested brackets shared by a multi-shot clause both survive every branch
  branch 10 | release inner | release outer | branch 20
no branch of a multi-shot handleFirst clause reads a resource that was already released
  branch 10 | branch 20 after release
```

The release runs **exactly once**, at the wrong time: after branch 1, while later branches still hold the
resource. Count correct, timing wrong, consequence is a use-after-release visible to user code.

### Root cause

When the use suspends, `dump(pos)` folds the `Binding` and the `Finalizer` into the continuation
(`Eval.scala:538`). A clause applying that continuation N times re-enters the extent N times against one
shared `Finalizer` instance, whose CAS makes the first branch's completion the release point.

`Finalizer.apply` (`Finalizer.scala:44-46`) equates *a value reached me* with *the extent ended*. That
holds only while the extent is linear.

### What was ruled out, with evidence

- **Fresh finalizer per re-entry**: a double free. One acquire is one obligation.
- **Re-acquire on re-entry** (Scheme `dynamic-wind`, Effekt `on resume`): tested and impossible for this
  shape. `branches of a multi-shot clause share the resource the use closed over` passes with
  `acquired == 1` and `seen == List(1, 1)`: the continuation folded from a suspension inside the use starts
  mid-use, and the user code `use` built has already closed over the resource. A fresh acquire reaches the
  binding entry, which nothing reads, because a resource binding is anonymous by construction. It works
  only where kyo already does it, when the suspension is in the **acquire**, and that case passes today
  with `List(1, 2)`.
- **Release at the eval boundary**: sound but universally late, and does not cover defect 4.
- **Release at the capturing region's completion**: refuted by
  `a branch built inside a clause does not read a released resource when it is evaluated later`.
- **Reference counting**: needs to know when the last reference is dropped, and reachability of an `Arrow`
  is not observable to the kernel. Every proxy is refutable by the same test.

### Proposed fix: refuse at the fold, for handlers that declare they may branch

Three production sites branch, all in kyo-prelude, none user-facing: `Choice.run` (`Choice.scala:100`),
`Choice.runStream` (`Choice.scala:125`, through `handleFirst`), and `Batch.eval`'s expansion
(`Batch.scala:150`). `Aspect` is **not** one: it never touches kernel handlers, and its `Cut` receives the
aspected function itself, so calling it twice re-runs the function and opens two independent extents.

A handler gains a flag:

```scala
// whether this handler may apply the continuation it is handed more than once
def branching: Boolean = false
```

`Eval.scala:536`, in the general `HandlerCont` branch only. Both fast paths above it are already safe: at
`pos == 0` nothing is folded, and the `pos == 1` path requires
`!stack.entry(0).isInstanceOf[Arrow.Region[?, ?, ?]]`, which a `Finalizer` is.

```scala
else
    if h.branching && stack.holdsResource(pos) then
        multiShotResource(kyo, stack)
    val k = stack.dump[OX[CX], AX, EX & SX](pos)
```

with a helper beside `unhandled` (`Eval.scala:141`) that attaches a trace and throws, naming the situation
and the supported alternative. And in `Stack`:

```scala
private[kernel] def holdsResource(n: Int): Boolean =
    @tailrec def loop(i: Int): Boolean =
        if i == n then false
        else
            entries((head + i) & mask) match
                case f: Finalizer[?, ?] => !f.get() || loop(i + 1)
                case _                  => loop(i + 1)
    loop(0)
```

It fires **before the clause runs**, so before branch 1: nothing partially executes and the trace points at
the `Choice.run`. Cost is a predictable boolean test per handled suspension, and a walk of at most `pos`
entries only for the three branching handlers, over the range `dump` walks immediately afterwards anyway.

### The supported alternative, which is tested

`Scope` puts its release point at a named boundary reached out-of-band through a `ContextEffect`, so it is
immune when the branching handler is nested inside it. `a bracket that encloses a multi-shot region
releases after every branch` passes. The error message should say exactly this: hold the resource with
`Scope` and run `Scope.run` outside the branching handler.

### Wrinkle

`Batch` uses one `capture` handler (`Batch.scala:136`) for both the multi-shot `ToExpand` expansion and the
one-shot `Expanded` source calls. Marking it branching refuses resources across all batching. Either the
two paths get separate handlers, or Batch accepts the conservative refusal.

### Explicitly not proposed

A structured branching combinator (`handleBranch`) that never hands out a continuation, plus
`private[kyo]` on `handleCont`/`handleFirst`. It is the correct-by-construction answer and it is what Koka
does by stratifying `val`/`fun`/`final ctl` against `ctl`/`raw ctl`. It is not proposed here because the
raw form is load-bearing for `Stream.zip`, `Sink`, `Pipe`, `Poll`/`Emit.runFirst`, `Batch` source calls and
the async boundary, all of which reify a remainder one-shot and are safe. If it ever lands it should be on
its own merits, because it also removes `Choice.run`'s recursion and both `flattenChunk` calls.

---

## Defect 4: a continuation applied after its extent was released

### Symptom

```
a branch built inside a clause does not read a released resource when it is evaluated later
  branch 10 after release
```

**One** application, not multi-shot. The clause built `cont(10)`, stashed it, returned; the region
completed with nothing in flight, the eval ended and drained, and evaluating the branch afterwards read a
freed resource.

### Why no timing rule reaches it

The remainder outlived the eval that made it. Applying a folded chain only *builds* a node
(`Arrow.scala:196-197`); entries re-install when the drive reaches that node. A holder can therefore
create a branch the kernel never sees start, and reachability is not observable. Both OCaml
(`Continuation_already_resumed`) and Effekt (`on resume { throw }`) concluded a dynamic check is the honest
floor here.

Note this is **not** how production code behaves. A remainder crossing an eval boundary via a **park**
carries its releases (`Eval.scala:455-465`: *"they belong to the computation, and this eval is ending
without finishing it, so its drain must not run them"*), which covers the async boundary. Every other
reifying use drives its remainder inside one eval.

### Proposed fix

`Finalizer.scala:44-46`:

```scala
override def apply(v: B): B < Any =
    if !compareAndSet(false, true) then throw Finalizer.Spent
    discard(Eval(release(resource, Result.succeed(v))))
    v
```

with `case object Spent` beside `Abandoned`. The CAS moves into `apply` so the test and the claim are
atomic. `run` keeps its own CAS for `unwind`, `escape` and `drainFinalizers`, which legitimately encounter
already-run finalizers and must stay silent. The `apply[C, S2]` overload needs no change.

### Risk, and the reason to sequence this last

Three currently-passing tests deliberately bless carrying on after the resource is gone and would become
errors:

- `a continuation held past the end of the eval does not release again`
- `a park evaluated twice releases its resource once`
- `abandoning a parked bracket releases with the abandoned outcome, and a later resume is harmless`

Each is a case where the resource is already released and the computation proceeds anyway, so under this
rule each is the bug. But they are deliberate and commented, so flipping them is a decision, not a fix.
If `handleCont`/`handleFirst` were ever made internal, no user code could reach those shapes and these
become internal assertions instead of a contract change.

---

## Sequencing

1. **Defect 1**, isolated to how the outcome is constructed, no interaction with the others.
2. **Defect 2**, one predicate in `dump()`. Run the full suite: it changes what folds carry.
3. **Defect 3**, the flag plus the fold check. Independent of 1 and 2.
4. **Defect 4**, last, because it is the only one that changes existing pinned behavior.

## Test plan

The 14 failing tests are the acceptance criteria; each fix flips its own group. Defect 3's five stay red as
written and get rewritten to assert the refusal. The prelude-level proofs through `Choice.run`,
`Choice.runStream` and `Batch.eval` cannot run until kyo-core compiles (`.withKyoTest`, `build.sbt:839`)
and are owed once it does.

## Open questions

- Defect 1: bypass `Panic.apply`'s guard, or change what a release is told? The second is cleaner and
  touches a public type.
- Defect 3: separate `Batch`'s two paths, or accept the conservative refusal?
- Defect 4: throw always, or only under the `Debugger`? Throwing is right for a use-after-free and costs
  three pinned behaviors.
- Should `Result.Panic.apply`'s rethrow be pinned by a test in `kyo-data`? Nothing pins it today and the
  kernel depends on it.
