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

