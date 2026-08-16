---
name: kernel
description: Design ethos and type-safety discipline for working on the kernel. Load before changing kernel sources, reviewing kernel code, or designing kernel features. Composition first, the evaluator second; casts and other concessions only when measured, justified, and protected.
---

# Working on the kernel

This skill captures how kernel work is done, distilled from the sessions that built the Arrow-based kernel. It is
the standard the code is held to, not a suggestion. Scope for now: design ethos, type safety, and how concessions
are made. Other areas (benchmarking workflow, porting, docs) come later.

## Composition first, the evaluator second

A kernel computation is a value built by composition. The evaluator is an accelerator for those values, not the
definition of what they mean. Every piece of evaluator behavior must be the operational reading of an equation you
can write in the public combinators; if you cannot write the equation, you do not understand the case yet, and no
amount of machine choreography will substitute.

Working rules that follow from this:

- **When the evaluator has a gap, write the equation first.** In terms of `map`, `Handle`, the segment values, the
  existing adapters. Then realize each piece with values the evaluator already has. A piece with no counterpart is
  a missing value, never a missing instruction.
- **Wanting a new node kind is the signal you are off the path.** The node kinds are the reifications of the
  combinators; a new one implies a new combinator, which is a surface decision, not an evaluator patch. The
  effectful-clause fix (see `proto-effectful-clauses.md` at the repo root) is the worked example: two designs that
  added evaluator machinery failed review; the landed fix is one `map` over the clause's outcome, rebuilding the
  region as a fresh `Handle` value, with `done` handled by the *absence* of anything to do.
- **Signatures are semantics; read the rows as region geography.** Where a computation's row places it is where it
  runs. `handleCont`'s clause returns `A < (E & S)`: it is region currency, self-re-entrant. `handleLoop`'s clause
  returns `Outcome[O[C] < (E & S), B] < S`: the clause lives *outside* the region it serves; only the answer
  payload is region currency, and `B` at row `S` is why `Loop.done` bypasses `complete`. Intersection rows are
  idempotent, so `E & (E & S)` collapses; the *position* in the signature is what distinguishes "the `E` handled
  here" from "an `E` for the region outside". Types like these decide evaluation strategy: outer-row code must run
  with the region absent (as values in hand), because dynamic tag scoping cannot skip a region that is present.
- **Fast paths specialize laws; they never replace them.** Every fast path must be observationally equivalent to
  the general equation it accelerates (the settled-outcome handler arms are `map` on an already-settled argument).
  If a fast path and the law can disagree, the fast path is wrong, whatever the benchmarks say.
- **When stuck, read the CPS sibling in kyo-kernel.** Continuation-passing style makes the composition explicit;
  the old kernel's `handleLoop` handles a suspended clause with `v.map(handleLoopLoop(_, context))`, one line,
  because its loop state is the same `Outcome` currency the clause speaks and entry equals resumption
  (`Loop.continue(v)`). If the proto needs many lines where the kernel needs one, the delta is a representation
  cost to pay knowingly, not extra semantics to invent.

## Type safety: the representation and its contract

The pending type is a union: a raw value, an `Arrow`, or a `Nested` payload. The whole kernel rests on one
representation contract:

