# Round 2 Consistency audit

Audit of `kyo-jsonrpc/DESIGN.md` (1043 lines). All line numbers exact.

Round-1 verification: the seven hard contradictions from round-1 are RESOLVED (extras now `Maybe[Structure.Value]` everywhere; `Outcome` removed from §6.1/§6.2; `Json.Value` purged; PascalCase `RequestCancelled` standard; `cancelledByLocal` gone; `unsubscribeProgress` declared in §6 line 294). Round-1 drift items D2/D3 (`JsonRpcCodec[Envelope]` / `JsonRpcTransport[Env]` vestigial type params) are RESOLVED — line 81 now reads `JsonRpcCodec[Envelope]` is gone, §4 has the explicit no-type-param note. Round-1 stale S7 (`sendNotification` in §2 diagram) is partially resolved (still in the §16.3 row prose at line 912, but that row labels the CDP-side requirement, not the engine API). New issues below.

---

## Hard contradictions (X stated; ~X stated elsewhere)

### HC1. `endpoint.events` referenced but never declared

§6.1 line 392:
```
- Inbound `Notification` for events that the consumer cares about (CDP `Page.frameNavigated`, etc) can either be dispatched as a registered `JsonRpcMethod.notification`-handler (forked) OR routed as an `Exchange.Message.Push` for `endpoint.events` consumption.
```

