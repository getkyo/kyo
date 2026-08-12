# Exception enrichment for kernel2

Design for reintroducing effect-level frames on failures in `kyo-kernel2`, replacing the old
kernel's always-on `Trace`/`TracePool` recording with enrichment performed only when an
exception is already travelling.

Codebases referenced:

- **worktree**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus`
  at `6ee19e2ebe`, branch `worktree-effervescent-painting-backus`. Paths under `kyo-kernel2/`
  and `kyo-data/` mean this tree unless stated otherwise.
- **origin/main**: the old kernel, `kyo-kernel/`, plus its downstream consumers in
  `kyo-core/` and `kyo-prelude/`.
- **historical commits**: `5d5d3e613f^` (the last commit carrying the deleted `EffectTrace`),
  `43d5c021a7` (its rename), `cc446cf27b` (bracket machinery), `c430cc3ed6`, `769632d75a`.

---

## 1. Executive summary

**Recommendation: reconstruct-at-throw, with the reconstruction performed at the small set of
kernel boundaries an exception must cross, and accumulated across nested boundaries in a
suppressed carrier that also drives the stack-trace splice.**

The load-bearing observation is that kernel2's live continuation chain is already the data an
effect-level stack trace is made of. `Arrow.Transform` carries a `Frame`
(`kyo-kernel2/shared/src/main/scala/kyo/Arrow.scala:70`), `Kyo.Suspend` carries a `Frame` and
a continuation (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala:48,49`),
and the region stack the evaluator threads holds, per cell, the handler with its `Tag`, that
region's exit arrow, and the enclosing cell
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Handlers.scala:15-22, 24-35`;
`.../internal/Handler.scala:10`). None of that is recorded for tracing purposes: it exists
because the evaluator needs it to run. A walk over it at the moment an exception is caught
reconstructs the effect-level context for free, and costs exactly nothing when nothing throws.

That is the shape the module's own guide asks for. `kyo-kernel2/CONTRIBUTING.md:31-33` states
the headline invariant: a property held by the representation cannot regress, while a property
held by a dedicated mechanism is a standing proof obligation. The deleted prototype's
`EffectTrace` was a mechanism (a carrier object with a mutable frame list, an `installed`
cursor, a 64-frame cap with drop-oldest bookkeeping, and an `attach` call to maintain at every
site that could throw, `5d5d3e613f^:kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:11-41`).
The old kernel's `Trace` was a larger one (a 16-slot ring per `Safepoint`, a two-level pool,
save/restore/copy/release lifecycles across fibers). Reconstruction replaces both with a read
of structure that already exists.

Three supporting conclusions, each argued below:

1. **The raw JVM stack trace in kernel2 is already better than it was assumed to be.** The
   per-site `map` expansion generates its `Arrow.Transform` as an anonymous class in the user's
   own compilation unit, with the user's line numbers. Verified by `javap` on the tree's
   already-compiled test classes (section 2). What the physical stack cannot show is anything
   across a suspension boundary or across a `Defer` bounce, and the region nesting the value is
   under. Those are exactly what the reconstruction supplies, so the two are complementary
   rather than redundant.
2. **Accumulate-at-catch is strictly dominated in kernel2**, because kernel2 has only two
   `try/catch` sites in the whole module (`kyo-kernel2/shared/src/main/scala/kyo/kernel/Effect.scala:19-20, 43-44`),
   and the prototype's fidelity depended on catch sites that kernel2 deliberately does not
   have (the per-transform `try/catch` in `Arrow.Step.run` and `Arrow.guardedRun` at
   `5d5d3e613f^:kyo-kernel2/shared/src/main/scala/kyo/kernel/Arrow.scala:73, 123`). Restoring
   them means a `try/catch` inside every per-site expansion, which the module explicitly
   refuses to pay for (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala:97-99`
   declines an inline `Defer.apply` on exactly that ground). Without those sites, "accumulate"
   degenerates to one frame per boundary, which is a strict subset of what reconstruction gives
   at the same boundaries.
3. **Neither architecture recovers pre-suspension history, and that is the one real capability
   the old always-on ring had.** Stated plainly in section 4.4, with its bound (the ring held
   16 frames, `origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/package.scala:8`).

One walker serves two open work items: exception enrichment, and `IOTask.fiberTrace`, whose
five tests are currently `.ignore`d with the reason "fiberTrace needs a frame walk over the
chain on the new kernel; deferred"
(`kyo-core/shared/src/test/scala/kyo/scheduler/IOTaskTest.scala:12, 38, 57, 81, 106`;
the current render is `curr.toString` at `kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:53-63`).
`iotask-kernel2-integration.md:801-814` already proposes that walk and observes that "the
residual is ordinary data and `Kyo.Suspend.frame` and `Arrow.Transform.frame` are both
reachable". This design is that walk, applied to a live failure instead of a parked residual.

---

## 2. What the physical stack already carries (measured, not assumed)

`<`'s `map` is `inline` and mints its `Arrow.Transform` at the call site
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala:27-52`). The consequence is that
the transform is an anonymous class in the *user's* compilation unit, and the step body is
lifted into a method on the *user's* class.

Inspection of the tree's already-compiled test classes
(`kyo-kernel2/jvm/target/scala-3.8.4/test-classes`, `javap -p -c -l`, no build run):

```
Compiled from "EffectTest.scala"
public final class kyo.kernel.EffectTest$$anon$1 extends kyo.Arrow$Transform<...> {
  public java.lang.Object apply(java.lang.Object, kyo.Arrow);
    Code: ... invokevirtual kyo/kernel/EffectTest.kyo$kernel$EffectTest$$_$_$run$1 ...
      LineNumberTable:
        line 54: 0
}
```

and on the user's class itself, `kyo$kernel$EffectTest$$_$_$run$1` carries a
`LineNumberTable` of eight entries, all `line 54`, which is the `map` call site in
`kyo-kernel2/shared/src/test/scala/kyo/kernel/EffectTest.scala`.

So for a strict, fused segment the JVM stack already reads as user source lines. Three things
it still cannot show, which is the entire gap enrichment must close:

1. **Anything before a suspension.** When a `Kyo.Suspend` reaches the evaluator, the drive
   answers it and applies the continuation from `Eval`'s loop
   (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:65, 83`). The physical
   frames of everything that ran before the suspension have returned.
2. **Anything before a `Defer` bounce.** The budget converts a step into a `Kyo.Defer`
   (`Pending.scala:40-42`) that the drive steps flat (`Eval.scala:93-98`), truncating the
   physical stack back to the loop.
