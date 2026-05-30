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

- Phase 01 committed: 8bec7d882 (0 BLOCKER, 0 WARN, 3 NOTE).
- Phase 02 impl dispatched in SLOT-A.
- Phase 01 audit dispatched in SLOT-B — clean.
- Phase 02 impl complete: sealed trait JsonRpcEnvelope + 4 top-level case classes (same file, Scala 3 sealed constraint) + JsonRpcId opaque type (separate file). kyo-jsonrpc [success], kyo-jsonrpc-http [success], kyo-browser [success]. 179/179 tests green. 9/9 convention checks = 0 hits. Decisions: phases/phase-02/decisions.md.

- Phase 03 caller-impact (rg --type scala): JsonRpcError reference sites=34, JsonRpcError.internalError sites=5, JsonRpcError pattern-match sites=11, JsonRpcError(code, ...) constructor literals=22. Phase 03 is the largest phase: ~50 reference updates across the construction sites + 11 pattern-match migrations. Special focus: the 5 internalError sites need to be RECLASSIFIED to specific leaves (Configuration / Lifecycle / Transport / HandlerPanic) — not all mapped to the InternalError catchall. The `CdpBackend.scala:65-71` string-prefix-match site also migrates to typed pattern matches.

- Phase 02 committed: 24b78e70f. Audit 0/0/2.
- Phase 03 impl dispatched in SLOT-A.
- Phase 02 audit dispatched in SLOT-B — clean.

- Phase 04 prep note: current JsonRpcRoute is `sealed trait JsonRpcRoute[+S]` at JsonRpcRoute.scala:27, with `def apply[In, Out, S](name)(handler: (In, Context) => Out < S)` at :80. HttpRoute reference at kyo-http/.../HttpRoute.scala:40 is `case class HttpRoute[In, Out, +E](method, requestDef, responseDef, filter, metadata)` (not sealed trait). `.error[E2]` at HttpRoute.scala:82 is inline + chainable. Phase 04 needs to (a) restructure JsonRpcRoute to `[In, Out, +E]` shape (case class or sealed trait), (b) fix handler row to `Async & Abort[E | JsonRpcError | JsonRpcResponse.Halt]`, (c) add JsonRpcResponse.Halt + .halt convenience, (d) add .error[E2] inline chainable form, (e) move `dispatch` to internal (audit A1).

- Phase 03 committed: cc067892b. Audit 0/1/4 (WARN: Schema binary serializeRead drops data — non-critical; primary path preserves).
- Phase 04 committed: f71402d0d (186/186 tests). Audit 0/2/3:
  - WARN-1: handler row uses `Abort[JsonRpcError | JsonRpcResponse.Halt]` with E=Nothing start (not `Abort[E | JsonRpcError | JsonRpcResponse.Halt]`). E accumulates via `.error[E2]` into the ROUTE type but isn't in the handler row. Phase 05 MUST wire engine to `ErrorMapping[E].matches` else `.error[E2]` is dead API.
  - WARN-2: Halt-in-request-handler path currently emits JsonRpcHandlerPanicError instead of the wrapped response. The test "JsonRpcResponse.Halt aborts with the wrapped response" actually asserts the panic shape (misleading name). Phase 05 must add a gate-layer Halt-to-response test + fix the routing.
- Phase 05 impl dispatched in SLOT-A.
- Phase 04 audit dispatched in SLOT-B — clean.

