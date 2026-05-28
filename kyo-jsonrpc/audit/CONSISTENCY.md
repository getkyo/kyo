# Consistency audit

Audit of `kyo-jsonrpc/DESIGN.md` (824 lines). All line numbers are exact.

---

## Hard contradictions  (the document asserts X and ~X in different places)

### HC1. `JsonRpcEnvelope.extras` declared as `Json.Value` in §3, but `Maybe[Json.Value]` everywhere else

§3 ADT (lines 106-108):
```
106: case Request (id: JsonRpcId, method: String, params: Maybe[Json.Value], extras: Json.Value)
107: case Notification(method: String, params: Maybe[Json.Value], extras: Json.Value)
108: case Response(id: JsonRpcId, result: Maybe[Json.Value], error: Maybe[JsonRpcError], extras: Json.Value)
```

§19 #1 resolved decision (line 781):
```
781: 1. **`JsonRpcEnvelope.extras: Maybe[Json.Value]`** (flat, opaque). `Absent` ≠ `Present(Json.Value.Null)`.
```

§20 invariant #11 (line 808):
```
808: 11. **`extras` is `Maybe[Json.Value]`, not `Json.Value`.** `Absent` means "no extras"; `Present(Json.Value.Null)` means "extras slot exists, is JSON null". The two are observably different on the wire.
```

§6 key shape decisions (line 295), §6 call signatures (lines 229/236/243/252), and §19/§20 all assert `Maybe[Json.Value]`. **§3 is the stale draft.** Canonical: `Maybe[Json.Value]`. Fix §3 lines 106-108 to wrap each `extras` field in `Maybe[...]`. The §3 comment on line 113 ("LSP has no extras") also reads better as "LSP envelopes carry `Absent`".

### HC2. `pendingInbound` value type references the removed `Outcome` ADT

§6.1 internals (line 306):
```
306: - `pendingInbound: ConcurrentHashMap[JsonRpcId, Fiber[Outcome, Any]]` — calls we received and are still serving. Keyed for `cancel()` lookup.
```

§5 (line 215) and §6 (lines 297-298) and §19 #3 (line 783) all assert there is **no `Outcome` ADT**:

```
215: Cleaner than the earlier `Outcome.Reply | NoReply` ADT.
298: - **No `Outcome` ADT.** Request handlers return `Out < S` (typed); the engine encodes to `Json.Value` ...
783: 3. **No `Outcome` ADT.** Handler returns `Json.Value < S` (or `Unit < S` for notifications). ...
```

The §19/§20 audit-cycle prose is canonical. `Fiber[Outcome, Any]` on line 306 is a stale type-reference. Recommend: `Fiber[Json.Value, JsonRpcError | Any]` (or similar) to reflect that handler fibers now produce the encoded result directly.

### HC3. §6.2 reader-fiber routing still mentions "discard Outcome"

§6.2 (line 318):
```
318: Notification -> fork(method.handle); discard Outcome; for cancel/progress, policy intercepts first
```

Contradicts §5 (line 215) and §19 #3 (line 783). The notification path now discards the handler's `Unit` (see line 215: "For notifications it discards the handler's `Unit` return"), not an `Outcome`. Replace "discard Outcome" with "discard Unit".

### HC4. `JsonRpcError.cancelledByLocal` referenced in §7 but not declared in §15

§7 outbound-cancel flow (line 413):
```
413: 3. Engine completes `pendingOutbound[id]` immediately with `Abort.fail(policy.cancelledError.getOrElse(JsonRpcError.cancelledByLocal))`.
```

§15 declares the `JsonRpcError` constants (lines 637-655). `cancelledByLocal` does **not** appear. The closest factory is `def cancelled(reason: Maybe[String] = Absent): JsonRpcError` (line 655).