3. **The region nesting.** A failure inside `Abort.run(Var.run(...))` shows no evaluator-level
   evidence of either handler on the physical stack; the handler cells live in the `Handlers`
   value threaded through `Eval.loop`'s second parameter (`Eval.scala:39`).

The old kernel had exactly the same property for (1) through (3), which is why it recorded a
ring. The difference is what is available to recover them: kernel2 holds the region stack and
the pending chain as ordinary data at the failure point, and the old kernel did not (its
`Context` and per-handle loops kept scope on the Java stack).

---

## 3. Attach-point inventory

Every place an exception crosses a kernel boundary in the worktree at HEAD, with what is in
scope there. "Reconstructible" means the region stack and/or a pending continuation are
reachable; "frame only" means the site can contribute a single labelled frame and nothing more.

### 3.1 `Effect.catching`, outer arm

`kyo-kernel2/shared/src/main/scala/kyo/kernel/Effect.scala:18-20`.

```scala
try guarded(v: B < (S & S2), f, _frame)
catch
    case ex: Throwable if NonFatal(ex) => f(ex)
```

In scope: `_frame` (the `catching` call site's `Frame`), `f` (the recovery function). The
guarded computation `v` has already been consumed by `guarded`, so no continuation is
reachable. **Frame only.** This is where the deleted prototype called
`EffectTrace.attach(ex, "catching", _frame)` (`5d5d3e613f^:.../kernel/Effect.scala:48`).

Note the exception then flows into `f(ex)`, which is user code. Enrichment must happen before
that call, so the user's recovery function sees an enriched exception. That is what the old
kernel did (`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/Effect.scala:59-60`,
`Safepoint.enrich(ex)` immediately preceding `f(ex)`).

### 3.2 `Effect.catching`, guard arm

`kyo-kernel2/shared/src/main/scala/kyo/kernel/Effect.scala:43-44`, inside the `Transform` the
guard installs on the continuation.

```scala
catch
    case ex: Throwable if NonFatal(ex) => f(ex)
```

In scope: `_frame`, `cont` (the guarded remainder, an `Arrow[In, B, S]`), `next` (the
downstream after the guarded computation, `Arrow[B, C, S3]`), and `v2` (the incoming value).
**Reconstructible from `cont` and `next`**: two chain walks give the steps that were about to
run inside the guard and the steps that follow it. The region stack is not in scope here (the
guard runs as an arrow, invoked from `Eval`), so the region contribution comes from the Eval
boundary below when this arm is reached through a drive.

This is the site the prototype attached `"catching"` to via the `Rotate.guard` arm
(`5d5d3e613f^:kyo-kernel2/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:190`).

### 3.3 Handler clause invocation in `Eval`'s dispatch

Three sites, one per handler kind, in `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala`:

| site | line | clause |
|---|---|---|
| stateful | 51 | `node.handler(kyo.input, node.state)` |
| loop | 73 | `h(kyo.input)` |
| continuation-passing | 92 | `loop(h(kyo.input, cont), node)` |

In scope at each: `kyo` (the `Kyo.Suspend` being answered, carrying `tag`, `input`, `frame`,
and `cont`), `node` (the handler cell: handler with its `Tag`, that region's `exit` arrow, and
`prev`), and `hs` (the full region stack). **Fully reconstructible.** The suspension's own
`frame` is the operation's source site; `kyo.cont` is the pending chain; `hs` walks outward
through every enclosing region.

The `Handler.Cont` site at line 92 is the one where a user handler can also re-enter through
the `cont` closure built at lines 90-91, so a throw from inside a resumed continuation
surfaces here too.

### 3.4 Continuation application in `Eval`'s dispatch

- `Eval.scala:98`, the `Defer` arm: `loop(walk(kyo.cont, kyo.value), hs)`.
- `Eval.scala:65, 83`, the answered-suspension arms: `loop(resume(kyo.cont, answer), hs)`.
- `Eval.scala:116-117`, the region-exit arm: `loop(resume(n.exit, v), n.prev)`.

In scope: the node (`kyo`, giving `cont` and, for a `Suspend`, `frame`) or the cell (`n`,
giving `exit` and `prev`), plus `hs`. **Fully reconstructible.** These are also where a
currency error surfaces as the `ClassCastException` the guide documents as arriving
"arbitrarily far away" (`kyo-kernel2/CONTRIBUTING.md`, currency discipline rule 2): the
`Nested.unnest` calls at `Eval.scala:55, 77` and the `@unchecked` pattern arms. For that
failure class specifically, naming the enclosing region and the pending chain is the single
most useful diagnostic the kernel can produce.

### 3.5 Unhandled suspension

Two throw sites, both constructing `IllegalStateException(s"unhandled suspension: $kyo")`:

- `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:48` (the drive, when
  `hs.find(kyo.tag)` returns `Empty` and the node is not `Defaulted` and the drive is not
  partial).
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala:140` (`eval`, when the drive
  returns a still-pending value).

In scope at `Eval.scala:48`: `kyo` and `hs`. **Fully reconstructible, and this is the highest
value site in the inventory**: the message today names the node, whose `toString` already
renders tag, position, and snippet (`KyoInternal.scala:53-54`), but says nothing about which
handlers *were* installed or where the computation was headed. Both are one walk away.

At `Pending.scala:140` only the residual value is in scope (the drive has returned and its
`hs` is gone), so it is reconstructible from the residual's own chain but not from a region
stack. In practice `Eval.scala:48` fires first for the common case.

### 3.6 The drive boundary

`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:15-21` (`apply`) and
`:23-33` (`partial`) call `evalLoop` and then `Safepoint.restore`. An exception escaping
`evalLoop` today skips the restore, which is a separate defect worth noting: `Safepoint.save`
at line 17 sets the slot to `State.init` and only the normal return path restores it
(line 19). A `try/finally` is needed here regardless of enrichment.

This is the boundary the prototype used for `EffectTrace.install`
(`5d5d3e613f^:kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:137`) and the
old kernel used for the whole trace lifecycle (`origin/main:.../internal/Trace.scala:181-188`,
`withNewTrace`, invoked from `Safepoint.eval`). **Frame only for reconstruction purposes**
(loop state has unwound), but it is the correct place to *install* the accumulated frames into
the exception, because it is the outermost kernel frame the exception crosses on its way out.

### 3.7 Future: `Kyo.Bracket` release failure

Not present at HEAD. The prototype's shape, for reference:

- `5d5d3e613f^:kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Finalize.scala:82-89`
  (`cleanup`): a release throwing while unwinding a use-failure, attached as `"release"` with
  `bracket.frame`, then `t.addSuppressed(t2)`.
- `.../Finalize.scala:96-110` (`finalizeValue`, the settled-bracket arm) and `:117-131`
  (`finalizeArrow`, the parked-region arm): releases owed by a discarded remainder, each
  attached with the bracket's frame and concatenated into the returned error chunk.

In scope at all three: the bracket node (hence `bracket.frame`) and, in the walk cases, the
remainder being finalized (hence a pending chain). **Frame plus a reconstructible remainder.**
The distinguishing requirement here is that a release failure is a *secondary* exception
suppressed onto a primary; its enrichment must not be confused with the primary's, so the
carrier attaches to the release exception itself before it is suppressed.

### 3.8 Summary table

| # | site | file:line (worktree) | in scope | class |
|---|---|---|---|---|
| 1 | `catching` outer | `kernel/Effect.scala:19-20` | `_frame` | frame only |
| 2 | `catching` guard | `kernel/Effect.scala:43-44` | `_frame`, `cont`, `next` | chain |
| 3 | stateful clause | `internal/Eval.scala:51` | `kyo`, `node`, `hs` | full |
| 4 | loop clause | `internal/Eval.scala:73` | `kyo`, `node`, `hs` | full |
| 5 | cont clause | `internal/Eval.scala:92` | `kyo`, `node`, `hs` | full |
| 6 | answered resume | `internal/Eval.scala:65, 83` | `kyo`, `hs` | full |
| 7 | defer dispatch | `internal/Eval.scala:98` | `kyo`, `hs` | full |
| 8 | region exit | `internal/Eval.scala:116-117` | `n`, `hs` | full |
| 9 | unhandled suspension (drive) | `internal/Eval.scala:48` | `kyo`, `hs` | full |
| 10 | unhandled suspension (eval) | `kernel/Pending.scala:140` | residual | chain |
| 11 | drive boundary | `internal/Eval.scala:15-21, 23-33` | nothing | install point |
| 12 | bracket release (future) | prototype `Finalize.scala:82-89, 96-110, 117-131` | bracket frame, remainder | frame plus chain |

Sites 3 through 9 are the same handful of arms in one `@tailrec` loop, and they share one
reconstruction. Implementation-wise they collapse to per-arm `try` regions that exclude the
tail call, so `@tailrec` is preserved (section 5.3).

---

## 4. Two architectures

### 4.1 (a) Accumulate-at-catch, the prototype shape

Recovered from `5d5d3e613f^:kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala`
(60 lines; earlier lineage as `KyoException` at `43d5c021a7`, and surviving in the worktree's
untracked prototypes at `kyo-kernel/shared/src/main/scala/kyo/proto3/pending.scala:701-748`,
which is byte-equivalent modulo the package filter).

Shape:

- `final private[kyo] class EffectTrace extends Exception(null, null, false, false)` with
  `var frames: Chunk[(String, Frame)]` and `var installed: Int` (lines 11-13).
- `attach(ex, op, frame)` finds the carrier among `ex.getSuppressed`, appends `(op, frame)`,
  and when the list passes `MaxFrames = 64` drops the oldest and decrements `installed`
  accordingly (lines 29-41, cap at line 21).
- `install(ex)` collapses consecutive duplicates, builds a `StackTraceElement` per surviving
  pair with a synthesized declaring-class string `op + " @ " + cls`, filters
  `kyo.kernel`-prefixed physical frames, and calls `setStackTrace(fresh ++ user)` (lines 43-57).

Call sites at that commit: `Arrow.scala:73` (inside `Step.run`'s inner loop, per transform),
`Arrow.scala:123` (`guardedRun`, per transform), `ArrowEffect.scala:190` (the guard arm),
`ArrowEffect.scala:311-312` (`traced`, wrapping the five eager handle entry points at
`391, 427, 462, 504, 617`), `Effect.scala:48` (`catching`), `Eval.scala:137` (the drive
boundary), and `Finalize.scala:87, 106, 128` (releases).

**Why it does not port.** Its fidelity came from the two `Arrow` sites: a `try/catch` around
every transform execution. In kernel2 at HEAD there is no `Arrow.Step.run` with a body to wrap
and no `guardedRun`; execution is `step.head(v, step.tail)` written into the caller's own
bytecode (`Pending.scala:44-45`, `ArrowEffect.scala:53-54`, `Eval.scala:184, 204-205`), which
is the whole point of the current arrow design. Reintroducing the per-transform catch means
either putting a `try/catch` inside every inlined per-site expansion, or reintroducing a
non-inlined dispatch method to hold it. The first inflates every user call site in every
program; the module already declines a cheaper version of that trade with the comment at
`KyoInternal.scala:97-99` ("an inline companion apply costs an Inliner cycle and residual tree
at every map call site in every program"), and `PendingBytecodeTest` exists to pin per-site
size (`kyo-kernel2/jvm/src/test/scala/kyo/kernel/PendingBytecodeTest.scala:7-11`). The second
undoes the transform-outside-map redesign (task #66) and the node-fusion work (task #64).

Reduced to the catch sites kernel2 actually has, (a) contributes exactly one `(op, frame)` pair
per boundary crossed, which is a strict subset of what (b) contributes at the same boundaries.

**Its residual mechanism cost**, if kept: a mutable carrier, a mutable cursor, a cap with
drop-oldest index arithmetic, and an `attach` obligation at every new site that can throw,
forever. `kyo-kernel2/CONTRIBUTING.md:140-152` ("When a change wants a new mechanism") is
directly on point: the carrier is a marker value that climbs, and the `installed` cursor is a
save/restore pair.

### 4.2 (b) Reconstruct-at-throw

At the catch, walk the structures the evaluator already holds and synthesize the frames from
them. Nothing accumulates during execution; nothing is recorded; the walk happens once, on a
path that is already throwing.

**What is walked, in emitted order (innermost first, matching stack-trace convention):**

1. The failing node's own frame, when the node carries one. `Kyo.Suspend.frame`
   (`KyoInternal.scala:48`) delegates to `root.frame` through `map` (`KyoInternal.scala:65`),
   so it is the *original operation's* source site, not an intermediate `map`'s. `Kyo.Defer`
   carries no frame (`KyoInternal.scala:80-82`); its continuation supplies them.
2. The node's pending continuation, head first. `Arrow.Step.head` is a `Transform` with a
   `frame` and `Arrow.Step.tail` is the rest (`Arrow.scala:48-49`); `Arrow.AndThen` decomposes
   into `a` and `b` (`Arrow.scala:105`). These are the steps that would have run next, which in
   a direct-style program are the callers.
3. For each region cell from `hs` outward: the handler's `Tag` as the region label
   (`Handler.scala:10`, `Tag.show` at `kyo-data/shared/src/main/scala/kyo/Tag.scala:161`), then
   that region's `exit` arrow walked as in (2) (`Handlers.scala:17, 26`), then `prev`
   (`Handlers.scala:18, 28`).

**Op labels come from the frame, not from a hand-written string.** `Frame` version `'1'`
encodes `calleeName`, "the syntactic name of the function whose implicit `Frame` parameter is
being filled at the macro-expansion call site"
(`kyo-data/shared/src/main/scala/kyo/Frame.scala:67-80`, the version byte at `:16`). So the
`"map"`, `"catching"`, `"handle"`, `"release"` literals the prototype passed to `attach`
(`EffectTrace.attach`'s `op` parameter, `5d5d3e613f^:.../EffectTrace.scala:29`) are already in
the frame. This removes the second half of the prototype's `(String, Frame)` pair and removes
the obligation to keep a label literal correct at each site.

**Internal frames filter themselves out.** `Frame.internal` is one shared instance and
`kyo-data/shared/src/main/scala/kyo/Frame.scala:126-135` documents the identity check for
exactly this purpose: "an identity/equality `eq`/`ne` check against the shared placeholder
identifies an internal frame, and the trace ring can skip it without an allocation". The
placeholder is used by `Arrow.identity` (`Arrow.scala:35`) and by `Eval`'s pending-outcome
re-entries (`Eval.scala:140, 151, 164`), so skipping it drops precisely the frames that carry
no user position. `Arrow.Transform.frameInfo` already applies the same skip for rendering
(`Arrow.scala:79-81`).

### 4.3 Comparison

| axis | (a) accumulate-at-catch | (b) reconstruct-at-throw |
|---|---|---|
| happy-path cost | zero *if* the catch sites already exist; per-transform `try/catch` if fidelity is wanted | zero; sites 3-9 are `try` regions added to arms of one loop |
| per-site bytecode | grows every user `map` site, if fidelity is wanted | unchanged |
| standing obligation | an `attach` call at every new throwing site, plus a correct `op` literal | none: a new arm inherits the walk if it is inside the guarded region |
| frames recovered | one per boundary crossed (in kernel2, 1 to 3 typically) | the whole pending chain plus every enclosing region, at one boundary |
| region labels | none (the prototype had no handler identity) | `Tag.show` per region |
| pre-suspension history | none (see 4.4) | none (see 4.4) |
| mutable state | carrier `frames`, `installed`, cap arithmetic | none during execution; the carrier holds only the already-reconstructed result |
| shares code with `fiberTrace` | no | yes, the same walker over a parked residual |
| CONTRIBUTING alignment | a mechanism per `:140-152` | a read of the representation per `:31-33` |

**Recommendation: (b).** The decisive argument is not performance (both are zero on the happy
path once (a) is reduced to kernel2's existing catch sites); it is that (b) has no standing
obligation and produces a strictly richer trace from the same boundaries. The second argument
is the shared walker with `fiberTrace`, which turns two deferred work items into one.

### 4.4 What is honestly lost versus the old always-on ring

The old kernel pushed a frame on every `Safepoint.enter`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:43`,
`pushFrame(frame)`), so its 16-slot ring
(`origin/main:.../internal/package.scala:8`) held the last 16 steps *including steps that ran
before an earlier suspension*, and `Trace.saved()` snapshotted that across a fiber fork
(`origin/main:.../internal/Trace.scala:33-34`; consumers at
`origin/main:kyo-core/shared/src/main/scala/kyo/Fiber.scala:768, 801, 914`).

