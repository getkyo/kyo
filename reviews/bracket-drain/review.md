# Live review: owed dumps, the bind step, and Effect.bracket

Branch `bracket-impl`, base `3eb1c6991d` (the held-out review commit on your branch).
Worktree `.claude/worktrees/bracket-impl`. Nothing has touched your tree; every edit
below is applied there by you, one at a time, when you open this review.

## What this change fixes

Four defects, each reproduced red before its fix, each pinned green after:

1. **The dropped capture leak** (the standing red pin): a handler clause discarding its
   continuation stranded the bracket region packed inside it. Fixed by owed dumps: the
   answering entry keeps the snapshot its dump produced and drains it at its own exit.
2. **The settle-to-install strand** (held-out review section 3, confirmed live): a stop
   landing as the acquire settles made `map`'s budget gate defer the settled resource
   against its region install; the reproduction's failure message showed the exact shape,
   `Defer(7, this(acquireReleaseWith...), Id)`. Fixed by `Arrow.Bind`: the install step
   has no gate, so settle-to-open is one slice by construction. No eval change, no guard,
   no packer; the reviewer's livelock concern cannot arise because no site declines.
3. **The loop-done discard leak** (held-out review section 1, confirmed live): a
   `Loop.done` answered over live regions truncated them with no release. Fixed by the
   exit drain at the loop-done arms.
4. **Stale context across crossings** (surfaced by the pin sweep, beyond the held-out
   review): a crossing dumped the regions above the answering entry but left their
   bindings in the context map, so a clause reading a `ContextEffect` bound inside the
   body saw the inner value where its row places it outside. Fixed by the eager dump plus
   `rebound`, the context downdate mirroring the settled pop's.

Plus the design improvement you called mid-flight: **`ContextHandler.done`**. Completion
is now the fourth eval-owned edge, fired at the settled context pop in the same slice as
the pop, so no safepoint can sit between the body settling and the completion. The
bracket body is bare `use(a)`; the exit map and its gate are gone from the bracket path.

## The design in one paragraph

