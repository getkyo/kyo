# Progress ; kyo-jsonrpc API cleanup

Append-only log. One entry per Stage 1/2/3 milestone.

## Stage 1 (project-once chain)

- A1-A4 produced (kyo-http template + current state + consumer footprint + naming/nesting analysis) ; manual, pre-/flow-launch.
- C-cleanup-plan.md (first-pass consolidated) ; manual.
- D1-D4 fork resolutions (policies / framer-wiretransport / jsonrpc-error / transport-jvm) ; manual via parallel research agents.
- D5-final-plan.md ; first folded consolidation (16 top-level shape).
- User challenge: "16 is a lot. What's really user facing?"
- D6-revised-nesting.md ; revised to 6 top-level + 11 nested-public.
- User approved D6 §14 (3 yes/no items: NEST 11, fold UDS multi-platform, merge JsonRpcResponse).
- /flow skill updated with 11 organization-discipline checks (Edits 1-11).
- ripgrep installed system-wide (was shell-function-only; flow-verify-* scripts now actually fire).
- design/02-design.md + design/05-plan.md + design/05-plan.yaml in progress.

## Stage 2 (per-phase loop)

- Phase 01 impl dispatched (sonnet, 30-45min ETA).
- Phase 02 prep note: `JsonRpcResponse` (3 fields, Maybe[id]) vs `JsonRpcEnvelope.Response` (4 fields, non-Maybe id, has extras) are NOT structurally identical. Phase 02 needs to: keep `Envelope.Response` (extras-bearing, used on wire); delete standalone `JsonRpcResponse.scala`; move `success`/`failure` factories onto `JsonRpcEnvelope.Response` companion adapting to the 4-field shape (extras = Absent default, id-as-Maybe wrapped).

- Phase 03 caller-impact (for impl prompt). 10 nesting moves, files affected (rg --type scala): MessageGate=7, CancellationPolicy=8, ProgressPolicy=8, UnknownMethodPolicy=8, IdStrategy=11, ExtrasEncoder=12, Framer=8, WireTransport=7, HandlerCtx=9 (also renames to JsonRpcMethod.Context), JsonRpcId=33 (also renames to JsonRpcEnvelope.Id). JsonRpcId is the dominant rename due to ubiquitous wire-message construction. Total deduped impact likely 50-80 files. Phase 03 commit will be the largest of the campaign.

- Phase 01 committed: 68ba4e113 (verify PASS, 56 files staged).
- Phase 01 audit dispatched in SLOT-B.
- Phase 02 impl dispatched in SLOT-A.