Neither (a) nor (b) can recover that. Once a continuation arrow has been applied, the arrow is
consumed and its frame is not reachable from anything the failure can see, and the physical
stack of a resumed computation starts at the drive. So the capability difference between
"always-on recording" and "enrichment at the throw" is exactly: **the last N already-completed
steps that ran before a suspension**, bounded at 16 in the old design.

This is a real loss and it should be an explicit ruling rather than an omission. Two points
bearing on it:

- A conventional stack trace never shows already-returned calls either; it shows the active
  call chain. (b)'s output is the active chain in the effect-level sense (pending continuation
  plus enclosing regions) plus the physical chain, so the shape a reader expects is preserved.
- The old ring's 16 entries were frequently consumed by repeated frames from a tight loop,
  which is why `Trace.orderedRuns` collapses consecutive identical frames into runs
  (`origin/main:.../internal/Trace.scala:41-76`, run counts rendered as `(xN)` at `:99-105`).

### 4.5 What the old design cost on the happy path

By inspection of `origin/main`, per executed step:

- `Safepoint.enter(frame, value)` reads `state`, calls `Thread.currentThread().getId()`,
  evaluates a three-term condition including an interceptor null/flag test, increments depth,
  and calls `pushFrame(frame)` (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:30-45`).
- `pushFrame` performs a null check, an `ne Frame.internal` identity check, a masked reference
  array store, and an index increment (`.../internal/Trace.scala:111-119`). The reference array
  store carries a GC write barrier.

Per drive: `withNewTrace` borrows and releases a pooled `Trace`
(`.../internal/Trace.scala:181-188`), and `withTrace` saves and restores two fields around a
`try/catch/finally` that also folds the index into canonical range
(`.../internal/Trace.scala:148-179`).

Per fiber fork: `copyTrace` borrows a `Trace` and `arraycopy`s up to 16 references
(`.../internal/Trace.scala:138-143`), called from three sites in
`origin/main:kyo-core/shared/src/main/scala/kyo/Fiber.scala:768, 801, 914`.

Per fiber termination: `releaseTrace` clears the used prefix with `Arrays.fill` and returns the
object to the pool (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:141, 157`;
`origin/main:kyo-kernel/jvm/src/main/scala/kyo/kernel/internal/TracePool.scala:58-73`).

