# A threaded Context for kernel2: context values and handler state in one map

Exploration. Direction: reintroduce the old kernel's threaded `Context` value,
extended to also carry the state of stateful (`LoopState`) regions, so all
per-region state lives in one place, forks copy it uniformly, and `Isolate`
becomes the whitelist over what crosses. Supersedes
`contexteffect-optional-design.md` (the `Optional`/`Maybe` patch over the
handler-based provision, now moot: the handler-based provision is deleted).

## 1. The complexity this pays off

Two mechanisms exist today only because state lives on spine cells.

**State updates path-copy the spine.** A `Loop.continue2` with changed state
runs `cell.withState` then `hs.replace(cell, updated)`, and `replace`
(Handlers.scala:164-182) is a count/fill/build array walk that path-copies
every cell above the stateful region. Every `Var.set` under N enclosing
regions allocates N+1 cells. The machinery it drags in: `StateNode`,
`withState`, `withPrev`, the throwing `state`/`withState` defaults on the
`Handlers` base, `RebuiltStateNode`, and the identity-compare fast path in
Eval's LoopState arm.

**Fork inheritance needed its own walker.** The deleted `transplant` walked
the spine rebuilding provision cells around a detached child, recognized by a
marker trait, filtered by a tag test. It could only see handler-shaped
context, so `Var` state, `Emit` accumulators, and every other `LoopState`
region were structurally invisible to a fork: prelude-level `Isolate` had to
reconstruct capture/restore per effect from outside the kernel.

And the deleted `ContextEffect` paid the price of having no queryable context
at all: `getOrElse` became an `Any`-typed default carried on every suspension,
`contains` became a probe answered by an identity-compared sentinel.

## 2. The design

The evaluator threads one more loop variable:

```scala
// internal/Context.scala, near-verbatim from the old kernel
private[kyo] opaque type Context = Map[Tag[Any], AnyRef]
```

```scala
@tailrec def loop(v: Any < Nothing, hs: Handlers, ctx: Context): Any < Nothing
```

`ctx` holds, keyed by tag:

- **Context-effect values**: `Local`'s map, `Env`'s TypeMap, `Scope`'s
  finalizer. Set by a binding region at entry, restored at exit, read by the
  evaluator directly.
- **LoopState region state**: seeded at region entry from the `HandledState`
  node, advanced by `Loop.continue2`, read at dispatch and at `applyDone`.

The entry for a tag always belongs to the innermost live region of that tag.
Shadowing (nested `Var[Int]` regions, nested `Local.let`) is save/restore: a
cell that owns a ctx entry records the previous value for its tag at push and
the pop puts it back. The saved value lives on the cell, so it is immutable
and snapshot-free.

The whole mechanism is four verbs, sequenced by Eval but owned by the cells:

| verb | where Eval calls it | what the cell does |
|---|---|---|
| **seed** | push of a `HandledState` or binding node | record `saved = ctx.get(tag)`, return `ctx.updated(tag, init)` |
| **restore** | pop (settle at region, `Loop.done`, First answer) | return `ctx` with `saved` put back (or the tag removed) |
| **unwind** | truncation (Cont/First dispatch, pending-answer re-entries) | fold **restore** over the crossed cells, `hs → cell` |
| **snapshot** | rebuild (resumer, partial residual) | rebuilt node reads its own entry from the ctx captured at dispatch |

Ownership is deliberately split this way because the entry/cell correspondence
is partial in both directions. Stateless cells (Cont, Loop, First regions) own
no entry and inherit no-op verbs. And a forked drive starts with a seeded map
and an empty spine: inherited entries have no owning cell at all (the old
kernel is identical, `IOTask.context` with no handler anywhere), which is why
the map cannot be a field of the spine or be derived from it. The two
structures also change at different rates: the spine at region entry/exit
(stack-disciplined, rare), the map per answered operation (hot). Fusing them
into one value forces either the `replace` path-copy this design deletes
(entry as an immutable cell field), broken multi-shot resumption and park
snapshots (entry as a `var`: a captured resumer shares the cell), or a wrapper
allocation per state write (map as a field of a spine holder). And
`ctx.inherit` filters the map in O(entries) precisely because the map is its
own value; entries-on-cells would make fork a spine walk, which is transplant
coming back.

