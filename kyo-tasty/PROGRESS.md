# kyo-reflect Performance Implementation Progress

Stage 2 driving the 8-phase plan in `execution-plan-perf.md`.

Baseline: cold-load 55 ms median, snapshot 57 ms median (full kyo-bench classpath, 121 jars, 5,949 TASTy).
Targets: cold-load ≤ 25 ms, snapshot ≤ 5 ms.

## Phase table

| Phase | Name | Status | Commit | Tests | Notes |
|-------|------|--------|--------|-------|-------|
| 1 | Single-pass JAR enumeration via direct CEN reader | pending | - | 14+4 planned | - |
| 2 | Digest by jar metadata | pending | - | 5 planned | - |
| 3 | Streaming pipeline via Channels | pending | - | 8 planned | - |
| 4 | Defer toMap in AstUnpickler | pending | - | 3 planned | - |
| 5 | PositionsUnpickler Integer boxing → IntMap | pending | - | 1 planned | - |
| 6 | Interner pre-sizing | pending | - | 3 planned | - |
| 7 | sbt plugin via fork-JVM | pending | - | 2 scripted | - |
| 8 | Re-profile and verification | pending | - | full suite | - |

## Prior plans

- `execution-plan-v3.md` — completed (commit 0a7c73e81). Final verdict: PROCEED, 4 WARNs drained.
- `execution-plan-v2.md` — completed (commit 4de012f0d + kyo.Path migration).
- `execution-plan.md` (v1) — completed.

## Notes log

(none yet)