Standing memory: a 16-element `Frame` array per live `Trace` (`.../internal/Trace.scala:29`),
a 32-slot thread-local pool per `Safepoint` owner and an 8192-capacity global MPMC queue
(`origin/main:.../jvm/.../TracePool.scala:27-28, 31, 34`), with separate implementations for
JS/Wasm and Native (`origin/main:kyo-kernel/js-wasm/.../TracePool.scala`,
`origin/main:kyo-kernel/native/.../TracePool.scala`).

kernel2's `Safepoint.enter` takes no frame and touches one `int` array slot
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:124-133`). Adopting (b)
keeps that untouched: no field is added to `Safepoint`, no per-platform file is added, and the
`Frame` values are read only when an exception exists.

---

## 5. Feasibility of the walk against the current structures

### 5.1 The node and arrow shapes the walk must handle

`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala` and
`kyo-kernel2/shared/src/main/scala/kyo/Arrow.scala`:

| shape | walk contribution |
|---|---|
| `Kyo.Suspend` (`KyoInternal.scala:43-78`) | `frame` (`:48`, via `root` at `:51, 65`), then `cont` (`:49`) |
| `Kyo.Defer` (`:80-94`) | `cont` (`:82`); `value` is a value, not a chain |
| `Kyo.Handled` (`:106-127`) | handler `Tag` plus `exit` (`:109`); `value` is the region's inside |
| `Kyo.HandledState` (`:146-170`) | as `Handled`, plus `state` is not walked |
| `Arrow.Transform` (`Arrow.scala:68-84`) | one frame (`:70`), unless it is `identity` (`:33-40`) |
| `Arrow.Step` (`Arrow.scala:46-56`) | `head` then `tail` (`:48-49`) |
| `Arrow.AndThen` (`Arrow.scala:105-139`) | `a` then `b` |
| `Handlers.Node` (`Handlers.scala:15-22`) | handler `Tag`, `exit`, then `prev` |
| `Handlers.StateNode` (`Handlers.scala:24-35`) | same |
| `Handlers.Empty` (`Handlers.scala:13`) | terminates |

**Fused objects require a match-order rule.** Four `map` implementations mint an object that is
simultaneously an `Arrow.AndThen` and a `Kyo` node: `Suspend.map` at `KyoInternal.scala:70`,
`Defer.map` at `:88`, `Handled.map` at `:119`, `HandledState.map` at `:161`. Independently,
`ArrowEffect.handleWith` mints an object that is a `Transform`, a `Handler.Cont`, and a
`Kyo.Handled` at once (`ArrowEffect.scala:89`), and `handleLoopWith` does the same at `:139`
and `:199-200`; `suspendWith` mints a `Transform` that is also a `Kyo.Suspend`
(`ArrowEffect.scala:36`).

The rule: **the walk is entered with a declared role** (walking a value, or walking an arrow),
and matches only the shapes of that role. Walking `Suspend.map`'s product as an arrow uses
`AndThen.a`/`AndThen.b`, which is correct because as an arrow it means "the previous
continuation, then `f`". Walking the same object as a value uses `frame`/`cont`, which is
correct because as a value it is the suspension. Conflating the two double-counts.

### 5.2 The walk must not call `Arrow.step`

`Arrow.AndThen.step` materializes a flattened chain through a shared thread-local
`ArrayDeque` and clears it on entry (`Arrow.scala:42-44, 112-114`). Two reasons the enrichment
walk must not use it: it mutates a buffer another in-flight `step` on this thread could own,
and it allocates a fresh `Step` per node in the chain (`Arrow.scala:131-133`) on a path that is
handling a failure. The walk reads `AndThen.a`/`AndThen.b` and `Step.head`/`Step.tail`
directly. `Arrow.Step.toString` already sets this precedent, and says why: it "renders the
shape plus the first transform's frame only: composed chains can be arbitrarily large and
walking them from toString has broken tools that stringify values, like kyo-test"
(`Arrow.scala:53-55`).

### 5.3 Preserving `Eval`'s loop shape

`Eval.evalLoop`'s `loop` is `@tailrec` (`Eval.scala:39`) and its arms tail-call `loop` with
different `hs` (for example `:65, 68, 83, 85, 92, 98, 104, 116`). Two shapes must be avoided:

- Wrapping the whole `loop(v0, Empty)` call at `Eval.scala:118` in a `try` does not give the
  handler access to the loop's current `v`/`hs`.
- Restructuring the arms to compute a `(next, hs)` pair and tail-call once at the bottom
  reintroduces exactly the pair return whose cost the guide records: "The pair-returning
  `evalLoop` signature cost a measured +24 B on every benchmark row that entered `Eval.apply`"
  (`kyo-kernel2/CONTRIBUTING.md`, Performance).

The shape that works: **per-arm `try` regions that exclude the tail call.**

```scala
case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
    if partial && Safepoint.consumeStopped(slot) then rebuild(hs, Empty, v)
    else
        Safepoint.reset(slot)
        val next =
            try walk(kyo.cont, kyo.value)
            catch case ex: Throwable => enrich(ex, kyo, hs); throw ex
        loop(next, hs)
