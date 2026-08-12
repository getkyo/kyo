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

### B. exp/flat-arrow: reintroduce the Flat representation
Array-backed composition node. Its unique property neither chains nor batches have: a
suspension's remainder is an O(1) window (array, from, until), no per-node minting at all.
Costs to measure against history (Flat was replaced at 0.998us/1424B on runOnly): no per-site
fusion in indexed execution, and append discipline on the shared array. Bounded exploration:
Flat node + chain integration + execution + window remainder; gate the same rows.

### C. exp/batch-growth: batch schedule 1,2,4,8 (cheap control)
Bounds early-suspension waste like batch-8 while approaching fused completion. Only run if A
disappoints; expected dominated.

## Decision and deliverable
Winner by the 9-row gate table (time and allocation), tie-broken by simplicity. Then: full
20-row board against the same-session old-kernel board, JIT/assembly verification of the win
mechanism, the winning branch merged to the worktree branch, this file updated with results,
and a morning report.