So the merge is protocol-level, not representational: cells expose
`seed`/`restore`/`read`/snapshot, `Context` is defined alongside `Handlers` as
its internal detail rather than a peer abstraction, and Eval touches raw map
operations in exactly two places, seeding a drive and answering `runDetached`
with `ctx.inherit`. One file owns the region-entry correspondence; the
evaluator only sequences the verbs, which is what keeps the two threaded loop
variables from drifting.

Snapshot needs no eager copy: `ctx` is a persistent map, so capturing it in a
resumer or a pending-outcome arrow is capturing a reference, and rebuild walks
innermost-out threading a working copy (`value = working(tag); working =
restore(tag, cell.saved)`), which handles same-tag nesting for free. Re-entry
of a rebuilt node is an ordinary push and re-seeds.

### Context effects stop being operations, and reads ride Defer

With a queryable ctx in the loop, a context read is not an effect operation:
it never dispatches to a handler, so `ContextEffect` returns to the old
kernel's shape, `abstract class ContextEffect[+A] extends Effect`, not an
ArrowEffect. And a read needs no node kind of its own. The old kernel's reads
were plain `KyoDefer`, because every `KyoDefer.apply(v, context)` received the
threaded map; kernel2's equivalent is giving `Kyo.Defer` its value with ctx in
hand:

```scala
sealed trait Defer[A, +B, -S] extends Kyo[B, S]:
    def value(ctx: Context): A < S      // Effect.defer ignores it, reads use it
    def cont: Arrow[A, B, S]

// Eval's existing Defer arm, unchanged but for the argument
val next = try walk(kyo.cont, kyo.value(ctx)) catch ...
```

- `suspend(tag)` = a Defer whose value is `ctx.getOrElse(tag, bug("Unexpected pending context effect: ..."))`.
- `suspend(tag, default)` = the same with the by-name default.
- `handle(tag, value)(v)` = a binding node: seed `value`, body, restore.
- `handle(tag, ifUndefined, ifDefined)(v)` = the same node, entry value
  computed once at push from the outer entry: `ctx.get(tag)` present, apply
  `ifDefined`, absent, use `ifUndefined`. All entry-time, exactly the old
  kernel's `context.set(tag, if !context.contains(tag) then ifUndefined else ...)`.
  No probe, no sentinel, no per-read handler clause, no `Loop.continue`
  allocation per read.

All six public signatures land on the old kernel's, with `default` keeping its
name (nothing on the node is called `default` in user space).

### Fork: no detach, no node, no machinery

`Effect.detach` does not come back. It existed only because the spine-held
context was unreadable except by the evaluator, so crossing a fork boundary
needed a suspension the evaluator answered by rebuilding cells. With a
threaded map the old kernel's architecture applies verbatim
(`Isolate.internal.runDetached`, origin/main Isolate.scala:228): the fork
primitive is a plain Defer that hands the inherited slice to the fork site,

```scala
private[kyo] inline def runDetached[A, S](inline f: Context => A < S): A < S =
    new Kyo.Defer[Unit, A, S]:
        def value(ctx: Context) = f(ctx.inherit)
        def cont = ...
```

and the scheduler carries that map to a fresh drive: `Eval` and `Eval.partial`
gain a seed parameter (`ctx: Context = Context.empty`), the direct analogue of
the old kernel's `IOTask.context` feeding `handlePartial(..., context)`. The
kernel keeps zero fork machinery beyond the `inherit` filter itself.

`ctx.inherit` filters by tag, the old kernel's exact mechanism
(`NoninheritableFlag` short-circuit included, if we keep that optimization):

- context-effect entries: inherited unless the tag is `Noninheritable`.
- LoopState entries: **not inherited by default** (preserves today's
  semantics: handler state does not cross fibers implicitly).

### Isolate becomes the whitelist

The old `Isolate` scaladoc distinguishes "Simple State Copying" (ContextEffect,
copy the entry) from "Complex State Management" (capture, transform, merge
back). With handler state in the same map, both categories collapse into map
operations on entries:

- **inherit** (whitelist): an effect's isolate marks its tag inheritable, and
  the fork's `ctx.inherit` carries the entry over. `Var.isolate.update` at
  fork time is: copy the entry in, read the entry back at join, write it into
  the parent's region via a normal `Var.set`.
- **merge**: same, with a combine function at join.
- **local**: default, entry not inherited; the child's region (if any) starts
  from its own seed.