```

The `try` does not contain the recursive call, so `@tailrec` still applies, and `kyo` and `hs`
are ordinary parameters readable in the handler. On the JVM a `try` region with no throw costs
no instructions; the cost that is real is that the two values must be live into the handler,
which can cost a register in the compiled loop. Six such regions cover sites 3 through 9 of the
inventory. `enrich` is a single private call so the handler blocks stay small.

This is a change to `Eval.scala`, so the module's gate applies verbatim: "any change to
`Eval.scala`, the node shapes in `Kyo.scala`, or `Arrow.scala` is validated by a JMH A/B
(`jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala`) against a frozen snapshot ... Read
allocation first" (`kyo-kernel2/CONTRIBUTING.md`, Performance). Expected result: allocation
byte-identical on every row (nothing new is allocated on a non-throwing path), time within
noise. If a row moves, the per-arm regions are the suspect and the fallback is to guard fewer
arms (sites 3, 5, 9 carry most of the value).

### 5.4 The walk's own stack safety

`kyo-kernel2/CONTRIBUTING.md:115-121` requires every recursion to name its stack-safe carrier.
The walk descends `AndThen` trees, which are not tail-recursive in general, and region stacks
that `EvalTest` exercises at depth 1000000. The carrier: **the frame cap is the carrier.** The
walk is an explicit worklist loop (a local `ArrayDeque`, not the shared `Arrow.scratch`) that
terminates as soon as the emitted-frame count reaches the cap, and the cap bounds both the
worklist size and the emitted count. Depth 1000000 is not a hazard because the walk stops at
the cap long before it descends that far, provided the cap is checked before pushing, not after
popping.

---

## 6. Install semantics

### 6.1 The carrier

Keep the prototype's carrier type and name (`EffectTrace`), which was already ruled at
`43d5c021a7` ("rename the trace carrier to EffectTrace"), and keep its constructor form
`extends Exception(null, null, false, false)`
(`5d5d3e613f^:.../internal/EffectTrace.scala:11`): suppression disabled and stack trace not
writable, so it costs no `fillInStackTrace`.

Change its contents. The prototype held `Chunk[(String, Frame)]` plus `installed: Int`
(`:12-13`). The successor holds the already-synthesized elements plus a dropped count:
frames are reconstructed once per boundary and never revised, so there is no cursor to
maintain.

The carrier serves three roles and no others:

1. **Idempotence.** Its presence among `ex.getSuppressed` is the marker that this exception has
   been enriched. Same lookup the prototype used (`:30, 44`).
2. **Accumulation across nested boundaries.** See 6.2.
3. **Programmatic access.** `getMessage` renders the effect trace, as at `:14-16`. A test or a
   log sink reads it without parsing a stack trace.

### 6.2 Repeated crossings accumulate outward, and that is correct

An exception can cross several boundaries: a nested `Eval.apply` inside a handler clause, then
the outer drive, then a `catching` guard. The reconstruction at an inner drive sees only that
drive's regions, because `evalLoop` starts each drive at `Empty` (`Eval.scala:118`); the outer
drive's regions live on the outer Java frame. So a second enrichment at an outer boundary adds
information the first could not have.

Rule: **each boundary appends its reconstruction; boundaries are crossed outward, so appending
produces innermost-first order.** The number of appends is bounded by drive nesting depth plus
guard count, not by step count, which is the structural difference from the prototype's
per-step accumulation.

Duplicate suppression: the same region can appear in an inner reconstruction and an outer one
only if the same `Handlers` cell is reachable from both, which cannot happen given each drive
starts at `Empty`. Consecutive-duplicate collapsing is still worth keeping for tight loops that
push the same frame repeatedly, which is what the prototype's `collapsed` fold did (`:47-49`)
and what the old kernel's `orderedRuns` did with run counts
(`origin/main:.../internal/Trace.scala:66-73`). Prefer the old kernel's form: collapse a run to
one element and carry the count, so a `(xN)` suffix stays available to the renderer.

### 6.3 Where the frames enter the exception

Both, and for different readers:

- **`setStackTrace`**, so a printed stack trace shows them. This is what makes the enrichment
  visible to every log, every test failure, and every debugger with no cooperation from
  downstream code.
- **The suppressed carrier stays attached**, so the frames remain readable as data after the
  splice, and so the idempotence marker survives.

Splice order: **synthesized effect frames first, then the physical trace with kernel-internal
frames filtered.** That is the prototype's order
(`5d5d3e613f^:.../EffectTrace.scala:55-56`, `fresh ++ user`), and it is preferable to the old
kernel's, which searched the physical trace for a splice position by matching file name and
line number against the first synthesized element
(`origin/main:.../internal/Trace.scala:194-198`). The search is what breaks on JS (section 7),
and it has no benefit once the synthesized frames lead.

Filter predicate: the prototype used `getClassName.startsWith("kyo.kernel")`
(`5d5d3e613f^:.../EffectTrace.scala:55`). In the current layout `Arrow` lives in package `kyo`
(`kyo-kernel2/shared/src/main/scala/kyo/Arrow.scala:1`), so the predicate must cover
`kyo.kernel.` and the `kyo.Arrow` family. It must **not** cover the user's per-site anonymous
transforms, which carry the user's own class name (section 2) and are the most informative
physical frames present.

Timing: splice at the outermost boundary the exception crosses, not at each. Each boundary
appends to the carrier; the splice happens once, when the exception is about to leave the
kernel. Two exits qualify: the drive boundary (`Eval.scala:15-21, 23-33`) and
`Effect.catching`'s arms immediately before calling `f(ex)` (`Effect.scala:20, 44`), matching
the old kernel's placement of `Safepoint.enrich(ex)` before `f(ex)`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/Effect.scala:50, 59`). A second
splice on an already-spliced exception re-writes the trace with the now-longer carrier, which
is correct and idempotent in content.

