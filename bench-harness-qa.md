# QA plan: bench-harness live validation

Everything so far was proven against synthetic data. This validates the parts that have only
ever met captured text or nothing at all. Ordered by risk: the parsers first, because they are
the only components whose input I do not control.

Environment: worktree `bench-sweep` (throwaway, detached), control `36b41336fb`, variant
`0f9b4b69f7`, one or two fast rows at `-f 1` to keep each leg minutes rather than tens of them.

## Phase 1 — parsers against real tool output (highest risk)

| id | what | passes when |
|---|---|---|
| P1.1 | `parseJmh` on a real `-rf json -prof gc` file | rows present, scores and errors sane, `allocPerOp` populated from secondary metrics |
| P1.2 | `parseJit` on real `PrintInlining` output | non-empty, a known kernel method appears with a byte count and a verdict |
| P1.3 | `parseAlloc` on real async-profiler alloc output | non-empty, class names recognisable, bytes ordered |
| P1.4 | `parseCpu` on real async-profiler itimer output | non-empty, method names recognisable |

## Phase 2 — guards must refuse (negative tests)

| id | what | passes when |
|---|---|---|
| P2.1 | run against the primary worktree | refused, names the throwaway requirement |
| P2.2 | run against a dirty worktree | refused, lists the uncommitted paths |
| P2.3 | run with a deliberately failing test in the throwaway tree | refused before spending measurement runs |
| P2.4 | compare runs from two sessions | report leads with the not-comparable warning |
| P2.5 | subset run | no whole-class claim anywhere in the report |
| P2.6 | source edited mid-leg | leg invalidated by the tree hash |

## Phase 3 — happy path end to end

| id | what | passes when |
|---|---|---|
| P3.1 | `openSession` | drift measured and greater than zero, machine fields populated |
| P3.2 | control leg at `36b41336fb` | stored, markers all zero, rows equal to the selection |
| P3.3 | variant leg at `0f9b4b69f7`, same session | stored, markers show SuspendWith and applyFolded present |
| P3.4 | `compare` | verdicts classify, alloc deltas populated, JIT diff lists real methods |
| P3.5 | round trip | a stored run reloads and re-renders identically |

## Phase 4 — CLI surface

| id | what | passes when |
|---|---|---|
| P4.1 | `BenchRun` | stores a leg and prints its id |
| P4.2 | `BenchList` / `BenchShow` | list and detail render from disk |
| P4.3 | `BenchCompare` | renders the same report as the in-process path |

## Acceptance

Every parser non-empty on real output, every guard refusing exactly when it should and not
otherwise, and one complete comparison rendering with mechanism attribution. Anything that
fails is a harness defect and gets fixed before the tool is trusted for a decision.
