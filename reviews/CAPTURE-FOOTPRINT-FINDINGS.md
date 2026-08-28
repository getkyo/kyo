# Capture and footprint campaign: findings

Mission: hunt memory-footprint waste from unnecessary capturing, explore further
allocation fusion, and account for header geometry. Method: every claim below is read
off JOL layouts and the demo's allocation log (baseline `kyo-root-hierarchy-demo.txt`,
campaign `kyo-root-footprint-demo.txt`), never inferred from source. All changes live
on this branch only; the working branch is untouched (owner editing it live).

## Verified end state

Clean batch build zero warnings, all 22 demo rows produce identical results, zero
unfused applies, 1196 kernel tests green. No row allocates more; four allocate less.

Bytes allocated per row (sum of instance sizes, classic 12 B headers):

| row | before | after | delta |
|---|---|---|---|
| cont handler | 176 | 152 | -14% |
| suspend loop | 176 | 152 | -14% |
| suspend with | 64 | 56 | -13% |
| loop handler | 240 | 216 | -10% |
| trailing maps | 296 | 264 | -11% |
| handled then mapped | 200 | 176 | -12% |
| handled mapped fused | 168 | 152 | -10% |
| pending rebind | 272 | 232 | -15% |
| suspend loop mapped | 320 | 264 | -18% |
| idle handler | 104 | 96 | -8% |
| nested regions | 192 | 176 | -9% |
| foreign context | 248 | 232 | -7% |
| emitting | 544 | 464 | -15% |
| choice | 288 | 264 | -9% |
| handler stack | 272 | 224 | -18% |
| deep crossing | 400 | 360 | -10% |
| data join | 152 | 136 | -11% |
| **total (22 rows)** | **4520** | **4024** | **-11%** |

Allocation-count deltas (objects, not just bytes): trailing maps 12 to 11, pending
rebind 11 to 10, suspend loop mapped 13 to 11, emitting 21 to 19; every other row
identical.

## Change 1: the reify fusion (Eval's suspend arm)

When a suspension crosses live registers, the old path allocated a `Chain` for
`contA.chain(contB)` plus a `withCont` copy: 48 B, two objects, per reify. One
object now fulfills every role: the reified suspension captures its continuation and
both registers (`sax, k0, cA, cB`, 32 B) and composes at delivery by nested
application, `cA(k0(x, id), cB.chain(c2))`. Delivery arrives through head and tail
with an identity continuation, so `cB.chain(Id)` composes allocation-free; a non-Id
`c2` (multi-shot re-application) allocates exactly where the old Chain's delivery
did. The single-live-register case keeps the plain absorb-into-slot copy.

## Change 2: factories force fields; sites make constants code

A companion `apply(...)` must store every parameter, so nodes built through
factories carried fields for values that are constants at the call site:

- `ArrowEffect.suspend` built through the companion: 24 B, three fields, one of them
  the identity continuation. Site-built: **16 B, one field** (the input; the inline
  tag splices statically, `def cont = Arrow.id` is code). Every user suspension
  shrinks a third.
- `handleCont` / `handleLoopState` built their region through `Kyo.handle` plus the
  companion: 32 B, four fields, two of them constants (`()` state, `Id` cont), plus
  a redundant settled re-test. Site-built: **24 B** (value, handler), and the node
  carries the combinator's real frame instead of `Frame.internal`.

`Kyo.handle` itself stays for callers with runtime state and untested values (the
eval's re-entry, direct node users). `withCont` copies keep their three fields; the
continuation there is a runtime value.

## Finding: the clause-nested `$outer` (fix attempted, rejected by measurement)

`Main$$anon$54` (a map rescue born inside a handler clause) is 32 B with an
`$outer` field, the only one in all 53 classes. Mechanism: map's local `run` helper
lambda-lifts onto the enclosing class; enclosing a module that is free, but inside a
clause (an anonymous polyfunction) it lifts as an instance method and the record
captures the clause instance. That is 8 B plus a retention edge: the record keeps
the whole clause alive.

The candidate fix, a self-contained record with the strict/defer split in its own
apply and no `run` helper, was prototyped and measured: pending rebind regressed 11
to 12. The closure-continuation record is load-bearing; it pre-folds the accumulated
continuation at re-deferral where the self-contained shape pays an eval-side Chain.
Rejected; the reference kernel's map shares the run-helper shape and the same
exposure. Status: known structural cost of inline expansion inside non-module
enclosing classes, quantified, no in-kernel fix that holds the allocation floor.

## Finding: the by-name defer's frame slot

`Effect$$anon$4` carries `x$2$1`, the captured `Frame`: inherent to the non-inline
by-name `defer` (the inline path splices frames as constants). 4 B, API-shaped,
documented not changed.

## Header geometry (compressed oops, compact headers)

Sizes above use the classic compressed-oops 12 B header. Under
`-XX:+UseCompactObjectHeaders` (JEP 450 line; the flag the build already pins for
tests), measured on the same run: `Id` 16 to **8 B**, two-field records 24 to 16 B,
the four-field nodes and the fusion node 32/32 to **24 B** flush; one-field
suspensions stay 16 B (alignment). The field-elision work compounds with compact
headers: fewer fields more often lands on the 8-byte alignment exactly. The demo
run does not carry the flag by default; worth deciding whether it should.

## Proposed follow-ups (need a design ruling)

1. **Fuse the region node with its handler.** In `handleCont`/`handleLoopState` the
   handler instance exists per region node anyway; one object could be both, taking
   the pair from two allocations (24 B node + handler) to one. Requires `Handler`
   to become a trait (it is an abstract class, and the node classes already extend
   a class).
2. The demo's `cfg` builds through the `SuspendContextDefault` companion with an Id
   slot; same site-building treatment applies if wanted.
3. Integration into the working branch: pending owner review; the owner has live
   edits in flight there, so nothing was synced.