### 6.4 Fatal errors and non-enrichable exceptions

Three exclusions, all present in prior art:

1. **Fatal.** `scala.util.control.NonFatal` gates every catch in the current module
   (`Effect.scala:20, 44`) and gated the prototype's guard arm
   (`5d5d3e613f^:.../ArrowEffect.scala:189`). A fatal must be neither enriched nor swallowed:
   the per-arm handlers in `Eval` are `case ex: Throwable => enrich(ex, ...); throw ex`, so
   they must test enrichability inside `enrich` and return immediately for a fatal, rather than
   filtering with a guard that would change which exceptions propagate. Rethrowing is
   unconditional either way, so control flow is unchanged for every exception.
2. **`NoStackTrace`.** The old kernel skipped it outright
   (`origin/main:.../internal/Trace.scala:191`). This matters concretely:
   `kyo.KyoException extends Exception with NoStackTrace`
   (`kyo-data/shared/src/main/scala/kyo/KyoException.scala:26`) and renders its own `frame` in
   `getMessage` (`:46-61`), so splicing a synthetic stack into it is wasted work on a value that
   deliberately has no stack. Keep the skip for the *splice*. Open question 9.3 asks whether the
   *carrier* should still attach (the frames would then be readable as data without a splice).
3. **Errors that are values, not throws.** `Abort` failures travel as `Result.Error` inside the
   computation and never reach a catch site, so enrichment does not apply to them and must not
   be expected to. Only panics and raw throws are enriched.

### 6.5 Cap policy

The prototype capped at 64 with drop-oldest and a compensating decrement of `installed`
(`5d5d3e613f^:.../EffectTrace.scala:21, 33-38`). The old kernel capped at 16 by ring
overwrite (`origin/main:.../internal/package.scala:8`).

Proposal: **one total cap, drop-newest, recorded.** The walk emits innermost first and stops at
the cap; the carrier records how many frames were not emitted. Drop-newest is the structurally
simpler policy (the walk just stops; no index arithmetic, no cursor compensation) and it keeps
the innermost frames, which are the ones a reader looks at first. The cost is that a very deep
pending chain hides the outermost regions, which is the same failure mode a truncated Java
stack has.

Value: 64, matching the prototype. Flagged as open question 9.4, since the tradeoff between
"deep chain" and "outer regions" could also be answered with a reserve (for example, cap the
chain contribution per boundary at 16 and let region cells always emit).

---

## 7. Platform notes

kernel2 declares JS, JVM, Native, and Wasm and has no platform-specific sources
(`build.sbt:772-776`; `kyo-kernel2/{js,native,wasm}` contain only `target`). This design keeps
that: the enrichment is shared code over `Throwable`, `StackTraceElement`, `setStackTrace`,
`addSuppressed`, and `getSuppressed`. That is a direct simplification over the old design,
which shipped three `TracePool` implementations
(`origin/main:kyo-kernel/{jvm,js-wasm,native}/src/main/scala/kyo/kernel/internal/TracePool.scala`).