`endpoint.events` is not in §6's public method list (lines 256-312), not in §15's public surface (lines 789-814), and not in any §18 phase. The §6 declaration set is closed: only `call`, `notify`, `callWithProgress`, `callPartialResults`, `subscribeProgress`, `unsubscribeProgress`, `cancel`, `awaitDrain`, `close`. Canonical: §6/§15 (the engine never exposes a push-events stream; the §6.1 sentence's "OR routed as `Push`" branch is dead). Fix: drop the "OR routed as an `Exchange.Message.Push`..." clause in line 392, or add a public `events: Stream[JsonRpcEnvelope.Notification, Async]` to §6/§15.

### HC2. `policy.encodeParams.encode(id, reason)` invokes `.encode` on a function value

§7 line 556:
```
3. If `Config.cancellation = Present(policy)`: engine emits a notification envelope with `method = policy.cancelMethod`, `params = policy.encodeParams.encode(id, reason)`.
```

§7 line 501 declares `encodeParams` as a function alias:
```
type ParamsEncoder = (JsonRpcId, Maybe[String]) => Structure.Value < Sync
```

`encodeParams` is a function, not an object with `.encode`. The call should be `policy.encodeParams(id, reason)`. Stale artifact from when `ParamsEncoder` was a trait with an `encode` method. Canonical: the §7 type definition. Fix line 556 to `policy.encodeParams(id, reason)`.

### HC3. §20 invariant #10 names policy fields that do not exist

§20 line 1005:
```
10. **`CancellationPolicy.protectedMethods: Set[String]`** carves out method names that cannot be cancelled. `MCP.lsp.protectedMethods = Set.empty`; `MCP.mcp.protectedMethods = Set("initialize")`.
```

There is no top-level `MCP` object in this design. The canonical names are `CancellationPolicy.lsp` and `CancellationPolicy.mcp` (§7 lines 513, 521). The §20 line writes `MCP.lsp` / `MCP.mcp`, which is a typo for `CancellationPolicy.lsp` / `CancellationPolicy.mcp`. Canonical: §7. Fix line 1005 accordingly.

### HC4. `JsonRpcMethod.init` signature in §15 / §18 vs the typed `methods` registry actually used

`Endpoint.init` (line 339) takes `methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]]`. §16.2 line 894 says "registered as `JsonRpcMethod` in kyo-mcp"; §18 phase 4 references `methods` as a map (`methods[env.method]` lookup in §6.2 line 412/417). The lookup syntax `methods[env.method]` implies a `Map[String, JsonRpcMethod[...]]`, but `Seq[...]` is what `init` takes. Either the engine builds a `Map[String, JsonRpcMethod[...]]` from the `Seq` (likely intent, not stated), or `init` should take a `Map`. Canonical: the `Seq` parameter on line 339 (init is what's invoked by users); §6.2's `methods[env.method]` is informal pseudocode for "lookup". Minor; recommend adding "Engine builds an internal `Map[String, JsonRpcMethod[...]]` from the `methods` Seq at init" in §6.1.

### HC5. §15 surface lists `JsonRpcMethod.Kind` but not `JsonRpcMethod.notification`

§15 line 793-794:
```
JsonRpcMethod              // typed method descriptor (request + notification factories)
JsonRpcMethod.Kind
```

`JsonRpcMethod.Kind` is listed as a public surface entry, but the `notification` factory (§5 line 219) is referenced everywhere (§16.3 line 921, §18 phase 2) and is functionally a primary public API. Either both belong as bullets or only the parent `JsonRpcMethod` should appear (the factory is implied). Minor inconsistency; consider dropping `JsonRpcMethod.Kind` from the surface list (it's metadata) or adding `JsonRpcMethod.notification` for symmetry.

---

## Drift (terminology / naming used inconsistently)

### D1. `notify` vs `notification` factory

The outbound API method on `JsonRpcEndpoint` is `notify` (line 268, 387, 722, 912, 924, 1020). The descriptor factory on `JsonRpcMethod` is `notification` (line 219, 793, 977). The two are distinct and both naming choices are internally consistent. No fix; flagged because prose at lines 21 ("Cancellation `notifications/cancelled`") and 905 ("notifications by method") sometimes blurs the distinction. Could clarify once in §5.

### D2. `endpoint.cancel(id)` vs `endpoint.cancel(id, reason)`

§6 signature (line 303-304) has `(id: JsonRpcId, reason: Maybe[String])`. §7 prose alternates:
- line 533, 537, 547, 564: `endpoint.cancel(id)`
- line 554: `endpoint.cancel(id, reason)`
- §16.2 line 900: `endpoint.cancel` (no args)

Canonical: the §6 declaration with both params. Prose should consistently write `endpoint.cancel(id, reason)`. Minor.

### D3. `Pending[Out].cancel` field name collides with `endpoint.cancel`

§6 line 319-324: `final class Pending[Out](id, result, progress, cancel: Unit < ...)`. The field `cancel` on `Pending` is a no-arg effect; `endpoint.cancel(id, reason)` is a method. Both are "cancel"; readers may confuse them. Not strictly a contradiction but worth a one-line note in §6 that the `Pending.cancel` is shorthand for `endpoint.cancel(this.id, Absent)`.

### D4. `Structure.Value` field access: `field(v, name)` helper vs informal `params._meta.progressToken`

§8 declares the `field` / `merge` helpers inside `ProgressPolicy`'s companion (lines 592-605) and the policies are rewritten to use them via pattern matching. But §16.2 line 892 still says "reads `params._meta.progressToken`" as if dotted-path access were the canonical pattern. Canonical: pattern-matching `Record(fields)` via `field` helper (per §20 #11 line 1006). The §16.2 prose is descriptive of behavior, not API; fine as-is, but mention the helper if anyone reads §16.2 first.

### D5. `progress` overloaded: handler method, sink, field on `Pending`, policy method name

`progress` is used as:
- `HandlerCtx.progress(value)` (line 235): handler-side emit method
- `HandlerCtx.progressSink` (line 231): internal closure that backs it
- `Pending[Out].progress` (line 322): stream field
- `ProgressPolicy.progressMethod` (line 579): wire method name string (`"$/progress"`)

These are coherent but four overloaded usages of "progress" in adjacent sections. Minor; just flagged.

### D6. `Exchange's pending map` phrasing

§6.1 alternates between:
- "Exchange's pending map" (line 363, 558, 1019, 1022, 1023, 1028)
- "Exchange's `pending map`" (line 352)
- "Exchange.apply" (line 1020, 457)

All point to the same thing (Exchange's internal id→promise map). Consistent enough; recommend prose pick one (suggest "Exchange's pending map") and stick with it.

### D7. `Json.encode` mentioned in §7 line 535 as a "trap"

§7 line 535:
```
Avoids the `Json.encode` (returns String) trap and needs nothing beyond what kyo-schema already provides.
```

The design has otherwise purged `Json.*` references in favor of `Structure.Value`. This single mention is a meta-reference to "the wrong approach we're avoiding". Not a contradiction, but a reader following this design alone might wonder where `Json.encode` is defined. Could be rephrased as "Avoids the string-encoding trap (e.g., a hypothetical `Json.encode` returning String)."

---

## Stale references (removed types/methods/policies still mentioned)

### S1. `endpoint.events` (§6.1 line 392)

Top stale-reference. See HC1. Reads as a public API; no such API exists in §6 / §15. Either add or remove.

### S2. `policy.encodeParams.encode(...)` (§7 line 556)

See HC2. `.encode` member access on a function type. Pre-rewrite remnant from when `ParamsEncoder` was a trait with an `encode` method. Now a type alias for a function.

### S3. `MCP.lsp.protectedMethods` / `MCP.mcp.protectedMethods` (§20 line 1005)

See HC3. Top-level `MCP` object never declared. Should be `CancellationPolicy.lsp` / `CancellationPolicy.mcp`.

### S4. `sendNotification(wire)` in §16.3 mapping table (line 912)

The CDP-needs column says: "5. `sendNotification(wire)` bypasses pending map". This describes the CDP requirement (the old kyo-browser API), and the engine surface column correctly maps it to `endpoint.notify(...)`. Strictly the left column is correct as "what CDP/kyo-browser exposed"; reader-readability would be better if both columns aligned (currently CDP→`sendNotification`, engine→`notify`). Minor; could update to "CDP fire-and-forget" for clarity.

### S5. `JsonRpcMethod.Kind` listed in §15 surface

Listed as a public type (line 794). It is a nested enum on `JsonRpcMethod` used internally for routing/metadata. Not strictly stale, but its public listing implies external callers will pattern-match on it; the design doesn't use it externally. Consider dropping or relegating to a comment.

### S6. `Exchange.Message.Push` mention (line 392)

Tied to HC1. If `endpoint.events` doesn't exist, then routing notifications "as `Exchange.Message.Push`" is dead code path. Drop or surface the events stream.

### S7. ASCII-diagram bullet `sendNotification` referenced by round-1 S7

Round-1 audit flagged a `sendNotification` line in §2's ASCII diagram (round-1 line 56). The current §2 diagram (lines 31-95) does NOT contain `sendNotification` (line 53 says "callerRegistry  (id -> method + caller fiber, for cancel)"). Round-1 issue resolved.

---

## Forward-reference errors / numbering

### F1. §6.2 step 4 references "§6.2 line 412 / 417" implicitly with `methods[env.method]`

Section numbering correct. The `methods[env.method]` informal pseudocode is consistent across §6.2 (lines 412, 417). All §-refs in the body resolve correctly: §6.4 (line 441), §6.5 (line 453), §6.6 (line 471), §7 (line 484), §8 (line 573).

### F2. §6.5 line 469 says "the correctness audit's race-condition #3"

`the correctness audit's race-condition #3` is an external-doc reference (the round-1 CORRECTNESS audit). The phrasing reads like it's a forward-ref within this doc. Recommend disambiguating: "the round-1 correctness audit's race-condition #3 finding".

### F3. §16.1 line 870 "(§15)" — verified

Resolves to public surface section at line 785. OK.

### F4. §17 line 938 references "engine `cancel(id)` + auto-fire on timeout + handler-observed via ctx.cancelled"

`cancel(id)` should match the §6 signature `cancel(id, reason)`. Same drift as D2. Spot-check only.

### F5. §18 phase 4 (line 973) references `pendingInbound`, `callerRegistry`, `InboundEntry`, `Running`/`Replying` — all consistent with §6.1

All section cross-refs in §18 resolve to §6.1, §6.2, §6.4, §6.5 correctly. Phase plan tests what §6 declares. OK.

### F6. §19 / §20 numbering — clean

No missing-section references. §19 #2 references "§19" implicitly (line 997). §20 #6/§20 #12 reference §6.4 / §6.5 correctly. §20 #11 references "§20" implicitly. OK.

---

## Item-by-item check summary

| Check | Result |
|---|---|
| 1. §6 method list vs §15 surface | OK except HC5 (`Kind` listed; `notification` factory not). HC1: `endpoint.events` referenced in §6.1 prose, undeclared. |
| 2. §6.1 internals fields vs §6.2/§6.5/§7/§8/§12 references | `pendingInbound`, `callerRegistry`, `InboundEntry.Running/Replying` all consistent across sections. |
| 3. `extras` shape | All `Maybe[Structure.Value]`. Zero `Json.Value`, zero `Maybe[Json.Value]`. RESOLVED from round 1. |
| 4. `Outcome` ghost | Zero references except "no `Outcome` ADT" denials (lines 240, 348, 998). RESOLVED. |
| 5. `pendingOutbound` / `OutboundEntry` / `cancelledOutboundIds` | Zero `pendingOutbound`, zero `OutboundEntry`. ONE `cancelledOutboundIds` reference (line 381) — defensively documents that the set was REMOVED. Anti-drift, fine. RESOLVED. |
| 6. `callerRegistry` lifecycle | §6.1 (357), §6.5 references via Exchange's `Sync.ensure`, §7 outbound flow (555-557) all consistent. |
| 7. `pendingInbound` state machine | `Running` / `Replying` used consistently across §6.1, §6.2, §6.5, §7, §8. |
| 8. Policy `Maybe[X]` vs `.none` | All `Maybe[...]` with `Absent` for CDP. ONE `.none` reference (line 530) defensively says no such constant exists. RESOLVED. |
| 9. `Json.Value` | Zero references. RESOLVED. |
| 10. `JsonRpcTransport[Env]` vs no-param | No-param canonical (§4 line 164, §2 diagram line 81). RESOLVED. |
| 11. `RequestCancelled` PascalCase | Used consistently (line 517, 646, 874, 986). No lowercase `requestCancelled` remains. `JsonRpcError.cancelled(reason)` factory referenced consistently at line 557, 842. RESOLVED. |
| 12. Em-dashes / en-dashes | Zero em-dashes. EIGHT en-dashes at lines 19, 21, 821-833 (the JSON-RPC error code ranges "-32700..-32603", "-32800..-32803") — these are MINUS signs in code-ranges, not en-dash separators. Three actual en-dash uses at lines 876 ("R1–R5"), 897 ("R22–R26"), and `LSP.md§§3–6`. Per CLAUDE.md, "no em-dashes or LLM-tells"; the rule's strict reading covers en-dashes too. Recommend replacing `R1–R5` with `R1-R5` (hyphen) and `R22–R26` likewise. |
| 13. Section numbering / forward refs | All resolve. See F-section. |
| 14. §18 phase plan vs API surface | Each phase tests an API §6/§7/§8/§9/§10/§11/§12 declares. OK. |
| 15. `notify` vs `notification` | Method = `notify` (engine), factory = `notification` (descriptor). Distinction held consistently. No `cancelCall` references. OK. |
| 16. `.field(name)` vs `Record(fields)` pattern matching | Pattern matching via `field` helper inside `ProgressPolicy` (lines 592-605); no `.field(name)` method calls anywhere. RESOLVED from round 1. |

---

## Counts

- Hard contradictions: 5
- Drift: 7
- Stale references: 7 (one resolved from round 1: `sendNotification` ASCII diagram)
- Forward-reference / numbering: 0 broken; 2 minor disambiguation suggestions
- Em-dashes: 0; en-dashes (in headings/identifiers): 3 (CLAUDE.md gray zone)

## Round-1 verification

| Round-1 item | Status |
|---|---|
| HC1 (extras shape) | RESOLVED |
| HC2 (`Fiber[Outcome, Any]`) | RESOLVED — now `Fiber[Structure.Value, Any]` (line 370) |
| HC3 (discard Outcome) | RESOLVED |
| HC4 (`cancelledByLocal`) | RESOLVED — uses `JsonRpcError.cancelled(reason)` at line 557 |
| HC5 (lowercase `requestCancelled`) | RESOLVED — PascalCase at lines 517, 646 |
| HC6 (`unsubscribeProgress` undeclared) | RESOLVED — now declared §6 line 294, listed §15 implicitly |
| HC7 (LSP-no-extras prose) | RESOLVED — line 154 now says "extras carries `Absent`" by implication |
| D1-D7 drift | Mostly resolved; new D1-D7 issues are different (see above) |
| S1-S8 stale | Most resolved; S7 (`sendNotification` in §2 diagram) resolved |
