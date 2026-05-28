# Round 2 Correctness audit

Re-audit of `kyo-jsonrpc/DESIGN.md` against the current monorepo (`crispy-swinging-lemur` worktree) and the relevant protocol specs. Round-1 issues (Json.Value, Json.encode, Span.Builder, Outcome ADT) have been corrected in the current DESIGN; this round verifies the new claims.

## Disproven claims

### D1. `Structure.Value`'s case list is incomplete in §3

**Claim** (DESIGN.md §3, line 105): "`Structure.Value` (an enum from `kyo-schema/Structure.scala`: `Str | Bool | Integer | Decimal | BigNum | Null | Record(fields: Chunk[(String, Value)]) | Sequence(elements: Chunk[Value])`)."

**Evidence**: `kyo-schema/shared/src/main/scala/kyo/Structure.scala:288-318` defines TEN cases, not eight. The two missing are:
- `case VariantCase(name: String, value: Value)` (line 293) — used for ADT/sum-type variants
- `case MapEntries(entries: Chunk[(Value, Value)])` (line 299) — used for map types

**Severity**: minor (documentation, but load-bearing for the codec). The codecs must decide what to do with these cases. The Cdp codec's "stamp fields from extras to top level" can't ignore `VariantCase` or `MapEntries`; the decoder must classify them when they arrive on the wire (they won't, from a JSON parser, but the `Structure.Value` may come from an in-process `Schema[T].toStructureValue` call where the value is a sealed trait or a `Map`).

**Fix**: List all ten cases in §3. Spec codec behavior for `VariantCase` (treat name+value as a Record-shaped `{name: value}` per JSON-RPC convention, or reject) and `MapEntries` (encode as Record when keys are Str, else reject).

---

### D2. `JsonRpcCodec.encode` signature can't surface CDP reserved-key rejection

**Claim** (DESIGN.md §3, line 135 and §3 line 154): "REJECTING any extras key in `cdpReservedKeys` with a `JsonRpcError.invalidRequest`." The signature is:

```scala
def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < Sync
```

**Evidence**: The effect row is `Sync` only. There is no `Abort[JsonRpcError]` channel. The codec cannot raise `JsonRpcError.invalidRequest`; it can only `Sync.defer(...)` or panic. Round-1 audit raised this exact security concern (R5) and the design's §3 fix replied "with a `JsonRpcError.invalidRequest`" — but the signature wasn't updated to permit aborting with it.

**Severity**: blocker. Without a way to surface the error, the only options are (a) panic (bad UX, loses the typed error), (b) silently drop offending keys (security regression), or (c) the signature returns `Structure.Value < (Sync & Abort[JsonRpcError])`.

**Fix**: change the codec signature to:
```scala
def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync
```
Update §6 to propagate `Abort[JsonRpcError]` through `call` / `notify` (already in their effect row as `Abort[JsonRpcError | Closed]`).

---

### D3. `HandlerCtx.cancelled` typed as `Fiber.Promise[Unit, Any]` but stored elsewhere as `AtomicBoolean`

**Claim** (DESIGN.md §5 line 229 vs §6.1 line 370-371):
- Line 229: `cancelled: Fiber.Promise[Unit, Any]` (HandlerCtx field)
- Line 370-371: `case class Running(... cancelled: AtomicBoolean)` (InboundEntry field referenced as "the AtomicBoolean backing HandlerCtx.cancelled")

**Evidence**: Direct contradiction. Either the handler observes a `Fiber.Promise` (parkable via `.get`, completable once) or an `AtomicBoolean` (poll-only, non-parking). They're not interchangeable: a handler awaiting `ctx.cancelled.get` cannot poll an `AtomicBoolean`; a handler checking `ctx.cancelled.get()` cannot wait on a Promise.

Round-1 issue #5 noted the `Fiber.Promise[Unit, Any]` weirdness but called it "minor". With the new §6.5 introducing `AtomicBoolean` for the suppress flag AND for cancellation, the inconsistency is now substantive.

**Severity**: major (internal inconsistency that blocks implementation).

**Fix**: Pick one. Recommendation: keep `HandlerCtx.cancelled: Fiber.Promise[Unit, Sync]` (matches kyo idioms; handler can either poll via `.done` or await via `.get`). The `suppress` flag in `Replying` remains a separate `AtomicBoolean` (no parking needed on the writer-check path). Update §6.1's `Running` case to `cancelled: Fiber.Promise[Unit, Sync]` and §6.5 to clarify that "completing `HandlerCtx.cancelled`" means `promise.complete(Result.unit)`.

---

### D4. `Schema.toStructureValue` / `fromStructureValue` is `private[kyo]`; design relies on package-`kyo` access — verified, but `Structure.encode` is the public alternative that the design ignores

**Claim** (DESIGN.md §3 line 109, §18 line 956): "`summon[Schema[T]].toStructureValue(t)` (kyo-schema, `private[kyo]`, accessible since we're in package `kyo`)."

**Evidence**:
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala:1016`: `private[kyo] def toStructureValue(value: A)(using Frame): Structure.Value` — confirmed.
- `kyo-schema/shared/src/main/scala/kyo/Structure.scala:44`: `def encode[A](value: A)(using schema: Schema[A], frame: Frame): Structure.Value` — PUBLIC API doing the same thing.

The design ignores `Structure.encode` (public) in favor of `Schema.toStructureValue` (private[kyo]). Both work from package `kyo`, but the public API is the principled choice.

**Severity**: minor (style / API hygiene). The chosen private API is accessible and works.

**Fix**: Replace `summon[Schema[T]].toStructureValue(t)` with `Structure.encode(t)` (and analogously `Structure.decode(v)` for the reverse) throughout §3, §7, §8, §18. No new helper, public-API surface, identical semantics.

---

### D5. `Fiber.current` does NOT exist — design implicitly relies on capturing the calling fiber for callerRegistry

**Claim** (DESIGN.md §6.1 line 360): `private[kyo] case class CallerInfo(method: String, callerFiber: Fiber[Any, Any])`. The text says (line 357) "callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo]: side-table consulted by endpoint.cancel(id) to ... get the caller fiber to interrupt."

**Evidence**: `grep -n "def current" kyo-core/shared/src/main/scala/kyo/Fiber.scala` finds no hit. There is no `Fiber.current` API to capture the calling fiber inside `endpoint.call`. The pattern kyo uses is `Fiber.initUnscoped(...)` returning the new fiber (e.g. `Async.timeout` line 192).

**Severity**: major (mechanism unspecified). The design needs to specify HOW the caller fiber is captured. Options:
(a) `endpoint.call` runs the body inside a forked fiber and stores that fiber's handle — adds one fiber per call, defeats Exchange's pending-promise model.
(b) Skip capturing the caller fiber; cancel by completing Exchange's pending promise with `Abort.fail(RequestCancelled)` directly. The caller awaiting `promise.get` then aborts. NO fiber interrupt needed.

Option (b) is the right one: completing the pending promise IS the interrupt. The `callerFiber: Fiber[Any, Any]` field is unnecessary.

**Fix**: Remove `callerFiber` from `CallerInfo`. Spec §7's outbound-cancel step 4 as: "Complete the Exchange pending entry for `id` with `Result.fail(JsonRpcError.cancelled(reason))`. The caller fiber awaiting `promise.get` will see the failure and Sync.ensure removes the entry." This is what Exchange already does for `failAllPending` (Exchange.scala:412-417). `callerRegistry` reduces to `Map[JsonRpcId, String]` (id → method name only, for `protectedMethods` lookup).

---

### D6. `JsonRpcEndpoint.init` claimed to return `< (Async & Scope)` but builds Exchange + reader/writer fibers — needs `Sync` too

**Claim** (DESIGN.md §6 line 341): `JsonRpcEndpoint < (Async & Scope)`.

**Evidence**: Exchange's scoped `init` returns `Exchange[...] < (Sync & Scope)` (Exchange.scala:95). Initializing channels / atomics / forking fibers is all `Sync`. The Endpoint init must include `Sync` in its row. The current effect row `Async & Scope` does subsume Sync semantically (Sync ⊆ Async typically), but kyo conventions spell `Sync` explicitly.

**Severity**: minor.

**Fix**: change to `JsonRpcEndpoint < (Sync & Async & Scope)` or note that Async subsumes Sync per kyo's effect hierarchy.

---

## Unverifiable claims

### U1. "writer fiber checks `suppress` before sending" closes the cancellation race

Walk-through of the §6.5 fix:

1. Handler returns at time T0. Engine code wants to transition `pendingInbound[id]` from `Running(_, _, cancelled)` to `Replying(method, suppress=AtomicBoolean(false))` and enqueue the writer message.
2. Cancel arrives at time T1, where T0 < T1 < (transition complete). Cancel policy looks up `pendingInbound[id]`, sees… what?

The design doesn't specify whether the `Running -> Replying` transition is an atomic CAS on the map slot. If not atomic:
- Cancel reads `Running(_, _, cancelled)`, completes `cancelled`, returns. (Handler already exited; ignored.)
- Engine then writes `Replying(method, suppress=false)`. Suppress is never set. Writer sends the reply. **MCP violation.**

If atomic CAS is implied:
- The transition needs ordering: (a) replace map slot atomically, (b) enqueue writer message AFTER the slot is `Replying`. Otherwise the cancel can see `Replying` but the writer message is queued without an entry.

**Severity**: major (race not actually closed).

**Fix**: §6.5 must spell out the atomicity:
1. CAS `pendingInbound[id]` from `Running` to `Replying(method, suppress=AtomicBoolean(false))`. If CAS fails (cancel raced in and removed the entry), drop the response and exit.
2. Enqueue `WriterMsg.SuppressIfCancelled(id, env)` AFTER the CAS succeeds.
3. Cancel intercept: if it sees `Running`, it must ALSO CAS to a `Cancelled` terminal state so that the post-handler transition (step 1 above) fails.
4. For MCP no-reply, cancel intercept that sees `Replying(_, suppress)` sets `suppress.set(true)`. Writer dequeue reads `pendingInbound[id]` to check; OR writer reads `suppress` from a snapshot captured at enqueue time.

The simpler design: cancellation removes the entry; the engine's post-handler transition uses `replace` with a witness on the previous state. Otherwise the race re-opens.

---

### U2. "Exchange.Message.Skip" inside-decode dispatch — `Fiber.initUnscoped` requires `Isolate[S, Sync, S2]` resolved at the call site

**Claim** (DESIGN.md §6.1 line 388, §6.6 line 477): "For `Request` and `Notification` envelopes it does the engine-side dispatch (fork handler / fire policy intercept) inside `decode` and returns `Exchange.Message.Skip` (Exchange doesn't need to do anything more)."

**Evidence**: `Fiber.initUnscoped` (Fiber.scala:163-171) requires `Isolate[S, Sync, S2]` evidence. The handler's effect row is `Async & Abort[JsonRpcError]`; the engine has to supply the Isolate. Standard isolates exist; this should work, but the design should call it out.

**Severity**: minor (implementability — not blocking, just an unstated dependency).

**Fix**: in §6.1 note "the engine forks via `Fiber.initUnscoped` using the standard `Isolate` for `Async & Abort[JsonRpcError]` (kyo-core supplies these via `Isolate.derive` or the implicit search at the use site)."

---

### U3. "endpoint.notify bypasses Exchange via direct write to writer channel"

**Claim** (DESIGN.md §6.1 line 387, §20 invariant #4): "endpoint.notify → outbound.put(envelope) directly, bypassing Exchange's pending machinery."

**Evidence**: Exchange.scala does NOT expose its `safeSend` callback as a public method; outside callers cannot invoke it. BUT: the engine controls Exchange construction and wires `Exchange.send = outbound.put`. So the engine itself can also do `outbound.put` directly for notifications, bypassing Exchange entirely. This is valid because the engine owns BOTH `outbound: Channel[OutboundMsg]` and Exchange's `send` callback.

**Severity**: confirmed correct.

---

### U4. "Sync.ensure" for callerRegistry cleanup — verified mechanism

**Claim** (DESIGN.md §6.1 line 357): callerRegistry is "Populated when an outbound call enters Exchange; removed via `Sync.ensure` on exit (response, timeout, scope close, cancel)."

**Evidence**: Sync.ensure (Sync.scala:66) exists with signature `inline def ensure[A, S](inline f: => Any < (Sync & Abort[Throwable]))(v: => A < S): A < (Sync & S)`. Wrapping `exchange(req)` with `Sync.ensure(removeFromRegistry)(exchange(req))` is sound and matches Exchange's own pattern at Exchange.scala:275.

**Severity**: confirmed correct (modulo D5 — the registry contents are wrong).

---

## Race conditions / logical hazards

### H1. Cancellation race during `Running -> Replying` transition (see U1 above)

Disproves the design's claim that the §6.5 fix closes the race. Must be reworked with explicit atomic CAS on the map slot.

**Severity**: blocker for MCP correctness.

---

### H2. callerRegistry / Exchange pending-map cross-fiber ordering

**Walk-through**: `endpoint.call(method, params)`:

```
1. Allocate id (Exchange's internal counter)
2. Register callerRegistry[id] = CallerInfo(method, ...)
3. Exchange.apply(req)  // registers pendingOutbound[id]
4. Exchange.apply suspends on promise.get
5. eventually: response arrives OR cancel OR timeout
6. Sync.ensure removes callerRegistry[id]
```

Hazard: a cancel arriving between (1) and (2) sees no callerRegistry[id] (drops silently — correct). A cancel arriving between (2) and (3) sees callerRegistry[id] but Exchange has no pendingOutbound[id]; the engine can't interrupt the caller through Exchange's machinery (because Exchange hasn't registered the promise yet). The design's "complete the pending promise" approach (D5 fix) fails here.

**Severity**: minor (narrow window; the engine should fold registry+Exchange.apply into a single Sync.defer block so the cancel can't observe a half-registered state).

**Fix**: §6.1 must specify the registration order: do callerRegistry insert AFTER Exchange.apply registers its pending promise (since Exchange.apply provides the id), or fuse both into a single `Sync.Unsafe.defer` block so the cancel intercept observes either both or neither.

---

### H3. CDP codec's reserved-key collision detection happens at encode time, but encoder is `Sync`-only (D2 dup)

See D2. Without `Abort[JsonRpcError]` in the encoder's effect row, the rejection can only panic or silently drop.

---

### H4. `ProgressPolicy.enforceMonotonic` — "last emitted value's progress" storage unspecified

**Claim** (DESIGN.md §8 line 670): "looks up the `progress` field in `value` and compares against the last emitted value's `progress`."

**Walk-through**: Where is "last emitted" stored?
- Per progress token: needs `ConcurrentHashMap[Token, AtomicReference[Double]]` (or similar).
- The design doesn't introduce this map.
- Concurrent `ctx.progress(v1)` and `ctx.progress(v2)` from the same handler need linearization: two threads checking `last < newValue` then writing can both observe the old value and both pass. Needs CAS loop, not just `last.set(new)`.

**Severity**: major (silent corruption of MCP monotonicity guarantee under concurrency).

**Fix**: Add a `progressLastEmitted: ConcurrentHashMap[Structure.Value, AtomicLong]` (or AtomicRef[Double], since `progress` is a number) to §6.1. Specify CAS-loop semantics: read prev; if newValue > prev, CAS prev → newValue; if CAS fails retry. If newValue ≤ prev or CAS retries exceed N, drop silently.

---

### H5. `partialResults` channel-vs-non-empty-response handling unspecified

Round-1 issue R6 — still unresolved in current DESIGN. §8.3 says "The final empty response from the peer closes the stream" but doesn't specify what to do if peer ignores the partialResultToken and replies normally with a non-empty result (legal per LSP/MCP — both progress hints are SHOULD-emit).

**Severity**: major.

**Fix**: §8 must specify the policy: emit the final response value as the last chunk and close (peer chose not to stream). If chunks were already received via `$/progress` AND the final response is non-empty, fail with `RequestFailed`.

---

### H6. Per-direction id allocation when peer reuses an id

Round-1 issue R2 caveat — still unresolved. If peer sends Request(5), Request(5) again (illegal per MCP id-uniqueness; allowed per LSP/CDP), the second overwrites the first's `pendingInbound[5]` entry. The first handler fiber leaks from cancellation lookup.

**Severity**: minor.

**Fix**: Detect existing entry on insert; for MCP profile reply `InvalidRequest`; for LSP/CDP log+overwrite. Promote to a policy knob on `Config`.

---

### H7. Scope finalizer order vs Exchange's own scoped close

**Claim** (DESIGN.md §6.4 line 441): cleanup order is "1. Poison writer. 2. Cancel reader. 3. Cancel writer. 4. transport.close. 5. Close Exchange. 6. Close progressStreams. 7. Interrupt pendingInbound."

**Evidence**: Exchange itself, when scope-initialized (Exchange.scala:96), registers `Scope.acquireRelease(initUnscoped(...))(ex => Sync.Unsafe.defer(ex.close()))`. The Endpoint's Scope will run finalizers in REVERSE registration order. If Endpoint registers Exchange-init first, then writer/reader fibers, then transport, Scope teardown will reverse-order this — possibly conflicting with the design's specified order.

**Severity**: minor (implementation will need to use `initUnscoped` and manage all of these manually, OR carefully order registrations to get the specified teardown order).

**Fix**: §6.4 should specify that the Endpoint uses `Exchange.initUnscoped` (not scoped) and registers a single composite finalizer in `Scope.acquireRelease` that runs the §6.4 sequence explicitly.

---

## API method calls that don't exist on main

| Used in DESIGN.md | Status | File:line |
|---|---|---|
| `Fiber.current` | DOES NOT EXIST | grep over `kyo-core/shared/src/main/scala/kyo/Fiber.scala` |
| `Schema[T].toStructureValue` | Exists, `private[kyo]` | Schema.scala:1016 |
| `Schema[T].fromStructureValue` | Exists, `private[kyo]` | Schema.scala:1029 |
| `Structure.encode[A]` (public alt the design ignores) | Exists, PUBLIC | Structure.scala:44 |
| `Structure.decode[A]` | Exists, PUBLIC | Structure.scala:57 |
| `Exchange.init` (scoped, sequential Int) | Exists | Exchange.scala:90 |
| `Exchange.init` (custom Id, with nextId) | Exists | Exchange.scala:118 |
| `Exchange.initUnscoped` (both variants) | Exists | Exchange.scala:144, 168 |
| `Exchange.Message.{Response, Push, Skip}` | Exists | Exchange.scala:63-72 |
| `exchange.apply`, `events`, `awaitDone`, `close` | All exist | Exchange.scala:259, 295, 303, 307 |
| `Fiber.initUnscoped` | Exists (needs Isolate at call site) | Fiber.scala:163 |
| `Fiber.Promise[A, S]` | Exists; first param value, second effect row | Fiber.scala:440 |
| `Channel.init`, `Channel.initUnscoped`, `Channel.Unsafe.offer` | All exist | Channel.scala:325, 374, 398 |
| `Meter.initSemaphore` | Exists | Meter.scala:169 |
| `Async.timeout` | Exists | Async.scala:165 |
| `AtomicLong`, `AtomicInt`, `AtomicBoolean`, `AtomicRef` | All exist as `final case class … private(unsafe: …)` with `.init` | Atomic.scala:16, 205, 392, 517 |
| `Sync.defer`, `Sync.Unsafe.defer`, `Sync.ensure` | All exist | Sync.scala:49, 66, 137 |
| `Maybe`, `Present`, `Absent` | Exist | Maybe.scala:12, 82, 133 |
| `Span` | Exists | Span.scala:36 |
| `Chunk`, `ChunkBuilder` (NOT `Chunk.Builder`) | Exist (top-level `ChunkBuilder`, not nested) | Chunk.scala, ChunkBuilder.scala |
| `Scope.acquireRelease` | Exists | Scope.scala:86 |
| `Schema.maybeSchema[A]` (auto-given for `Schema[Maybe[A]]`) | Exists | Schema.scala:1477 |
| `JsonRpcCodec.encode` returning `Structure.Value < Sync` | DESIGNED INCORRECTLY — needs `Abort[JsonRpcError]` to surface reserved-key rejection (D2) | n/a |
| `Structure.Value` enum — design lists 8 cases | INCOMPLETE — actual has 10 (D1) | Structure.scala:288-318 |

## Confirmed correct (load-bearing claims)

- **Exchange architecture**: `Exchange` owns the outbound id-to-promise pending map (`pendingMap: ConcurrentHashMap[Id, Promise.Unsafe[Resp, Abort[E|Closed]]]`, line 179), the reader fiber (`readerLoop`, line 200), the `nextId` allocator (callback, line 169), `Sync.ensure` cleanup on caller interrupt (line 275), `donePromise` for orderly close (line 178). Late-reply drop is structurally guaranteed: `readerLoop` does `pending.remove(id).foreach(...)` (line 213); a missing entry means silent drop. The design's framing (Layer 0 owns these things; the engine adds bidirectionality, JSON-RPC envelopes, dispatch, cancellation, progress) is now ACCURATE.
- **`Exchange.Message.Skip` enables inside-decode dispatch**: the decode callback is Sync-only (Exchange.scala:88 spells this out), and forking handlers via `Fiber.initUnscoped` is Sync-compatible.
- **Notification-bypass via shared writer channel**: the engine wires `Exchange.send = outbound.put`. `endpoint.notify` writing to the same channel bypasses Exchange's pending machinery cleanly.
- **`Structure.encode` / `Structure.decode` are PUBLIC**: better public-API alternatives to the design's `Schema[T].toStructureValue` choice. Both yield `Structure.Value` directly.
- **`Schema[Maybe[A]]` is auto-given** (Schema.scala:1477), so `JsonRpcEnvelope derives Schema` works with the `Maybe[Structure.Value]` and `Maybe[JsonRpcError]` fields.
- **Spec claims**: re-verified via WebFetch — JSON-RPC 2.0 base codes / notification-no-reply / batch / id-null; MCP `notifications/cancelled` SHOULD-not-reply, `initialize` MUST-NOT cancel; LSP `$/cancelRequest` MUST-reply (with RECOMMENDED -32800), `$/`-prefix drop rule, Content-Length framing. All match DESIGN.md verbatim. (Round-1 audit verified the rest in items B-K; nothing changed.)
- **Per-direction id allocation routing** (§10): sound for the common case. Each endpoint's Exchange pendingMap is keyed on ids IT allocated; incoming Response routes there; incoming Request goes via `pendingInbound`. No cross-collision.
- **Timeout-fires-cancellation late-reply scenario** (§7 last paragraph): safe. Late reply hits empty Exchange pending → silent drop. Same for late cancel-of-completed-handler.
- **§6.4 scope-cleanup ordering principle** (poison writer → cancel fibers → close transport → close Exchange → drain progress → interrupt inbound): logically correct, though implementation must use `Exchange.initUnscoped` to control the sequence (see H7).
- **JsonRpcEndpoint.cancel(id) refusing on `protectedMethods`** (MCP `initialize`): correctly maps to the spec (verified via WebFetch above).
- **Reader-fiber discipline** (§6.6): all listed operations are `Sync`-only and won't park, matching Exchange's reader-fiber contract.
- **Sequential reader-decode dispatch model**: forking handlers via `Fiber.initUnscoped` and returning `Skip` to Exchange keeps the reader unblocked. Sound.