- **Nest exactly once** at the public lift boundary (the `CanLift` emission), and only for `Boxed` values.
- **Carry opaquely**: the evaluator moves union values without inspecting payloads.
- **Unnest exactly once** at delivery (map's strict arm, handler completes, the eval wrapper).
- **Suspension continuations receive raw payloads**, never union representations. Feeding a union value into a
  suspension double-wraps; three delivery sites were fixed for exactly this, each with a failing reproduction
  first.
- **Everything handed out is a complete value**, valid in any context, any number of times. This is what makes
  the mutable stack safe: dumps reify fully, segments are immutable, replays are independent.

Conversions are not free safety. The implicit lift *re-wraps* `Boxed` values, so applying it to an
already-union-represented value corrupts it: know whether a position holds a raw or a union value before letting
a conversion fire. Removing a cast can compile and be wrong — one such removal passed the compiler and failed
nine nesting tests.

### The cast discipline

Preference ladder, in order, with the step down taken only when the step above is impossible:

1. **Variance and ascription.** The row is contravariant; `Int < Any` conforms to `Int < Ask` by ascription.
   Two casts in this codebase's history were exactly this, found by review.
2. **Typed patterns.** Bind at the needed type with `@unchecked` rather than rebinding and casting; the runtime
   test is identical and the claim is visible.
3. **The conversion**, only where semantically correct (raw value, lift-once position).
4. **A cast, justified and categorized.** Every surviving cast belongs to a closed set:
   - *Erasure-forced*: `O[A]` erased, `unnest`'s `Any => A`, array element re-typing at the storage boundary
     (`Stack`), `Tag` storage outside its opaque scope.
   - *Reference-identity knowledge*: `f eq Identity` implies `C = B`; the type system cannot carry it.
   - *Representation assertion*: "this value is already union-represented; do not lift again" at settled
     re-delivery sites. These casts actively *block* the conversion from corrupting; they are load-bearing.
   - *Evidence-backed*: a compiler-checked equality in hand (`using S =:= Any`) applied where the inliner blocks
     the typed spelling.
   - *Macro-emitted under analysis*: `CanLift`'s bare-cast arm, taken only for types that provably admit no
     `Boxed` subtype.

Rules around the ladder:

- **No look-safe indirection.** A helper that wraps a cast, or a "checked" widening whose check is vacuous
  (at `A = Any` everything inhabits the union's first arm), is worse than the cast: it hides an assertion the
  reader needs to see. Casts announce themselves; that is part of their value.
- **Necessity is verified, not argued.** A cast stays only if removing it makes the compiler error, or makes a
  test fail. Both verifications have caught confident reasoning being wrong, in both directions.
- **Opaque transparency is narrower than intuition says.** The alias is transparent in its companion
  (`object <`) and NOT in sibling objects (`object Nested`). In a non-transparent scope, a "typed" spelling can
  silently compile through the conversion instead — the cast-free `nest` would have been an infinite recursion,
  exposed only because its other arm failed to compile. Inside such scopes, the casts are the safe spelling.
- **New casts require sign-off.** Never introduce one without surfacing it; never remove an existing one without
  the compiler/test verification above.

## Concessions: measured, justified, protected

The kernel makes concessions — mutability, casts, statics, code duplication — but a concession is only
acceptable in a fixed shape: **justification (a measurement or a structural proof) + minimal scope + a protective
measure + a pinning test.** A concession missing any of the four is a defect. Standing examples of the shape:

| concession | justification | protection |
|---|---|---|
| `Loop.continue` constructors carry casts | design comment records the three bare-return designs that failed and why | `Continue` extends nothing but `Serializable`, so the lift's boxing arm is provably unreachable |
| raw values and arrows share the union | zero-allocation settled path | the `Boxed` marker closes the channel: a computation used as data is always `Nested`-wrapped, so the evaluator cannot mistake a payload for a suspension |
| the implicit lift exists at all | ergonomics of the pending type | the `CanLift` lint rejects already-pending types at concrete sites; `abortCastUnit` turns the Unit-row trap into a guided error |
| interpreter mutability (loop vars, thread-local stack) | it is the accelerator's engine room | never escapes: locals only, absence is `Maybe` never `null`, and the complete-value rule above governs everything that leaves |
| `*With` overloads copy their bodies | inline nesting measurably inflates compile time | each variant has its own tests; the copies are kept textually parallel |
| `@static` on boundary primitives | expansion sites must not capture the module (measured bytecode and compile-time cost) | known caveat: a cross-file non-inline reference to a static can fail the clean batch build; verified by clean builds, not incremental ones |

Two meta-rules bind the concessions together:

- **Reproduce before you fix, and pin what the concession could break.** Every concession that touches
  representation or replay has tests on the hostile axes: multi-shot application, capture and replay in foreign
  drives, double nesting, budget parks mid-path. A fix without a reproduction that failed for the right reason
  is not done.
- **Nothing is rolled back or accepted on feel.** Optimizations are not reverted, and regressions are not
  accepted, without measurement and explicit sign-off; probes are run one variable at a time and reported with
  their numbers.
