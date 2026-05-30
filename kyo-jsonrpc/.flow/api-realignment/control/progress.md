# Progress ; kyo-jsonrpc API realignment

## Stage 1 (project-once chain) — done manually

- Exploration: audit at design/audit.md (30 axes, 18 divergent).
- Design: realignment-plan.md (8 phases, all decisions locked).
- Exception-hierarchy questions: design/exception-hierarchy-q1q2.md + q3q4.md.
- Plan: realignment-plan.md (treated as design/05-plan.md).
- Validation: skipped — plan was human-reviewed through multiple iterations with the user.

## Stage 2 (per-phase loop)

- Phase 01 impl dispatched (sonnet).
- Phase 02 caller-impact (rg --type scala): JsonRpcEnvelope.Request=12, .Response=9, .Notification=15, .Malformed=9, .Id=27. Total deduped likely 30-40 files. Id is dominant.

