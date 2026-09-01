# Live review: owed dumps, the bind step, and Effect.bracket

Branch `bracket-impl`, base `3eb1c6991d` (the held-out review commit on your branch).
Worktree `.claude/worktrees/bracket-impl`. Nothing has touched your tree; every edit
below is applied there by you, one at a time, when you open this review.

## What this change fixes

Three defects, each reproduced red before its fix, each pinned green after:

1. **The dropped capture leak** (the standing red pin): a handler clause discarding its
   continuation stranded the bracket region packed inside it. Fixed by owed dumps: the
   answering entry keeps the snapshot its dump produced and drains it at its own exit.
2. **The settle-to-install strand** (held-out review, section 3, confirmed live): a stop
   landing as the acquire settles made `map`'s budget gate defer the settled resource
   against its region install; the reproduction's failure message showed the exact shape,
   `Defer(7, this(acquireReleaseWith...), Id)`. Fixed by `Arrow.Bind`: the install step
   has no gate, so settle-to-open is one slice by construction. No eval change.
3. **The loop-done discard leak** (held-out review, section 1, confirmed live): a
   `Loop.done` answered over live regions truncated them with no release. Fixed by the
   exit drain at the loop-done arms.

Plus one the pin sweep surfaced beyond the review: **stale context across crossings**. A
crossing dumped the regions above the answering entry but left their bindings in the
context map, so a clause reading a `ContextEffect` bound inside the body saw the inner
value where its row places it outside. Confirmed red through the public surface; fixed by
the eager dump plus a context downdate mirroring the settled pop's.

And one design improvement you called mid-flight: **`ContextHandler.done`**. The
bracket's completion was an exit map in the region's body; it is now the fourth eval-owned
edge, fired at the settled context pop in the same slice as the pop, so no safepoint can
sit between the body settling and the completion. The bracket body is bare `use(a)`; the
map and its gate are gone from the bracket path.

## The design in one paragraph

Every dump is owed by the entry directly below it (attached inside `Stack.dump`, so no
caller can produce an unowed dump). The owed chunk is the fourth slot of the entry, on
the live stack and in every packed snapshot, so it travels wherever the entry travels:
into parks, into enclosing dumps, back onto a stack at install. It drains at every exit
the eval owns: the settled pops (after `done`), the recovery pops (with the failure), the
loop-done truncates, and the abandonment walk. The one entry that dissolves while its
extent continues (the effectful-clause pop) re-homes its chunk to the enclosing entry, or
to the eval root at depth 0, which a park transfers into `Kyo.Park.owed`. Reachability is
the kernel's guarantee, at least once per edge; exactly-once belongs to the state, which
for `Effect.bracket` is the `Cell`'s CAS.

## Rulings applied during the build (verbatim in derivation.md)

- region-exit scoping ("once the handling scope ends we must release")
- `done` on the handler; owe inside `dump`; `Chunk` over `List`; no `Obligation` carrier
  (hot code, the sanctioned storage-boundary cast instead); no `Discarded` class (drains
  take the throwable; discard sites mint a `KyoException` behind their empty-checks);
  `Effect.bracket` in the kernel with no Sync prototype.

## Edit sequence for the live review

Bottom-up, one file at a time; the sentence to say when applying each:

1. **`Stack.scala`** — "The stack gains the owed slot: a fourth parallel array and a
   fourth snapshot slot, maintained by exactly the sites that already maintain the other
   three, and `dump` attaches its snapshot to the entry below so no dump can be unowed."
2. **`KyoInternal.scala`** — "A park carries what the eval itself owed, default empty."
3. **`Handler.scala`** — "The context handler gains its completion edge, and both hooks
   state the at-least-once contract."
4. **`ContextEffect.scala`** — "The handle surface exposes done beside release, the
   settled fast path still completes, and the pair overload names its release argument
   so the defaults cannot misbind."
5. **`Arrow.scala`** — "The bind step: an arrow application with no budget gate, so a
   settled value and the region it owes cannot be separated by a park."
6. **`Eval.scala`** — "The eval drains what exiting entries owe, downdates the context at
   crossings, and re-homes obligations whose owner dissolves; the loop-done arms unify
   and dropRegions disappears."
7. **`Effect.scala`** — "The bracket is kernel machinery: Cell, Finalize, and bracket on
   the companion, built on the bind step and the done edge."
8. **Delete `Sync.scala`**, **add `EffectBracketTest.scala`**, **extend `EvalTest.scala`**
   — "The prototype dissolves into the kernel, and the pins live beside what they pin."

## Adjudication

`flags.md` beside this file: 48 rows, all adjudicated, zero REMOVE. The two casts worth
your eyes: F32 (`hc.done(state.asInstanceOf[VX])`, the storage-boundary cast you asked
about, kept per your 2026-08-29 ruling against carrier types) and F1/F48 (the two
genuinely new constructs: the Bind class and the owed accessor).

## Evidence

| check | result |
|---|---|
| kernel JVM suite | 1535/1535 |
| kernel JS suite | 1489/1489 |
| kernel Native suite | running at package time; reported before the review opens |
| clean batch build (`clean` then `compile`) | passes; no suspension cascade from Bind in Arrow.scala or bracket in Effect.scala |
| red-first reproductions | dropped capture, settle strand, loop-done discard, stale context: each observed red with the right failure before its fix |

**Benches: parked by your standing instruction ("no benchs for now please"), so every
hot-path delta is disclosed, not measured.** The deltas and the rows that gate them when
benches resume:

| site | delta | rows |
|---|---|---|
| Defer arm, Handle arm, `answers` loop, `push` | none | (the hottest paths are untouched) |
| settled arrow exit | one owed-slot read + branch (`pop` returns the chunk) | suspension, fused handler |
| settled context exit | same read + one megamorphic no-op `done` call | context binding, handleInheritable |
| loop-done exits | one owed read + branch, allocation only when owed | handleLoop |
| `ContextEffect.handle` settled fast path | inline `done(derive(Absent))`, DCE expected for the default | context settled |
| non-top crossings | dump now eager on the loop-done path; downdate walk per crossing | emitting, crossing |
| bracket call | Bind replaces the map arrow (net zero); Cell per shot (was already) | bracket rows (new) |

## Open questions, deliberately not decided here

1. **`Spent` re-entry refusal** (held-out review section 5): pass-through is pinned as
   the chosen-until-ruled semantics; the refusal hook remains your call.
2. **`release`/`done` visibility on `ContextEffect.handle`**: discussed 2026-09-01; the
   reachability law now supports user-facing, leaning yes, not yet ruled.
3. **The name `Bind`** (main's `BindingStep`; "install" rejected as the Park arm's verb;
   `Open` recorded as the alternative).

## Held-out review reconciliation

Section 1 (loop-done leak): fixed and pinned. Section 2 (at-least-once for raw hooks):
accepted, documented on the handle scaladoc and `ContextHandler`, pinned as the
double-fire shape. Section 3 (settle strand): fixed by construction; the reviewer's three
enforcement points collapse to the one gate-skip, and its livelock concern cannot arise
because no site declines. Section 4/5 (Spent): reopened for you, pass-through pinned.
Section 6 answers adopted where applicable (eval-local root, LIFO both levels, no
singleton signal, kernel-internal placement, isolate confirmed untouched); the Park owed
field landed as the reviewer recommended. Its eval-end drain position was superseded by
your region-exit ruling, including on the recovery path: recovery ends the region, so its
obligations drain there.