**§15 is canonical** (it's the official public-API surface and was rewritten in the completeness audit). Either:
- add `val CancelledByLocal: JsonRpcError = ...` to §15, OR
- change line 413 to use the existing factory: `JsonRpcError.cancelled(Absent)`.

The second is more consistent with the no-new-constants approach already taken for `methodNotFound`/`invalidParams`/`internalError`.

### HC5. `JsonRpcError.requestCancelled` (lowercase) vs `JsonRpcError.RequestCancelled` (constant)

§7 `CancellationPolicy.lsp` (line 381):
```
381: cancelledError = Present(JsonRpcError.requestCancelled)
```

§7 prose (line 406):
```
406: 5. If `expectReplyForCancelledRequest = true` (LSP): wait for the handler fiber to finish naturally. Send whatever it produces (result, error, or `policy.cancelledError` if the handler aborted with `requestCancelled`).
```

§15 declares the constant in PascalCase (line 646):
```
646: val RequestCancelled     = JsonRpcError(-32800, "Request cancelled",      Absent)
```

§15 is canonical (the PascalCase scheme is used for all six promoted LSP codes and matches the JSON-RPC base codes `ParseError`, `InvalidRequest`, etc.). Fix line 381 to `JsonRpcError.RequestCancelled` and line 406 to refer to `RequestCancelled` if the intent is the constant.

### HC6. `endpoint.unsubscribeProgress` invoked in §8 but undeclared in §6 / §15

§8 (line 486):
```
486: Implementation: same `progressStreams[token]` map; just a registration entry-point that doesn't go through `callWithProgress`. The stream closes when `endpoint.unsubscribeProgress(token)` is called or the endpoint shuts down.
```

`endpoint.unsubscribeProgress` is not in the §6 public-method list (lines 224-274), not in the §15 public-API surface (lines 608-630), and not in any §18 phase. The dual `subscribeProgress` appears in §6 (line 256), §15 surface table (line 682), and §19 #6 (line 786).

§6 is canonical for the public surface. Either:
- declare `def unsubscribeProgress(token: Json.Value): Unit < ...` in §6 and add to §15, OR
- remove the `unsubscribeProgress` reference and state that the stream closes on endpoint shutdown only (and via standard Stream consumer cancellation).

### HC7. Comment in §3 says "LSP has no extras", §15 / §19 say extras is `Maybe`

§3 (line 113):
```
113: // LSP has no extras.
```

§19 #1 (line 781) and §20 #11 (line 808) say `Absent` is the no-extras representation. The phrasing on line 113 implies extras-is-absent-everywhere-on-LSP-wire, which is true, but the field still exists with value `Absent`. Should be reworded: "LSP envelopes carry `extras = Absent`." Minor, included for completeness.

---

## Drift  (terminology / naming used inconsistently)

### D1. `JsonRpcMethod.Kind.Request | Notification` vs the engine's runtime routing

§5 declares `Kind` (lines 182-183) and uses it in `method.kind` (line 215, 783). §6.2 reader fiber switches on the **envelope** ADT (`Request | Notification | Response | Malformed`, line 317-320) not on the method's `Kind`. This is correct — they're different types — but the prose elides the distinction. Not a contradiction, but `Kind` is used **only** in §5 (line 175, 182), §6 prose (lines 215, 297-298, 783), and §15 (line 613) and §18 (line 765). Worth a sentence in §5 noting that `Kind` is informational metadata on the method descriptor; the engine routes by envelope type, not by `method.kind`.

### D2. Layer-2 diagram shows `JsonRpcCodec[Envelope]` (parameterised) but §3 declares it unparameterised

§2 diagram (line 69):
```
69: │  │   JsonRpcCodec[Envelope]  (pluggable)                              │  │
```

§3 declaration (line 117):
```
117: trait JsonRpcCodec:
118:     def encode(env: JsonRpcEnvelope)(using Frame): Json.Value < Sync
119:     def decode(raw: Json.Value)(using Frame): JsonRpcEnvelope < Sync
```

The trait is **not** parameterised; both methods bake in `JsonRpcEnvelope`. The §2 diagram's `[Envelope]` type parameter is vestigial drift. Drop the `[Envelope]` in the diagram (line 69) — write `JsonRpcCodec (pluggable)`.

### D3. `JsonRpcTransport[Env]` vs `JsonRpcTransport`

§2 diagram (line 78):
```
78: │  │   trait JsonRpcTransport[Env]:                                     │  │
79: │  │     send(env: Env): Unit < (Async & Abort[Closed])                 │  │
```

§4 declaration (line 141):
```
141: trait JsonRpcTransport(using Frame):
142:     def send(env: JsonRpcEnvelope): Unit < (Async & Abort[Closed])
```

§14 (line 595) also says: "implements `JsonRpcTransport[JsonRpcEnvelope]`" — same vestigial parameter.

§4 is canonical (envelope is fixed, codec selection happens at transport construction). Drop `[Env]` / `[JsonRpcEnvelope]` from §2 (line 78) and §14 (line 595).

### D4. "Outbound" cancellation prose: `endpoint.cancel(id)` vs `endpoint.cancel(id, reason)`

§6 signature (line 262): `cancel(id: JsonRpcId, reason: Maybe[String])`. §7 prose alternates:
- line 394: `endpoint.cancel(id)`
- line 411: `endpoint.cancel(id, reason)`
- line 420: `endpoint.cancel(id)`

Canonical is the §6 signature with both params. Prose should consistently write `endpoint.cancel(id, reason)`, or both forms with `reason = Absent` shown explicitly. Minor.

### D5. `JsonRpcMethod.notification` vs `notification factory`

Phrasing for the notification constructor varies: "notification factory" (line 612, 765), "notification flavour" (line 64, 170), "`JsonRpcMethod.notification(name)`" (line 297). All point to the same method. Canonical is the §5 declaration `def notification[In, S](name)(...)` (line 194). Prose should consistently say "the `notification` factory".

### D6. `progressSink` vs `progress sink` vs `ctx.progress`

§5 field name is `progressSink` (line 206). The wire-up uses `HandlerCtx.progressSink` (line 490). The handler-visible method is `progress(value)` (line 210, 488, 750). §15 surface description on line 629 says: "cancellation token, extras, progress sink, request id". This is fine — distinct names for the internal field (`progressSink`) and the public method (`progress`) — but worth surfacing once in §5 so readers don't think they're two separate concepts.

### D7. "Single endpoint per WebSocket" vs "kyo-browser models all sessions on one endpoint"

§10 (line 535) says CDP runs all sessions on one endpoint. §16.3 (line 729) says "one endpoint per WebSocket". Consistent if a WebSocket carries all sessions — which is the CDP model — but the two clauses read differently. Restate as "one endpoint per CDP connection (WebSocket), serving all sessions on that connection."

---

## Stale references  (references to types/methods/policies that were removed in revisions)

### S1. `Fiber[Outcome, Any]` (line 306) — `Outcome` ADT was removed in §19 #3

Top stale-reference item. See HC2. The pendingInbound declaration carries the type name `Outcome` which the design explicitly says no longer exists.

### S2. "discard Outcome" in §6.2 reader-fiber pseudocode (line 318)

See HC3. Same ghost as S1.

### S3. `JsonRpcError.cancelledByLocal` (line 413) — never declared in §15

See HC4. The only place it appears is §7. Either declare it or replace with `JsonRpcError.cancelled(Absent)`.

### S4. `JsonRpcError.requestCancelled` lowercase (line 381) — supplanted by `RequestCancelled` constant in §15

See HC5. Constants in §15 use PascalCase consistently. The `requestCancelled` (camelCase) reference is leftover from a pre-promotion draft.

### S5. `JsonRpcCodec[Envelope]` type parameter (line 69) and `JsonRpcTransport[Env]` (lines 78, 595) — both unparameterised in their canonical declarations

See D2 and D3. Vestigial type parameters from an earlier draft where the codec/transport were generic.

### S6. `endpoint.unsubscribeProgress` (line 486) — not in any public-surface declaration

See HC6. Reads as an API that exists, but the §6 / §15 lists do not include it. Either real-but-undocumented (needs adding) or planned-and-dropped (needs removing).

### S7. `sendNotification` in §2 diagram (line 56)

§2 layer-4 box says:
```
56: │  │   • awaitDrain, sendNotification                                   │  │
```

The actual method is `endpoint.notify` (declared §6 line 233; referenced everywhere else: §11 line 541, §16.2 line 722, §16.3 line 734, §18 line 767, §19 #5 line 785, §20 line 801). The §2 diagram bullet is a stale name from an earlier draft. Replace `sendNotification` with `notify` in the diagram.

### S8. `CancellationPolicy.none` mention (line 391) — defensively documents non-existence

Line 391 is a code comment inside the `object CancellationPolicy` block:
```
391: // CDP: Config(cancellation = Absent). No CancellationPolicy.none constant.
```

This is actually correct (it's anti-drift documentation), and §6 (line 296) and §19 #2 (line 782) confirm. Not a bug, but worth verifying the symmetry — there is **no** equivalent "No `ProgressPolicy.none` constant" comment in §8 / `object ProgressPolicy`. Recommend adding the same line in §8 for symmetry, since `Config.progress: Maybe[ProgressPolicy]` is the analogous shape.

---

## Forward-reference errors / numbering issues

### F1. §5 line 213 references "(§8)" but the link target was probably §7

§5 (line 213):
```
213: The policy (§8) extracts the progress token from inbound params and installs the `progressSink` closure into `HandlerCtx`.
```

§8 is the progress section (line 429: `## 8. Progress policy`). The sentence is about progress, so §8 is correct. **Not an error.** Included only because §5 also says "see §7" elsewhere — verified those references (line 297) are about cancellation, so they correctly point at §7. All §-references in §5 resolve.

### F2. §5 line 215 says "see §7" but the cancellation section is §7

Line 297:
```
297: ...Cancellation reply semantics are entirely an engine concern (see §7), not handler-visible.
```

§7 (line 364: `## 7. Cancellation policy`). Correct.

### F3. §16.1 references `§4`, `§6`, `§10`, `§14`, `§15` — all valid

Spot-checked all the §-references in §16 (lines 672, 673, 674, 675, 683, 694, 695, 696, 700, 701, 711, 718, 724, 725). All resolve to the correct section.

### F4. §17 references §17 itself

Line 660 (in §15):
```
660: The prompt's "exactly these seven public types" target was too tight to express what CDP needs (pluggable codec) and what LSP needs (cancellation/progress policies). The deviation is documented in §17.
```

§17 is "Gap vs the original prompt" (line 741). Resolves correctly.

### F5. §20 line 803 references "(LSP report §2.)" — that's external

§2 here refers to the **LSP research report**, not §2 of this DESIGN.md. Could be misread. Recommend disambiguating to "(LSP research report §2)".

Same pattern in line 425 ("LSP's report §3"), line 497 ("LSP report §4"), line 600 ("The LSP report's §1"). All refer to the external research docs. Not broken, just easy to confuse.

### F6. §21 numbering — no missing-section heading

§20 ends at line 809 ("...drain pendings."). §21 (line 811: `## 21. What this design does NOT do`) immediately follows. No gap, no §-reference forward-points into a missing section. Note: §20 does **not** call forward to §21 at all, and §21 does not back-reference §20. Both stand alone. No fix needed.

### F7. §2 ASCII diagram numbering vs prose

§2 diagram labels layers 0–4 (lines 48, 60, 67, 76, 85). Prose (line 93) names them "Layer 0", "Layer 1", "Layer 2", "Layer 3", "Layer 4" and §§3–6 headers consistently use "Layer 2", "Layer 1", "Layer 3", "Layer 4" (lines 97, 138, 168, 219). All consistent. Order in prose §93 matches the diagram (bottom-up).