The kernel primitive Isolate needs is just: read an entry from a captured
context, and the seeding node above. The strategy zoo stays at the effect
level where it belongs.

### Handlers after the change: one cell, one region node

Taking live state off the cells unlocks a collapse of the cell and node
taxonomy, because what distinguishes `Node` / `StateNode` / `FirstNode` after
tier one (delete `replace`, `withPrev`, the `state`/`withState` refusals) is
only which `Kyo` region node their `rebuilt` produces, and what distinguishes
`Handled` / `HandledState` / `HandledFirst` is by then one field: the state
seed. Unify the region node,

```scala
trait Handled[...] extends Kyo[...]:
    def value: A < (E & S)
    def handler: Handler[...]   // any kind; dispatch already matches kinds
    def exit: Arrow[...]
    def seed: Any               // initial or snapshot state; null for stateless
```

and the tower collapses:

- Handlers.scala: `Empty` + one cell class + one rebuilt class + `find` + one
  `push`; the per-kind casting accessors vanish (~70-80 lines, from 185).
- KyoInternal.scala: `HandledState` and `HandledFirst` deleted with their
  duplicated `map` implementations (~90 lines).
- Eval.scala: three region-node arms and three push shapes become one each;
  handler-kind dispatch stays the single place the kinds differ, which is
  already Handler.scala's published vocabulary.
- The fused single allocation survives: `handleLoop` still builds
  `new Handler.LoopState with Kyo.Handled { def handler = this; def seed = state }`.

Two consequences to gate:

1. **Entry-owning cells cannot reuse the push fast path.** A parked region can
   resume in a different drive whose seeded map has a different outer entry
   for the tag, so `saved` must be recomputed at every push; re-entry
   allocates a fresh cell for entry-owning regions (stateless cells keep the
   reuse path, they save nothing). Re-entry only happens after a crossing that
   already paid a rebuild, so nothing hot changes.
2. **One extra field per region node** (`seed`, null for stateless): 8 bytes
   per `handle` call. `idleHandlerAddsNothing` and the bytecode pins gate it.

Binding regions (`ContextEffect.handle`) can ride the unified node with a null
handler, structurally unreachable by dispatch once context tags are not
raisable as operations, or get a four-line dedicated cell. The dedicated cell
is the cleaner read; either fits.

## 3. What executes, per scenario

- **`Local.get` under a `let`**: one Defer node, one map lookup, resume. No
  handler clause, no outcome box. Old-kernel speed and old-kernel shape
  (its reads were `KyoDefer`).
- **`Var.set` at its own region** (no enclosing regions entered above): map
  update (`Map1`-`Map4` node) replaces a `StateNode` alloc. A tie.
- **`Var.set` under N enclosing regions**: map update replaces N+1 cell
  allocations. The win grows with real stacks (Scope, Abort, Env layers).
- **Unchanged state** (`Loop.continue2(sameRef, a)`): identity fast path stays,
  zero writes either way.
- **Foreign crossing / park / resume**: same rebuild walk as today; the
  rebuilt state node reads its entry from the captured map instead of the cell.
- **Fork**: a Defer reads `ctx.inherit` (filter a persistent map), the fork
  site seeds the child's drive with it. No walk of the spine at all; empty map
  short-circuits to no cost.

## 4. What is deleted, what is added

Deleted from today's tree:

| piece | file |
|---|---|
| `StateNode`, `RebuiltStateNode`, `withState`, `withPrev`, base-class refusals | Handlers.scala (~45 lines) |
| `replace` and the count/fill/build path-copy | Handlers.scala (~20 lines) |
| identity-compare + `withState` + `replace` in the LoopState arm | Eval.scala |
| state re-wrap in `statePending` | Eval.scala |
| per-state-change successor allocation | the cost model itself |

Added:

