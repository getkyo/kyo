# Overnight: AndThen chain evaluation, the general-case solution

Goal: one elegant mechanism in Eval (and if needed AndThen) that wins BOTH shapes, chains that
suspend early (trailingMaps, foreignCrossings) and chains that run to completion
(fusionAfterSuspension family), closing on the old kernel's 88us/408B per level. No kernel
rearchitecture. Composition thinking, safe by construction, no VM-style execution machinery.

## Reference numbers (all 3-fork, this session)

| row | old kernel | flatten | no-mint loop | batch-8 | recursive walk (quadratic) |
|---|---|---|---|---|---|
| fusionAfterSuspension us | 88.0 | 271.5 | 214.9 | 245.5 | 166.8 |
| fusionAfterSuspension B | 408K | 1,073K | 545K | 977K | 545K |
| trailingMaps us | 472,771 | 672 | 696 | 595 | 91,137 |
| runOnly us / B | 0.283 / 0 | 0.829 / 1,288 | 0.790 / 64 | 0.750 / 952 | 0.588 / 64 |

The recursive walk was the best completion-row variant ever measured (166.8, zero answer-time
allocation) and died only from its unwind and budget wiring: per-frame kyo.map(b) re-wrapped
suspensions one Suspend at a time, and per-frame Safepoint enters made deep accumulated trees
exhaust the budget mid-descent, Defer, and re-descend without progress. Both are wiring
defects, not properties of stack execution. The old kernel executes answered continuations
exactly this way: nested applies on the thread stack, per-site monomorphic, zero allocation;
its weakness (per-frame re-wrap on suspension) is the same one the AndThen composition can fix.

## Experiments, each on its own branch off c7ee09048e

### A. exp/stack-walk: thread-stack execution + single-composition unwind
The main candidate, per the thread-stack unlock. Eval walks the AndThen tree by recursion:
units execute through their own head(v, tail) sites (fused, zero allocation on completion).
The two defects fixed by construction:
- Unwind: a suspension returns through the frames composing ONE pending arrow,
  rest = rest.chain(b) per frame (chain nodes only, proportional to genuinely unfinished
  work), carried by a single private one-shot node that never escapes walk; the walk root
  attaches once with kyo.map(rest). No per-frame Suspend wrappers.
- Stack bound: walk frames consume no budget (the driven transforms' own enter/exit still
  bounds real nesting); a constant recursion cap (like BatchSize today) falls back to the
  linked flatten past it, which executes budget-bounded and trampolines with progress.
Verification: 547 suite, 9-row gates, PrintInlining on fusionAfterSuspension to confirm the
per-site fusion actually fires, JFR allocation attribution.

### A2. exp/stack-walk-safepoint: A with the budget as the stack bound
Same walk as A, but each recursion frame brackets Safepoint.enter/exit instead of a constant
depth cap: unified stack accounting, no magic constant. The livelock that killed the earlier
budget-integrated walk is gone by construction: its cause was the per-frame kyo.map rebuild
producing a fresh tree to re-descend; with the carrier unwind and flatten-remainder-once, an
exhausted descent defers a linked chain that executes under the transform budget with
guaranteed progress. Cost to measure: enter/exit per AndThen frame.

### D. exp/wrapper-fusion: Suspend.map builds one object
Suspend is a trait, so the map-over-suspension wrapper can extend AndThen directly:
new AndThen(cont, f) with Suspend { def cont = this }. One 24-byte object per map that is the
chain node and the suspension, correct in both roles by construction (as a value it carries
the tag for dispatch; as an arrow it means prevCont then f, which is its continuation). Build
allocation drops below the old kernel's wrapper. Layered on the winning walk variant.

### B. exp/flat-arrow: reintroduce the Flat representation
Array-backed composition node. Its unique property neither chains nor batches have: a
suspension's remainder is an O(1) window (array, from, until), no per-node minting at all.
Costs to measure against history (Flat was replaced at 0.998us/1424B on runOnly): no per-site
fusion in indexed execution, and append discipline on the shared array. Bounded exploration:
Flat node + chain integration + execution + window remainder; gate the same rows.

### C. exp/batch-growth: batch schedule 1,2,4,8 (cheap control)
Bounds early-suspension waste like batch-8 while approaching fused completion. Only run if A
disappoints; expected dominated.

### E. exp/site-fusion: one object per map over a suspension
JFR on the wrapper-fusion build attributes fusionAfterSuspension's remaining allocation gap to
the per-site mapLoop arrow objects (about 280B/level) sitting beside the fused suspension
nodes (about 264B/level); the old kernel's 408B is one KyoContinue per map unifying closure
and composition. The equivalent here: the walk calls the composition node's evalB step instead
of reading a b arrow, so the per-site class from Pending.map's Kyo arm can extend the node
directly, capture f, and set a = kyo.cont: one object per map, old-kernel build parity,
walked by the same recursion. Scope: Pending.map's Kyo arm, one node class, the walk's b step.

### Further thread-stack strategies (added as the night progresses)

- A10. In-place answering on the live stack: the deepest use of the thread stack. When the
  walk hits a suspension whose handler is a Loop or StateNode handler (the answer-producing
  kinds that never expose the continuation), ask the handler for the answer at the suspension
  point and continue executing in the still-live frames: the remainder is the stack itself,
  zero materialization, zero re-walk, old-kernel answer semantics. Not applicable to
  Handler.Cont (the handler owns the continuation and may store or multi-shot it; a
  capture-marker trick is unsound because handler code could observe the marker). Scope:
  covers handleLoop/stateful shapes, not the handle-based rows; needs the walk to reach the
  handler stack, so it touches the evalLoop/walk boundary. Conditional: only if A/A2/D leave
  a gap on the answering rows.
- F. Build-side pairing in chain(): appending a transform when the tree's b side is already a
  lone transform merges them into a linked pair, AndThen(a, Step(b, f)): units get bigger
  (more fusion per unit in the walk), the left spine gets shorter, at one extra short-lived
  node per merge. Conditional: only if walk profiles show per-unit dispatch dominating.
- Rejected on analysis: passing the remaining tree as the transform's next (mapLoop's
  next.step would flatten it, re-minting); right-leaning chain building (quadratic appends);
  capture-marker continuation stealing for Handler.Cont (unsound, marker observable).

## Decision and deliverable
Winner by the 9-row gate table (time and allocation), tie-broken by simplicity. Then: full
20-row board against the same-session old-kernel board, JIT/assembly verification of the win
mechanism, the winning branch merged to the worktree branch, this file updated with results,
and a morning report.
