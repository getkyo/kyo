# Steering ; kyo-jsonrpc API realignment

## Most-corrected patterns (read before any phase)

1. Present finalized decisions, not options.
2. The summary IS the approval artifact.
3. Complete and correct is the North Star.
4. NEVER STOP.
5. Resume silently from any interruption.
6. No pending items at completion.

## Idle-wait and status-report are STOPS

- No mid-campaign status / checkpoint reports.
- Dispatch-then-work, never dispatch-then-wait.

## Campaign-specific constraints — REFERENCE IS kyo-http

EVERY phase impl prompt MUST cite kyo-http file:line precedents for the pattern being applied. Drift from kyo-http is the failure mode this campaign exists to fix. Do not invent shapes; copy kyo-http's conventions verbatim.

- **Subject**: kyo-jsonrpc + kyo-jsonrpc-http (+ kyo-browser caller updates as needed)
- **Template**: kyo-http (verbatim — naming, opaque types, sealed hierarchies, extension methods, error model)
- **Module prefix**: JsonRpc
- **Locked design**: realignment-plan.md (in this design/ folder)
- **Locked decisions** (per `exception-hierarchy-design.md` + `exception-hierarchy-q3q4.md`):
  - 4 operation-category traits (Parse/Dispatch/Execution/Application) ; single-leaf inheritance default
  - 11 case-class leaves + 1 abstract open class for user-extensible application errors
  - 4 specialized execution leaves (Configuration / Lifecycle / Transport / HandlerPanic) + InternalError catchall
  - Aux enums nest under their single-consumer leaf
  - String not Text ; no `detail: String` free-form parameters ; raw structured data only

## Phase plan (8 phases, ~2150 LoC)

01. Foundation renames (Endpoint→Handler, Method→Route, drop 2nd-overload, test base rename)
02. Wire-message hoist (Request/Response/Notification/Malformed/Id top-level, Envelope bare sealed trait)
03. Error hierarchy (sealed JsonRpcError + 4 operation traits + 11 leaves + JsonRpcTransportException analogue if scoped)
04. Route restructure (drop free S, add [In, Out, +E], Halt, .error[E])
05. Entry-point opaque type (JsonRpcHandler = JsonRpcHandler.Unsafe + extension methods)
06. Lifecycle variants + filter hoist (initWith/initUnscoped/initUnscopedWith, MessageGate top-level, Decision.Reject widen)
07. Cleanup nested types (hoist remaining Phase-03 nested ; Pending +Out ; codec/filter presets)
08. Final cross-platform green gate

## Repo-wide rules

- Never push to remote. Never create / touch PRs.
- No Co-Authored-By in commits.
- No em-dashes / LLM-tells in any output.
- Never destructive git against uncommitted work.
- Test infrastructure changes OK; behavior changes need approval.
- Sequential test runs across platforms.