| piece | file |
|---|---|
| `Context` (old kernel's, minus the `inherit`-flag if we simplify) | internal/Context.scala (~50 lines) |
| `ctx` loop variable + seed/restore/unwind/snapshot; seed parameter on `Eval`/`Eval.partial` | Eval.scala |
| `value(ctx)` on the existing `Defer` node; one new binding-node kind | KyoInternal.scala |
| `saved` field on entry-owning cells | Handlers.scala |
| `ContextEffect` on old-kernel signatures over Defer reads; `runDetached` | ContextEffect.scala |

`Effect.catching`'s guard rewraps `Defer` today by reading `kyo.value`
structurally; with `value(ctx)` it delegates the accessor instead of reading
it (`def value(ctx) = kyo.value(ctx)`), which is the same rewrap. A read
riding the Defer arm also inherits the arm's budget reset, matching the old
kernel, where every read was a suspension with safepoint handling.

With the unified region node (section 2), `HandledState` and `HandledFirst`
are deleted rather than kept: the seed rides the one `Handled` node.
`Handler.LoopState` keeps `apply` and `applyDone`; the evaluator passes
`ctx(tag)` where it passed `cell.state`.

## 5. The honest tradeoffs

**A field read becomes a map lookup.** Every LoopState dispatch reads
`ctx(tag)` (hash + tag equality on a `Map1`-`Map4`) where today it reads
`cell.state`. Every update allocates a map node where today it allocates a
`StateNode` (tie) plus the path-copy (win). The three kernels sit at three
points:

| | state read | state write | foreign crossing | fork sees state |
|---|---|---|---|---|
| old kernel | loop parameter (free) | loop parameter (free) | KyoContinue re-wrap per handler per crossing | no (Isolate rebuilds it outside) |
| kernel2 today | cell field | StateNode + path-copy above | one rebuild walk, lazy | no (transplant saw only provision) |
| proposed | map lookup | map update | one rebuild walk + unwind | yes, whitelist-filtered |

The old kernel's free state came at the cost kernel2 was built to remove (the
per-crossing continuation re-wrap). The proposal keeps kernel2's crossing
model and moves the state cost from write-side path-copy to read-side lookup.
Whether that nets positive on the flat single-region benchmark is the open
empirical question; on nested-region stacks it is a clear win.

**Exit-restore is a mechanism, not a structure.** Today "a region's state is
on its cell" is structural: nothing can forget it. The proposal introduces an
obligation: every pop restores, every truncation unwinds. This runs against
the module's headline invariant ("properties are structural, not enforced")
and must be said plainly. The mitigations: the obligation is discharged at the
evaluator's pop/truncation sites only, all in one file, and the four verbs are
symmetric enough to test exhaustively (nested same-tag regions crossing a park
is the adversarial case). The payoff is that two whole mechanisms (path-copy,
transplant) and one class of type-unsafety (`Any`-typed defaults, sentinels)
stop existing.

**Fork semantics gain power and need a default.** Handler state becoming
forkable is the feature, but the default must stay "not inherited" or every
existing `Var`-under-`Async` program changes meaning.

## 6. Benchmarks that gate this

- `statefulAnswersPaySuccessor` (today 119.8±1.7us, 1,118,168 B/op): the flat
  case, expect near-tie; regression here is the lookup cost showing.
- A new row, `statefulAnswersUnderRegions`: the same loop under 3-4 enclosing
  stateless regions, pinning the path-copy the proposal deletes. Should be
  added before the change so the win/loss is measured, not asserted.
- `userTypesSkipKernelWrapping`, `idleHandlerAddsNothing`: must not move; they
  never touch ctx (the empty-map fast path must keep them off the map).
- Context read hot path once `ContextEffect` lands: `Local.get` shape,
  compared against the old kernel's number.
- The bytecode pins (`ArrowEffectBytecodeTest`) re-derived for the new shapes.

## 7. Open questions

1. **Tag-keyed map with save/restore on cells**: confirm over the alternative
   (per-cell keys), which avoids save/restore but makes fork inheritance and
   lookup keying incoherent. Tag keys match the old kernel and make `inherit`
   a filter.
2. **`ContextEffect` back to `extends Effect`** (not ArrowEffect): reads are
   evaluator-answered, so a user-installed ArrowEffect handler over a context
   tag would be dead code. The old kernel's opacity was correct here. Confirm.
3. **LoopState inheritance flag**: where does an effect declare "my state may
   cross a fork"? Options: a marker on the effect type (like
   `Noninheritable`, but opt-in), or purely at the Isolate call site (the
   fork's isolate parameter names the tags to carry). The call-site form is
   more explicit and matches the old Isolate's per-use strategies.
4. **`Scope` fit**: Scope's finalizer entry must NOT inherit into forks
   (a child must not close the parent's resources); it is the first
   Noninheritable-by-construction LoopState-adjacent case to check.