**`StackTraceElement(String, String, String, Int)` and `setStackTrace`: proven on all four
platforms in this repository.** `origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Trace.scala:78-84`
constructs `StackTraceElement` and `:198` calls `setStackTrace`, both in *shared* source of a
module cross-built for `JSPlatform, JVMPlatform, NativePlatform, WasmPlatform`
(`build.sbt:712-729`). If they did not link, that module would not build.

**`addSuppressed`/`getSuppressed`: proven on JVM and Native, unverified on JS and Wasm.**
Native evidence: `kyo-core/native/src/main/scala/kyo/internal/UUIDEntropyPlatformSpecific.scala:95`
and its test at `kyo-core/native/src/test/scala/kyo/internal/UUIDEntropyPlatformSpecificNativeTest.scala:181`.
The worktree's prototype files use them in `kyo-kernel`'s shared source
(`kyo-kernel/shared/src/main/scala/kyo/proto3/pending.scala:721, 732`), which would imply JS
and Wasm linking, but those files exist only in this worktree and there is no evidence they
have been compiled for JS or Wasm. This is a link check, not a design risk, and it has a
fallback (open question 9.5).

**What degrades on JS.** The old `TraceTest` marks its JS cases `pendingUntilFixed` with the
reason "JS does not preserve source file/line positions, so Kyo trace frames land at the wrong
stack position" (`origin/main:kyo-kernel/shared/src/test/scala/kyo/kernel/internal/TraceTest.scala`,
the `"js"` block). Read carefully, that is a statement about the *splice position*, not about
the synthesized frames: the old design located the splice by matching a physical frame's
`getFileName` and `getLineNumber` against the first synthesized element
(`origin/main:.../internal/Trace.scala:194-196`), and on JS the physical frames carry no
usable file or line, so the match fails and the elements land in the wrong place.

The synthesized frames themselves are built from `Frame` strings produced by the macro at
compile time (`kyo-data/shared/src/main/scala/kyo/Frame.scala:245`), so they are exact on every
platform. Under the splice order recommended in 6.3 (synthesized frames first, no search) the
JS failure mode disappears: there is no position to locate. That is a reason to prefer that
order beyond aesthetics, and it means the JS cases can be real assertions rather than
`pendingUntilFixed`.

**What degrades on Native.** The physical portion of the trace depends on the binary carrying
debug information; a release build may produce sparse or absent physical frames. The
synthesized portion is unaffected. So on Native the enriched trace can be strictly more
informative than the unenriched one.

**Wasm.** Same as JS for the physical portion. No separate concern identified.

**Cross-thread reads.** The old `IOTask.fiberTrace` guarded a cross-thread read of a mutable
ring by containing any throw (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:44-53`).
The reconstruction walk reads immutable structures (`Arrow` nodes, `Kyo` nodes, `Handlers`
cells are all immutable once built), so the hazard is smaller, but the `curr` field it starts
from is mutable and the walk can observe a torn view. The existing containment at
`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:53-63` should be kept when the
walker replaces `curr.toString`.

---

## 8. Surface and tests

### 8.1 Visibility

`private[kyo]` throughout, matching the old kernel's `Trace.render`
(`origin/main:.../internal/Trace.scala:99`) and the prototype's `private[kyo] object EffectTrace`
(`5d5d3e613f^:.../EffectTrace.scala:19`). No public user API.

Rationale: the user-visible surface is the enriched exception, which needs no API. The one
cross-module consumer is `kyo-core`'s `IOTask.fiberTrace`, which is in package `kyo.scheduler`
and reachable under `private[kyo]`. A public reader can be added later if a consumer appears;
adding it now would be speculative generality, which `CLAUDE.md` (Write Clean, Simple, and Safe
Code) rules against.

File placement: `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala`,
which is where the deleted one lived (`5d5d3e613f^` path) and is consistent with the internal
package. Naming is subject to open question 9.1, since
`kyo-kernel2/CONTRIBUTING.md` (Terminology) requires a ruling for new nouns; `EffectTrace` is
not new, it was ruled at `43d5c021a7`.

### 8.2 Test file

`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EffectTraceTest.scala`, 1:1 with the
source per the root `CLAUDE.md` naming rule. It belongs in `shared` so it runs on all four
platforms; `CLAUDE.md` (All platforms, shared tests) forbids moving it to `jvm/` to dodge the
JS position problem, and section 7 argues the JS problem does not arise under the recommended
splice order.

### 8.3 Assertion shapes that do not depend on line numbers

The old `TraceTest` asserts full rendered stack traces including exact line numbers and
column-padded snippets
(`origin/main:kyo-kernel/shared/src/test/scala/kyo/kernel/internal/TraceTest.scala`, the three
`assertTrace` blocks). Every edit above those tests breaks them, which is why three of them are
`pendingUntilFixed` on JS rather than fixed. The successor should assert properties, not
renderings:

1. **Presence by callee name.** The synthesized elements include one whose method name is the
   enclosing `def`'s name and whose file name is `EffectTraceTest.scala`. Uses
   `Frame.callerName` (`kyo-data/shared/src/main/scala/kyo/Frame.scala:61-65`) and
   `Frame.Position.fileName` (`:27-28`), both stable under line edits.
2. **Order.** For a failure inside `outer { inner { boom } }`, the element for `inner` precedes
   the element for `outer`. Asserted as index comparison, not as an exact list.
3. **Region labels.** A failure under `ArrowEffect.handle(Tag[TestEffect1], ...)` produces an
   element mentioning `TestEffect1`. This is the property that has no analog in the old design
   and is the clearest statement of what reconstruction buys.
4. **The cross-suspension property, stated as a differential.** Run the same failure with and
   without a suspension between the composition and the throw. Assert that the *raw* stack
   trace of the suspending variant does not name the outer combinator, and that the *enriched*
   one does. This is the single test that would fail if enrichment silently regressed to
   "whatever the JVM gives us".
5. **No internal frames.** No synthesized element has file name `<internal>`
   (`kyo-data/shared/src/main/scala/kyo/Frame.scala:135` encodes exactly that string), which
   pins the `Frame.internal` skip.
6. **Idempotence.** Enriching through a nested drive and then the outer drive produces each
   region exactly once; assert the count of elements naming a given `Tag` is 1.
7. **Cap.** A chain deeper than the cap produces exactly cap elements, and the carrier's
   dropped count is positive. Also the stack-safety pin: the same case at depth 1000000
   completes without `StackOverflowError`, matching the depth `EvalTest` already uses
   (`kyo-kernel2/CONTRIBUTING.md:115-121` names those scenarios).
8. **Fatal pass-through.** A `VirtualMachineError` subclass propagates with an unmodified stack
   trace and no suppressed carrier, proving neither enrichment nor swallowing occurred.
9. **`NoStackTrace` pass-through.** A `KyoException` propagates with its stack trace unmodified
   (`kyo-data/shared/src/main/scala/kyo/KyoException.scala:26`).
10. **Allocation.** Not a unit test: the JMH A/B required by
    `kyo-kernel2/CONTRIBUTING.md` (Performance) for any `Eval.scala` change, with `-prof gc`,
    expecting byte-identical allocation on every row.

Downstream, the five `.ignore`d `fiberTrace` tests
(`kyo-core/shared/src/test/scala/kyo/scheduler/IOTaskTest.scala:12, 38, 57, 81, 106`) become
the acceptance criteria for the walker's second consumer, and the `Fiber` test that asserted
enriched frames on a detached carrier
(`origin/main:kyo-core/shared/src/test/scala/kyo/FiberTest.scala:725-751`) needs a successor
whose assertion is not `getClassName.contains("@")`, since that string is an artifact of the
old `toElement` format (`origin/main:.../internal/Trace.scala:78-84`).

### 8.4 What `Debug` needs

`Debug.apply` (`origin/main:kyo-prelude/shared/src/main/scala/kyo/debug/Debug.scala:25-37`)
uses only `Effect.catching` and `map`, both present in kernel2. It ports unchanged.

`Debug.trace` (`origin/main:.../Debug.scala:48-75`) installs a `Safepoint.Interceptor` whose
`enter(frame, value)` the old `Safepoint` invoked on every step
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:40`), and
propagates it across suspensions with `Safepoint.propagating`
(`origin/main:.../internal/Safepoint.scala:125`). The interceptor is ruled dead and a
per-step callback in the drive is ruled out for the same reason the stop callback was
(recorded at `iotask-kernel2-integration.md:846-852`, citing
`kernel2-preemption-design.md:72-74`, ruling 2).