Every dump is owed by the entry directly below it, attached inside `Stack.dump` so no
caller can produce an unowed dump. The owed chunk is the fourth slot of the entry, on the
live stack and in every packed snapshot, so it travels wherever the entry travels: into
parks, into enclosing dumps, back onto a stack at install. It drains at every exit the
eval owns: the settled pops (after `done`), the recovery pops (with the failure), the
loop-done truncates, and the abandonment walk. An extent that dissolves while its logic
continues (the effectful-clause pop, a resumed park's remainder) re-homes what it owes
below itself (`oweBelow`), reaching the eval itself (`evalOwed` on the pooled stack) only
when nothing is below; a safepoint park transfers that into `Kyo.Park.owed`. Reachability
is the kernel's guarantee, at least once per edge; exactly-once belongs to the state,
which for `Effect.bracket` is the `Cell`'s CAS.

## Rulings applied during the build (verbatim in derivation.md)

Region-exit scoping; `done` on the handler; owe inside `dump`; `Chunk` over `List`; no
`Obligation` carrier (hot code, the sanctioned storage-boundary cast instead); no
`Discarded` class (drains take the throwable, the discard site mints a `KyoException`
behind its empty-check); `Effect.bracket` in the kernel with no Sync prototype; the
naming pass (`evalOwed`, `takeEvalOwed`, `owe`, `oweBelow`, `rebound`) after "takeRoot
sounds odd to me".

## Edit sequence for the live review

Bottom-up, one file at a time; the sentence to say when applying each:

1. **`Stack.scala`** — "The stack gains the owed slot and the eval's own owed: a fourth
   parallel array, a fourth snapshot slot, and `evalOwed`, maintained by exactly the
   sites that already maintain the other three; `dump` attaches its snapshot to the entry
   below, `pop` hands back what the entry owes, and `oweBelow` re-homes a dissolving
   extent's obligations."
2. **`KyoInternal.scala`** — "A park carries what its eval owed, default empty."
3. **`Handler.scala`** — "The context handler gains its completion edge, and both hooks
   state the at-least-once contract."
4. **`ContextEffect.scala`** — "The handle surface exposes done beside release, the
   settled fast path still completes, and the pair overload names its release argument so
   the defaults cannot misbind."
5. **`Arrow.scala`** — "The bind step: an arrow application with no budget gate, so a
   settled value and the region it owes cannot be separated by a park."
6. **`Eval.scala`** — "The eval drains what exiting entries owe and rebinds the context
   at crossings; the crossing arm splits into its lazy top tier and the eager non-top
   tier, cold bodies live behind one-call helpers, and the loop-done arms unify."
7. **`Effect.scala`** — "The bracket is kernel machinery: Cell, Finalize, and bracket on
   the companion, built on the bind step and the done edge."
8. **Delete `Sync.scala` and `SyncTest.scala`**, **add `EffectBracketTest.scala`**,
   **extend `EvalTest.scala`** — "The prototype and its test dissolve into the kernel,
   and the pins live beside what they pin."

The sequence exists as data: `sequence.py --verify` beside this file reproduces the tip
from the base byte for byte, `--list` walks it, `--show N` prints one edit.

## Adjudication

`flags.md` beside this file: 56 rows, all adjudicated, zero REMOVE. The rows worth your
eyes: F1 (the Bind class, the one genuinely new arrow shape), F39 (the storage-boundary
cast you asked about, kept per your 2026-08-29 ruling against carrier types), F42/F44
(the stack's two new mutable members and their reset protocol), and F34-F36 (the split's
duplicated arms, measured bytecode-neutral below).

## Evidence

| check | result |
|---|---|
| kernel JVM suite | 1546/1546 at the tip |
| kernel JS suite | 1499/1499 at the tip |
| kernel Native suite | 1526/1526 at the tip |
| clean batch build | passes; no suspension cascade from Bind in Arrow.scala or bracket in Effect.scala |
| red-first reproductions | dropped capture, settle strand, loop-done discard, stale context: each observed red with the right failure before its fix |
| `loop$1` bytecode | base 2197, unsplit eager variant 2508, final tree 2504: the split plus cold extraction is size-neutral against the unsplit shape while the hot top trace runs the pre-change lazy code; the +307 over base is the semantic addition itself. `recovered$1` 235 to 309. Defer and Handle arms untouched. |

**Benches were parked during the build and unparked at review time** ("how about you
launch the benchamarks in parallel?"). The A/B screening, full class at -f 1 with tip and
base back to back, is reported in the table further down; the hot-path deltas and their
gate rows:

| site | delta | rows |
|---|---|---|
| Defer arm, Handle arm, `answers` loop, `push`, top-tier crossing | none | (the hottest paths run pre-change code) |
| settled arrow exit | one owed-slot read + branch (`pop` returns the chunk) plus one call site | suspension, fused handler |
| settled context exit | the same plus one megamorphic no-op `done` call | context binding, handleInheritable |
| loop-done exits | one owed read + branch; no allocation when nothing is owed | handleLoop |
| `ContextEffect.handle` settled fast path | inline `done(derive(Absent))`; DCE expected for the default | context settled |
| non-top crossings | the dump is eager on the loop-done path too; the rebind walk per crossing | emitting, crossing |
| bracket call | equal node count by structure (flags F8); Cell per shot | bracket rows (new) |

## The pin suite (28 in EffectBracketTest, plus the EvalTest owed block: promptness,
sibling order, the raw double fire, the outer-binding read, pooled-stack reuse)

Completion order and payload, the settled fast path, use throwing during application,
exactly-once, failure payload, abandonment, park-resume, LIFO nesting, the settle strand,
loop-done discard, unguarded acquire; discard, in-clause resume, park-after-resume,
effectful-clause resume and done; discard by clause throw, the leaked capture entering
the spent extent, throwing release on the discard drain; release throwing on completion
(fails the computation, outer bracket still releases), release failure suppressed onto
the unwind failure, release failure suppressed onto the abandonment signal; bracket with
a binding in one dump, multi-shot over a bracket (released at first completion), two
parks, contextual isolate forking inert. EvalTest: drain promptness at the answering
region's exit, sibling dumps newest first, the raw-hook at-least-once double fire, the
clause reading the outer binding.

## Open questions, deliberately not decided here

1. **`Spent` re-entry refusal** (held-out review section 5): pass-through is pinned as
   the chosen-until-ruled semantics (the leaked-capture pin); the refusal hook remains
   your call.
2. **A release failure on the discard drain is swallowed** (suppressed onto the internal
   discard signal, which the drain then drops). The other three paths surface it: the
   completion path propagates it, the unwind and abandonment paths suppress it onto a
   visible throwable. Pinned as-is ("does not starve the ones after it"); whether the
   discard path should surface finalizer failures somewhere is open.
3. **`release`/`done` visibility on `ContextEffect.handle`**: the reachability law now
   supports user-facing; leaning yes, not yet ruled.
4. **The name `Bind`** (main's `BindingStep`; "install" rejected as the Park arm's verb;
   `Open` recorded as the alternative).

Prior-art notes for the layers above (lens-prior-art.md, not kernel changes): the
concurrency layer should standardize its abandonment signal throwable so release hooks
can discriminate cancellation the way ZIO and kotlinx users do; and the effectful-release
tier the deleted prototype hinted at (boundary discharge) is state-layer work, with the
incumbent as precedent.

## Held-out review reconciliation

Section 1 (loop-done leak): fixed and pinned. Section 2 (at-least-once for raw hooks):
accepted, documented on the handle scaladoc and `ContextHandler`, pinned as the
double-fire shape. Section 3 (settle strand): fixed by construction; the three
enforcement points collapse to the one gate-skip. Section 4/5 (Spent): reopened for you,
pass-through pinned. Section 6 answers adopted where applicable (LIFO both levels, no
singleton signal, kernel-internal placement, isolate confirmed and now pinned); its
eval-local-var recommendation was superseded by the pooled-stack `evalOwed` (the nested
eval functions would have lifted a local into a per-eval box), and its eval-end drain
position was superseded by your region-exit ruling, including on the recovery path.
