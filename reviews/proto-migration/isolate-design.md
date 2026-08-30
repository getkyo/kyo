# B1: Isolate on the proto, the design

The plan as ruled: `Contextual` is the automatic isolate propagating every context binding, driven
by the fork and join strategies `HandlerContext` already carries; custom `Isolate` instances are
required for `ArrowEffect` regions; composition via `andThen` and the derivation macro. This
document is the concrete translation onto the proto's machinery, read against the reference's
`Isolate.scala` (662 lines) in full.

## What the reference does, mechanically

`Isolate[Remove, Keep, Restore]` carries `State`, `Transform[_]`, and three phases:
`capture(f: State => A < S)`, `isolate(state, v): Transform[A] < (Keep & S)`,
`restore(v: Transform[A] < S): A < (Restore & S)`, plus `run`, `nest`, `apply`, `use`, `andThen`.

`Contextual extends Isolate[Any, Any, Any]` is the neutral element every fork crosses:

1. **capture**: a whole-context read (its `Bindings` node) hands back every binding and its held
   value; the walk asks each binding's `fork(held)` what the child receives, drops shadowed names,
   and freezes each crossing into a binding of the same tag holding the crossed value, with the
   origin's strategies borrowed one level deep so repeated crossings cannot stack.
2. **isolate**: attaches the frozen bindings to the fork's body (a `Park`, so evaluating the fork
   installs them first) and appends a trailing whole-context read, so the fork ends by reading its
   own final bindings back. `Transform[A]` is that pair plus the value.
3. **restore**: reads the caller's bindings again, pairs the fork's final bindings with the
   caller's by origin, asks each `join(mine, forkedFinal)`, and writes every answer out in one
   visit (`Bindings.updates` into the owning entries).

The derivation macro flattens the `Remove` intersection, skips `Keep` members and every
`ContextEffect` (Contextual covers those), summons a per-effect isolate for the rest, and folds
them with `andThen` from `Contextual`.

## The proto translation

The proto's regions are values and its context is a threaded immutable map, which dissolves most
of the reference's machinery and changes where the rest attaches.

**One invariant makes it work, and the exit law established it**: within an eval, every tag in the
context has its installing region live on the stack. Installation binds, the exit reverts or
removes, updates change values of installed tags only. So at any capture point, the (tag, value,
handler) triple for every visible binding is assembled from the stack's context entries plus the
map, innermost occurrence winning, which is the reference's shadowed-drop.

1. **One new internal node kind, the whole-context read.** The proto counterpart of the
   reference's `Bindings` read role: `Kyo.SuspendContextAll` with a continuation taking the
   assembled triples. The eval answers it in place from `ctx` and the stack. This is a node the
   reference's surface also has, so it is spec conformance rather than evaluator invention; it is
   the only new kind B1 needs.
2. **Frozen crossings are just values.** `capture` asks each triple's `handler.fork(value)`;
   what crosses is (tag, crossedValue, handler), a plain triple. No `Frozen` class, no origin
   anchoring: the strategies ride the triple, and re-freezing is re-reading a field.
3. **`isolate` wraps the body in fresh context regions.** The fork's body is the captured triples
   folded outside-in as ordinary `Kyo.Handle` values whose `HandlerContext` derives to the frozen
   value, with the trailing whole-context read inside them. Evaluating the fork anywhere, any
   thread, installs the bindings first, exactly the reference's `Park`-attachment expressed as
   values. No cross-eval context passing is needed at all: the fork's eval starts empty and the
   regions bring the bindings.
4. **`restore` writes joins back through the update machinery that already exists.** For each
   pair, `handler.join(callerValue, forkedFinal, result)`; each answer is written by an internal
   `SuspendContext` whose `update` returns the joined value, the non-identity update the kernel
   already evaluates, persisting for the extent under the exit law. The reference's origin
   matching collapses to tag matching because the proto has no duplicate live tags in the map: the
   innermost binding is the map's binding.
5. **`Isolate[Remove, Keep, Restore]`, `andThen`, `use`, `run`, `apply`** port with their
   signatures; `nest` ports with its deliberate `Kyo.lift`. The derivation macro ports nearly
   verbatim: flatten, drop `Keep` and `ContextEffect` members, summon, fold from `Contextual`.
6. **Custom isolates for `ArrowEffect` regions** are user-provided instances, as in the
   reference; the kernel ships the type, the composition, and `Contextual`. `Var.isolate`-style
   strategy objects live with the effects, above the kernel.

## What dissolves relative to the reference

- `Frozen`/origin anchoring (values carry strategies; re-freezing is free).
- `Bindings.updates`/`rebind` (write-back is the existing update node under the exit law).
- The `Park` construction in `isolate` (region values install themselves).
- `carried`/`crossedIndex`/`forkedIndex` origin searches (tag pairing suffices in a map).

## The join-scope subtlety to pin

The reference writes joins "into the scopes that own them"; the proto writes into the live
context at the restore point. These agree exactly because of the exit law: a join written after
the fork persists for the enclosing region's extent and reverts at that region's own exit, which
is the reference's origin-scoped lifetime spelled as a law rather than a pointer. The pin: a join
to a binding whose region exits afterward is gone after that exit, matching the reference.