**Minimal hook needed: none in the drive.** `Debug.trace` is expressible as the same one-layer
rewriting `Effect.catching`'s guard already performs: walk the head node, wrap its continuation
in a `Transform` that observes and then re-wraps, so later steps stay wrapped
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/Effect.scala:28-76`, and its own comment at
`:22-25`). `kyo-kernel2/CONTRIBUTING.md:140-152` point 4 names that guard as the precedent to
look for before inventing a mechanism.

The only kernel change that would be needed is exposing the rewrite so prelude can supply the
per-step function without prelude matching on `Kyo.Suspend`/`Defer`/`Handled` directly (those
live in `kyo.kernel.internal` and should stay there). `Effect.catching` would then be the
failure-specialized instance of that one combinator. That is a small addition with no drive
cost and no cost when unused, but it introduces a name, so it is open question 9.6 rather than
a recommendation here.

Note also that `Debug.trace`'s current placement of `Safepoint.propagating` around the whole
computation makes the tracing survive fiber boundaries; the rewrite approach makes it survive
suspensions (the wrapper rides the continuation) but not a fork onto another fiber. Whether
that matters is part of 9.6.

---

## 9. Open questions requiring a maintainer ruling

**9.1 Name and file.** `EffectTrace` in
`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala` reuses the name ruled
at `43d5c021a7`, but the successor is not the same thing (it reconstructs rather than
accumulates, and it is also the `fiberTrace` walker). `kyo-kernel2/CONTRIBUTING.md`
(Terminology) requires a ruling for any new noun. Reuse the name, or rule a new one for the
walker separate from the carrier.

**9.2 Pre-suspension history.** Section 4.4 establishes that no try/catch architecture recovers
the last N already-completed steps that ran before a suspension, which the old 16-slot ring
did. Is that an accepted loss? If not, the only mechanism that recovers it is per-step
recording, and the ruling is really a ruling on whether the ring comes back in some form.

**9.3 `NoStackTrace` and the carrier.** The old kernel skipped `NoStackTrace` exceptions
entirely (`origin/main:.../internal/Trace.scala:191`). Under this design the splice is clearly
pointless for them, but the carrier is not: attaching it would make the effect trace readable
as data on a `KyoException` without touching its stack. Skip both, or skip only the splice?

**9.4 Cap policy and value.** Section 6.5 proposes one total cap of 64 with drop-newest. The
alternative is a reserve so region cells always emit even when a deep chain fills the budget.
The prototype used 64 with drop-oldest (`5d5d3e613f^:.../EffectTrace.scala:21`); the old kernel
used 16 (`origin/main:.../internal/package.scala:8`).

**9.5 `addSuppressed` on JS and Wasm.** Section 7 has proof for JVM and Native and no proof for
JS or Wasm. If it does not link, the fallback is to hold the reconstruction only in the spliced
stack trace and drop the carrier, which costs idempotence detection (recoverable by scanning
the existing stack trace for a synthesized element) and programmatic access. Ruling needed only
if the link check fails.

**9.6 A shared rewriting combinator for `Effect`.** Section 8.4 concludes `Debug.trace` needs no
drive hook but does need the guard's rewrite exposed with a user-supplied per-step function, of
which `Effect.catching` becomes an instance. This adds one name to the kernel's public-ish
surface and changes nothing at runtime for anyone not using it. Approve the direction, or rule
that `Debug.trace` is dropped rather than ported.

**9.7 Scope of the `Eval` arms to guard.** Section 5.3 proposes six per-arm `try` regions
covering inventory sites 3 through 9. If the JMH A/B shows movement, the fallback is to guard
sites 3, 5, and 9 only (the handler clauses and the unhandled suspension), which keeps the
highest-value diagnostics and drops enrichment for throws that originate inside a continuation
already on the physical stack. Confirm the full set is wanted before measuring, so the
measurement answers the right question.

**9.8 The `Safepoint` restore leak at the drive boundary.** `Eval.apply` and `Eval.partial`
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:15-21, 23-33`) call
`Safepoint.save` and `Safepoint.restore` without a `try/finally`. `save` resets the slot to
`State.init` (`.../internal/Safepoint.scala:143-147`), so an exception escaping `evalLoop`
leaves the slot holding whatever the aborted drive left there, and the caller's budget and
armed bit are never restored. Subsequent drives on that thread then run on a wrong budget.
`partial` additionally arms the slot before the loop (`Eval.scala:28`), so an escape leaves it
armed. This is a pre-existing defect independent of enrichment, found while inventorying the
boundary. It needs a fix and a reproducing test whether or not this design lands; flagging it
here rather than silently folding it in.
