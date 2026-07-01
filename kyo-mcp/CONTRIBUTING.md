# Contributing to kyo-mcp

This guide carries the conventions, invariants, and patterns specific to `kyo-mcp`, a
[Model Context Protocol](https://modelcontextprotocol.io) server and client built over
`kyo-jsonrpc`. For repo-wide rules (effect-row discipline, naming, the safe-by-default
tiers, the `using`-clause ordering, the test-file naming rule, the build/test commands,
and the cross-platform shared-test rule) read the root
[CONTRIBUTING.md](../CONTRIBUTING.md) first; this file does not restate them. What follows
is the module's own shape: the one contract every change turns on, the layered internals,
the handler-authoring DSL, the reverse-direction dispatch, the lifecycle and unsafe
boundaries, the error model, the codec conventions, the extension recipes, the test
patterns, and a decision checklist.

## The headline invariant: a total wire surface

The module's central design contract is that **a malformed or hostile peer message is
always a typed failure, never a thrown panic, and a wrong-direction or wrong-type handler
registration is always a compile error.** Two failure modes are unrepresentable by
construction.

**Every closed-vocabulary decode is total.** MCP wire enums (`McpContent.Role`,
`McpServer.LogLevel`, `ElicitationResponse.Action`, `SamplingRequest.IncludeContext`,
`McpCapabilities.Name`) and the hand-rolled discriminator schemas
(`McpContent`, `McpHandler.ResourceContents`, `McpHandler.CompletionRef`,
`McpServer.SamplingContent`) decode an unrecognized wire string by throwing
`TypeMismatchException` (a `DecodeException`). The JSON-RPC receive loop runs decode under
`Result.catching[DecodeException]`, so that throw surfaces as `Result.Failure` and is
answered with a correlated error response. The contrast that makes this load-bearing: any
other decode path (`Enum.valueOf`, a pattern match on capitalized strings) throws a JVM
exception type that is **not** a `DecodeException`, so the loop would not catch it and a
hostile peer string would panic the dispatch fiber. Route every new closed-vocabulary enum
through `internal.mcp.McpEnumSchema.closed`, and every new multi-arm discriminator through
the same `TypeMismatchException` mechanism (`McpEnumSchema.discriminatorMismatch` for the
reader-driven schemas). Never add `Schema` to a closed enum's `derives` clause; the comment
`Do NOT add Schema to the derives clause` sits on those enums for exactly this reason.

**Direction and type are baked into the carrier.** A server-side endpoint is an
`McpHandler[In, Out, +E]`; a client-side reverse-direction endpoint is a distinct
`McpClientHandler[In, Out, +E]`. The carriers are unrelated types, so a server `tool` /
`resource` / `prompt` passed to `McpClient.init` does not typecheck, and an `onSampling` /
`onRoots` passed to `McpServer.init` does not typecheck. Each factory bakes in the wire
method string and the `(In, Out)` schemas, so a wrong method name or response type cannot
be supplied at the call site. `McpHandler.Direction` (`ServerHandled` / `ClientHandled`) is
the explicit runtime tag the engine reads to route; the type distinction is what the
compiler enforces ahead of it.

When you extend the surface, preserve both halves: keep decode total (a new peer-supplied
vocabulary is a typed failure, never a panic), and keep direction/type carrier-encoded (a
mis-registration is a compile error, not a runtime check).

## Architecture

The public surface lives in `shared/src/main/scala/kyo/` (the `kyo.*` namespace); the
engine, codecs, and policies live in `shared/src/main/scala/kyo/internal/mcp/` and
`shared/src/main/scala/kyo/internal/`, all `private[kyo]`. Each layer depends only on the
ones below it.

| Layer | Types | Role |
|-------|-------|------|
| Public handles | `McpServer`, `McpClient` | The two opaque connection handles; each `opaque type T = T.Unsafe`. |
| Handler DSL | `McpHandler`, `McpClientHandler` | The author's vocabulary: the server-side and reverse-direction endpoint carriers and their factories. |
| Handler accessors | `Mcp` | The per-request context (`Mcp.server`, `Mcp.progress`, `Mcp.requestId`, `Mcp.cancelled`, `Mcp.extras`) bound into a `Local` for one dispatch. |
| Value types | `McpContent`, `McpConfig`, `McpCapabilities`, `McpResourceUri`, `McpMimeType`, `McpInfo`, `McpCursor`, `McpNotification` | The wire vocabulary the author and the peer exchange. |
| Errors | `McpException` and its fifteen per-operation failure traits | The typed error channel; each public operation's row is a sealed operation-trait, and every leaf is a `JsonRpcApplicationError`. |
| Engine | `internal.mcp.McpEngine`, `internal.mcp.McpClientEngine` | Compose the gates, routes, and handshake into a live `T.Unsafe`. |
| Lift | `internal.mcp.McpHandlerLift`, `internal.mcp.McpClientHandlerLift` | Lift each carrier to a `JsonRpcRoute`, binding `Mcp.local` for the dispatch. |
| Routing | `internal.mcp.McpBuiltInRoutes`, `internal.mcp.McpReverseDispatch`, `internal.mcp.McpCatalog` | The `tools/*`, `resources/*`, `prompts/*`, `completion/*` routes; the reverse-direction defaults; the frozen handler snapshot. |
| Gates | `internal.mcp.McpHandshakeGate`, `internal.mcp.McpCapabilityGate` | The two `JsonRpcMessageGate`s composed before dispatch. |
| Codecs | `internal.McpContentSchema`, `internal.McpCompletionRefSchema`, `internal.McpSamplingContentSchema`, `internal.mcp.McpEnumSchema` | The hand-rolled discriminator and closed-enum schemas. |
| Policies | `internal.mcp.McpProgressPolicy`, `internal.mcp.McpCancellationPolicy`, `internal.mcp.McpUnknownMethodPolicy` | The MCP-specific adapters wired into the JSON-RPC config. |

The whole stack sits on `kyo-jsonrpc`: an MCP server/client is a `JsonRpcHandler` over a
`JsonRpcTransport`, every handler is a `JsonRpcRoute`, every MCP error is a
`JsonRpcApplicationError`, and `McpConfig.jsonRpc` is a `JsonRpcHandler.Config` with
MCP-specific policy slots filled in. `McpServer.underlying` / `McpClient.underlying` expose
the `JsonRpcHandler` as the escape hatch for advanced consumers.

## The opaque-type-is-its-Unsafe pattern

`McpServer` and `McpClient` are each defined as `opaque type T = T.Unsafe`: the safe handle
**is** its own `Unsafe` implementation at runtime, with the safe API supplied as extension
methods in the companion and the unsafe API as an `abstract class Unsafe`. This is the
zero-cost two-tier pattern from the root guide's Unsafe Boundary, applied to a connection
handle.

- The safe extension methods take `(using Frame)` and return effectful values over a precise
  operation-trait row (`A < (Async & Abort[McpRequestSamplingFailure])`, or
  `Abort[McpConnectionClosedException]` for the notification and progress sinks); they enter the
  unsafe tier through `Sync.Unsafe.defer(self.method(...).safe.get)`, which provides `AllowUnsafe`
  and lifts the `Unsafe` method's `Fiber.Unsafe` back into the effect.
- The `Unsafe` methods take `(using AllowUnsafe, Frame)` and return raw `Fiber.Unsafe[...]`,
  never `A < S`.
- `T.unsafe` returns the `Unsafe` handle; `Unsafe.safe` returns the safe handle. Because the
  opaque type erases to its `Unsafe`, both are zero-cost identity at runtime.

**Scala 3 forbids a wildcard on an opaque type**, which is why the handler families are
sealed traits, not opaque types: the engine and the lift pattern-match across
`McpHandler.ToolHandler`, `McpHandler.ResourceHandler`, and the rest, and a sealed trait
admits the `case h: McpHandler.ToolHandler[?, ?, ?] => ...` match that an opaque type would
not. The concrete carriers are `final private[kyo] class` leaves of the sealed trait; only
the trait and the factory results are public.

## The handler-authoring DSL

`McpHandler`'s factories use **clause interleaving** so the author annotates only `[In]`
and the compiler infers `[Out]` from the handler's return type:

```scala
inline def tool[In](name: String, ...)(using inSchema: Schema[In])[Out, E](
    handler: In => Out < (Async & Abort[JsonRpcResponse.Halt | E])
)(using outSchema: Schema[Out], frame: Frame): McpHandler[In, Out, E]
```

The handler body's row is `Abort[JsonRpcResponse.Halt | E]`: the user's own `E` plus the
short-circuit control signal, never a framework `McpException` blanket. `E` defaults to nothing
when the body raises no module failure, and a body that calls a `Mcp.*` accessor infers that
accessor's leaves into `E`.

The first type-parameter clause holds the user-specified `[In]`; the second
(`[Out, E]`, after the first `using` clause) holds types inferred from the handler. This is
the root guide's `(using Frame)`-as-separator pattern, here using the `Schema[In]` clause as
the separator. The factory family:

- `tool[In](...)[Out, E]` / `toolRaw[In](...)[E]`: `tools/call`. `tool` takes a handler
  returning one `Out` value; the lift encodes it into both `structuredContent` (via
  `Structure.encode[Out]`) and a text mirror (via `Json.encode[Out]`), and advertises
  `outputSchema` from `Out`, so the three coupled fields agree by construction. `toolRaw`
  takes a handler returning a full `ToolOutcome` for total control (multiple content leaves,
  a pure-content tool, in-band `isError`); it advertises no `outputSchema`. Reach for `tool`
  when one returned value should drive the structured output, `toolRaw` for the lower-level
  escape.
- `resource(uri, ...)[E]`: a fixed-URI `resources/read`. The handler is a **by-name**
  effectful value of `Chunk[ResourceBody]`, because the URI is fully known at registration:
  the engine dispatches here only when the inbound URI equals the registered one. The handler
  returns **URI-less** `ResourceBody` values; the engine stamps the registered URI (and the
  registered default mime when the body's is `Absent`) onto each, so a body whose URI
  disagrees with the registration is unrepresentable. `subscribe = true` opts the resource
  into the subscription protocol.
- `resourceTemplate(uriTemplate, ...)[E]`: a URI-template `resources/read`. The handler
  receives a `ResourceMatch` carrying the matched URI and pre-extracted RFC 6570 Level 1
  bindings; `ResourceMatch.requireVariable` aborts `McpInvalidArgumentException` on a missing
  required binding rather than returning `""`.
- `prompt[In](...)[E]` (typed) and `prompt(name, description, arguments)[E]` (raw map):
  `prompts/get`. The typed form derives the advertised `PromptArgument` list from `In`'s
  fields (a `Maybe` field is `required = false`) and decodes the inbound `Map[String,String]`
  into `In`, surfacing a typed decode failure when a required field is absent rather than a
  silent `""`. The raw-map form is the escape hatch for per-argument description/title that
  have no home on a bare `In` field.
- `completion(ref)[E]` / `completion(promptHandler)[E]` / `completionWith(ref)[E]`:
  `completion/complete`. The 1-arg form discards the previously-filled context; `completionWith`
  receives `(arg, Maybe[Context])` per the spec's §3.17 context. The `completion(promptHandler)`
  overload reads the ref off a registered prompt handler value, removing the third restatement
  of a prompt name. The wire `ref` is omitted from every completion closure because the engine
  dispatches here only when the inbound ref matches the registered one.
- `custom[In](method)(...)[Out, E]`: an arbitrary JSON-RPC method, the magic-string escape
  hatch.

Domain errors are attached fluently with `.error[E2](code, message)` (mirroring
`JsonRpcRoute.error`), or with the `.error[E2]` type-class overload that reads the code and
message off an `McpHandler.McpErrorCode[E2]` instance. An author code in the framework-reserved
range `-32003..-32000` is rejected when the server is built (`McpEngine.initServer` throws an
`IllegalArgumentException` before returning).

### The empty-dropping smart constructors

`ToolOutcome.content(items*)` and `PromptMessage.messages(items*)` are smart constructors
that **drop a blank text leaf**: an `McpContent.Text` whose `text.trim.isEmpty` is filtered
out so a handler cannot emit blank text (or a blank-content message) on the wire; non-text
leaves pass through unchanged. `ToolOutcome.ok` and `ToolOutcome.error` route through
`content`, so they inherit the same drop. When you add a content-bearing outcome type, route
its construction through one of these (or add a sibling smart constructor with the same drop),
do not let raw blank text reach the wire.

The `*.empty` records (`ToolAnnotations.empty`, `ResourceAnnotations.empty`,
`McpContent.Annotations.empty`) are the default parameter on every factory and the
omit-sentinel on the wire: a factory translates `.empty` into `Absent` so the field is
omitted, and the hand-rolled schema omits the `annotations` field when the runtime value
equals `.empty` (and restores `.empty` when it is absent on decode).

## Reverse-direction dispatch and capability gating

The server can issue three request methods to the client (`sampling/createMessage`,
`roots/list`, `elicitation/create`) plus notifications. The author reaches the connected
peer two ways: through the live handle returned by `McpServer.requestSampling` /
`requestRoots` / `requestElicitation` (and the typed `requestElicitationAs[A]`), or, inside
a server-side handler, through `Mcp.server` (or directly `Mcp.requestSampling`, which sends
without exposing the handle so it cannot leak into a detached fiber). On the client side, an
author answers these with `McpClientHandler.onSampling` / `onRoots` / `onElicitation`, and
sinks notifications with `onLog` / `onResourceUpdated` / `onNotification`.

`McpReverseDispatch.buildRoutes` installs a **default-reject** route per reverse method
first, then appends the user-registered handlers so a same-method registration overrides the
default (the underlying `JsonRpcHandler` is last-write-wins by method). The default route
encodes the **capability-gating contract**: it checks the relevant advertised capability
first and aborts `McpCapabilityNotAdvertisedException` with code **`-32601`** when the
capability is absent, only then falling through to a typed rejection
(`McpSamplingRejectedException`, `McpElicitationDeclinedException`, or, for roots, an
`McpInvalidArgumentException` rather than an overloaded empty `Chunk` so a real empty
workspace from a user `onRoots` stays distinguishable). The server side mirrors this: when a
reverse-direction call comes back with code `-32601`, `McpEngine`'s `requestSamplingEffect` /
`requestRootsEffect` / `requestElicitationEffect` re-raise it as
`McpCapabilityNotAdvertisedException` (peer = `Client`), so the `-32601` contract holds in
both directions.

Inbound requests are gated before dispatch by two composed `JsonRpcMessageGate`s
(`McpEngine` chains them in `composedGate`): the handshake gate runs first, the capability
gate second, and the capability gate runs only when the handshake gate returns `Allow`.
`McpCapabilityGate` has three modes from `McpConfig.CapabilityGateMode`: `RejectUnsupported`
(reject an inbound request whose required capability was not advertised, with `-32601`),
`LogOnly` (admit but warn), and `Off` (admit unconditionally, for dev/test). On the client
side, `McpClient`'s `guarded` wrapper applies the same gate **locally**: it aborts the same
typed `McpCapabilityNotAdvertisedException` the wire would, minus the round trip, before
sending a method whose capability the server did not advertise.

## Lifecycle: init vs initUnscoped, and the two close paths

Both handles follow the resource-factory convention from the root guide, with a deliberate
split in the close machinery.

- `McpServer.init` / `McpClient.init` (and the `initWith` / config-curried overloads) are
  **`Scope`-managed**: they `Scope.acquireRelease(...)(handle => handle.closeDirect)`, so the
  handle is closed exactly once on scope exit. This is the default; use it unless a manual
  lifecycle is genuinely required.
- `McpServer.initUnscoped` / `McpClient.initUnscoped` return an **unscoped** handle whose
  scaladoc carries the close-obligation: the caller owns the lifecycle and MUST close it
  (ideally under `Scope.ensure`), or the reader/writer fibers and the transport leak on
  interrupt. When you add an unscoped factory, repeat that close-obligation in its scaladoc.

The two close paths are distinct and not interchangeable:

- **`closeDirect`** (`private[kyo]`) runs `handler.close(Duration.Zero)` **in-place on the
  caller's fiber**, without spawning a detached fiber. It is what the `Scope.acquireRelease`
  release slot calls, so the close runs on the scope's finalizer fiber rather than spawning a
  new unsupervised fiber. Use it only from a release slot.
- **`close` / `closeNow`** (public) go through the `Unsafe.close(gracePeriod)` bridge, which
  **spawns a detached fiber** (`Fiber.initUnscoped`) so the caller's `Scope` does not cancel
  the in-flight close when the caller returns. `close` uses a 30-second grace period,
  `close(d)` an explicit one, `closeNow` a zero grace period. These are for manual (unscoped)
  callers.

The handshake is **once-only**, enforced by `McpHandshakeGate`: an `initialize` arriving
after the handshake already completed is rejected with `McpHandshakeAlreadyInitializedException`,
and any non-`ping` request arriving before the handshake completes is rejected with
`McpHandshakeNotInitializedException`. `ping` is always admitted (per spec §3.8, both sides
must answer it). `McpConfig.handshakeOrder` controls whether the
`notifications/initialized` notification is also required before general traffic is admitted.

## The Unsafe boundary inside the engine

The engines hold post-handshake negotiated state in `AtomicRef`s (negotiated version, client
capabilities, client info, log-level threshold, subscription set) plus a forward `serverRef`
that is published synchronously after construction so each dispatch can bind the live handle
into `Mcp.local`. Two unsafe idioms recur and must keep their `// Unsafe:` rationale comments:

- **Atomic pure reads.** The post-handshake accessors (`protocolVersion`,
  `clientCapabilities`, `clientInfo`) read the handshake-populated `AtomicRef` directly with
  `ref.unsafe.get()(using AllowUnsafe.embrace.danger)`, commented `// Unsafe: atomic ... pure
  read, no scheduling`. These are pure reads of already-published state, so they need no
  suspension.
- **The detach-then-reattach bridge.** Every `Unsafe` method that issues a reverse-direction
  request or a notification runs its inner effect on a **fresh unscoped fiber**
  (`Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(effect)).unsafe`), commented `// Unsafe:
  detach-then-reattach bridge; the inner effect runs on a fresh unscoped fiber so the caller's
  Scope does not cancel it when the handler returns`. This is why a reverse-direction call from
  inside a handler does not die when the handler's request scope completes. When you add a
  reverse-direction or notification method to `Unsafe`, follow the same bridge and carry the
  same comment.

The `AllowUnsafe.embrace.danger` import is scoped to the smallest expression (the per-ref
read, the per-init `AtomicRef.Unsafe.init`), never placed on the anonymous `Unsafe` class or
a constructor where it would leak to every method, per the root guide's narrow-scoping rule.

The client builds a **sentinel** `McpServer.Unsafe` (`buildClientSentinelServer`) whose
reverse-direction methods all reject: client-side route handlers have no reverse-direction
peer, but the lift requires a `serverRef`, so the sentinel satisfies the carrier without
allowing a handler to reach `Mcp.server` for a reverse call. A client-side handler must not
call `Mcp.server`; the sentinel exists only to avoid an `IllegalStateException` during
dispatch.

## The error model

`McpException` is a sealed `abstract class` over `JsonRpcApplicationError` (the cross-module
JSON-RPC extension point). Because it extends `JsonRpcApplicationError`, every `McpException` is a
valid `JsonRpcError` and travels through `Abort[JsonRpcError | ...]` rows transparently; the
inherited `Schema[JsonRpcError]` encodes any leaf as the `(code, message, data)` wire triple, so
**no separate `Schema[McpException]` is needed**.

The row type is **per operation**, not a blanket. Each public method aborts a sealed
operation-trait that names exactly the leaves that method can raise, so a `when` over the row is
exhaustive and the compiler flags a missed case. There are fifteen: `McpCallToolFailure` /
`McpCallToolRawFailure`, `McpReadResourceFailure` / `McpReadResourceRawFailure`,
`McpGetPromptFailure` / `McpGetPromptRawFailure` / `McpGetPromptCheckedFailure`, `McpListFailure`,
`McpCompleteFailure`, `McpClientRequestFailure`, the reverse-call `McpRequestSamplingFailure` /
`McpRequestElicitationFailure` / `McpRequestElicitationAsFailure` / `McpRequestRootsFailure`, and
`McpInitFailure` for the eager `McpClient.init` handshake. A concrete leaf **mixes in every
operation-trait it can occur in**, so one leaf is shared across the rows that produce it without
duplicating its fields or message; `McpException.scala` is the authority for each leaf's mix-in
list. User-domain errors are registered per-handler via `.error[E2](code, message)`, never by
extending the hierarchy, so the sealed surface stays closed.

Four leaf roles are load-bearing:

- **The shared transport leaf.** `McpConnectionClosedException` (-32603) mixes **all fifteen**
  operation-traits: a closed transport is one typed leaf reachable on every row, never a bare
  `Closed` leaking through (the engine maps the residual transport `Closed` to it at each recover
  site). The notification and progress sinks, which can fail only by racing a closing transport,
  name it directly as their single-leaf row `Abort[McpConnectionClosedException]`.
- **The remote forward.** A server handler's `.error[E2]` payload, and every engine-internal
  failure (the handshake, unknown-method, and handler-execution leaves raised server-side), reach
  the client re-encoded as `McpRemoteApplicationException`, which carries the remote
  `(code, message, data)` verbatim. Those engine-internal leaves therefore never appear on a public
  client row.
- **The typed-read leaves.** `McpToolStructuredMissingException` and `McpToolStructuredDecodeException`
  (both -32603) ride the typed client-read rows and carry the raw-vs-typed distinction below.
- **The construction panic.** `McpConfigurationError` (-32603) carries **no** operation-trait: a
  rejected configuration is a construction-time programmer error thrown by `McpConfig.require` and
  the engine's reserved-error-code check, so it panics and stays off every row (root guide's
  failure-tracking rule).

Three typed-read leaves carry a deliberate distinction the client's typed lanes depend on:

- `McpToolStructuredMissingException` is raised when a typed read finds **no** structured
  content (`structuredContent = Absent`); the message points the caller at the raw overload.
- `McpToolStructuredDecodeException` is raised when the payload is **present but does not
  decode** to the requested type. Keep these two distinct; collapsing them loses the
  actionable hint.
- `McpDecodeException` is the open-payload reader failure for the `*As[M]` projections
  (`ToolOutcome.structuredContentAs` / `metaAs`, `Page.metaAs`) and `Mcp.extras[T]`: a value
  present but non-conforming. (Note: `requestElicitationAs[A]` raises
  `McpToolStructuredDecodeException` for its non-conforming `Accept` payload, not
  `McpDecodeException`, even though the `McpDecodeException` scaladoc lists it; the code path
  is the source of truth.)

**Typed-vs-raw client lanes.** Every read-shaped client method has a typed default lane and
a raw escape hatch:

- `callTool[In, Out]` decodes `structuredContent` to `Out` (raising the missing-vs-decode
  distinction above); `callToolRaw[In]` returns the raw `ToolOutcome`.
- `readResource[Out]` decodes the single text leaf to `Out`; `readResourceRaw` returns the
  raw `Chunk[ResourceContents]`.
- `getPrompt[Out]` decodes the prompt's `_meta` to `Out`; `getPromptRaw` returns the raw
  `PromptOutcome`.

The typed lane delegates to the raw lane and decodes its result, so the two never diverge.
When you add a read method, supply both lanes the same way: the typed one delegates to the
raw one. A remote application error (a user error the peer registered via `.error`) surfaces
as `McpRemoteApplicationException`, preserving the wire `(code, message, data)` verbatim so
the caller pattern-matches on `code`.

## Schema and codec conventions

Three kinds of schema are hand-rolled in this module; the derivation macro is used for the
plain records (`derives Schema`).

- **Closed enums** go through `McpEnumSchema.closed[E]((wire, case)*)`, which builds a
  `Schema[E]` whose encode path is the supplied total case-to-wire table and whose decode
  path throws `TypeMismatchException` on an unknown wire string. The wire shape is identical
  to `Schema.stringSchema` (a plain JSON string); the decoder is built with `Schema.init` so
  its `readFn` receives the `Codec.Reader`, and the `TypeMismatchException` is raised under
  `reader.frame` so it attaches to the user's `decode` call site, not a synthetic internal
  frame. This is the in-module sanctioned totality mechanism; there is no `Result`-returning
  `Schema.transform` variant to use instead.
- **Discriminator schemas** (`McpContentSchema.contentSchema` and `resourceContentsSchema`,
  `McpCompletionRefSchema.schema`, `McpSamplingContentSchema.schema`) are anonymous
  `Schema[T]` subclasses (`new Schema[T](Seq.empty): ...`) that hand-roll `serializeWrite`,
  `serializeRead`, and `fromStructureValue` over the `"type"` discriminator key. They are
  **singletons**: each is a `val` referenced from the type's companion `given`, so every
  `summon[Schema[T]]` resolves to the same reference (`internal.SchemaSingletonTest` asserts
  this). The local `var`s inside `serializeRead` are reader-loop field accumulators (the
  standard streaming-reader idiom), not shared mutable state; they live and die inside the
  one `serializeRead` call. An unrecognized discriminator value throws `TypeMismatchException`
  the same way the closed enums do (the reader-driven schemas route it through
  `McpEnumSchema.discriminatorMismatch`, which owns the `given Frame = reader.frame`), so a
  bad `"type"` tag is a typed `Result.Failure`, not a panic.
- **Open-object fields** that the MCP spec leaves as arbitrary JSON (`structuredContent`,
  `_meta`, the elicitation response payload, experimental capabilities) are surfaced as
  `Maybe[Structure.Value]` (or `Map[String, Structure.Value]`), and the schemas mark their
  `structure` as `Structure.Type.Open`. Decode them to a typed `M` only at the explicit
  reader boundary (`*As[M]`, `Mcp.extras[T]`), which raises `McpDecodeException` on a
  non-conforming payload.

When you add wire vocabulary: a plain record gets `derives Schema, CanEqual`; a closed enum
gets `McpEnumSchema.closed` and **no** `Schema` in its `derives`; a discriminated union gets
a hand-rolled singleton schema in `internal/` that throws `TypeMismatchException` on an
unknown arm. Opaque-over-`String` identifier types (`McpResourceUri`, `McpResourceUri.Template`,
`McpConfig.ProtocolVersion`, `SamplingResponse.StopReason`) use a total `apply`/`fromWire`
constructor in their `Schema.stringSchema.transform` so the codec accepts any wire string;
validation (blank-rejection, supported-version check) lives at the user `parse` call site or
the handshake gate, never at the codec.

## Extension recipes

### Add a server-side endpoint kind end-to-end

Say you want a new `tools/call`-shaped endpoint with custom dispatch.

1. **Carrier.** Add a `final private[kyo] class FooHandler[...] extends McpHandler[In, Out, E]`
   leaf with `name`, `kind`, `errorMappings`, and both `.error[E2]` overloads delegating to
   the canonical one (copy the existing leaves; the two `.error` overloads are identical
   boilerplate across every leaf).
2. **Factory.** Add the `McpHandler.foo[In](...)[Out, E](handler)(using ...)` factory using the
   clause-interleaved signature so the author annotates only `[In]`. Mark it `inline` only if
   it expands `Json.jsonSchema[In]` / `Json.jsonSchema[Out]` at the call site (the tool and
   `toolRaw` factories are `inline` for exactly this; against an abstract type parameter the
   macro yields an empty Product).
3. **Lift.** Add a `liftFoo` arm to `McpHandlerLift.lift`'s match and a `liftFoo` method that
   builds the `JsonRpcRoute` and wraps the body in `withCtx(jrCtx, serverRef)(...)` so the
   handler reaches `Mcp.*`.
4. **Catalog + capability.** If the kind advertises a capability, partition it in `McpCatalog`
   and extend `autoDeriveServerCapabilities`; if it has a built-in list/dispatch route, add it
   to `McpBuiltInRoutes` and register it in `McpEngine.initServer`'s `builtinRoutes`.
5. **Tests.** Add a factory test to `McpHandlerTest` (asserting `kind` and `name`) and an
   end-to-end round-trip to `shared/src/test/scala/kyo/integration/` driving a real
   in-memory transport.

### Add a reverse-direction (client-answered) method

1. **Carrier factory.** Add an `McpClientHandler.onFoo[E](handler)` that constructs a
   `RequestCarrier` (for a reply-bearing method) or a `NotificationCarrier` (for a sink) with
   the baked-in wire method string, the `(In, Out)` schemas, and the required capability
   (`Present(McpCapabilities.Name.Foo)` or `Absent`).
2. **Default route.** Add a `buildFooRoute` to `McpReverseDispatch` that gates on the client
   capability first (`-32601` when absent) and otherwise rejects with a typed leaf, and append
   it to `buildRoutes`'s default list (before the lifted user routes, which override it).
3. **Server-side issue path.** Add the `McpServer.requestFoo` extension method and the matching
   `Unsafe.requestFoo` using the detach-then-reattach bridge, recovering a `-32601` wire error
   into `McpCapabilityNotAdvertisedException(peer = Client)`.
4. **Tests.** Add a capability-gating case to `McpReverseDispatchCapabilityGatingTest` and a
   round-trip to the integration suite.

## Test patterns

- **Base class.** Every test extends `Test`, the module base defined in
  `shared/src/test/scala/kyo/Test.scala` as `abstract class Test extends kyo.test.Test[Any]`.
  This module **does** ship that one-line `Test.scala` shim, and tests extend `Test`, not
  `kyo.test.Test[Any]` directly; keep new tests on the `Test` base. Never use ScalaTest
  directly.
- **File layout and naming.** Tests follow the 1:1 source-prefix rule
  (`McpHandler.scala` -> `McpHandlerTest.scala`, `McpException.scala` ->
  `McpExceptionTest.scala`); aspect splits keep the source prefix
  (`McpToolStructuredTest`, `McpToolOutcomeTest`). Unit tests for `internal.*` live under
  `shared/src/test/scala/kyo/internal/`; end-to-end round-trips over a live transport live
  under `shared/src/test/scala/kyo/integration/`.
- **Real transport, no mocks.** The default fixture is `JsonRpcTransport.inMemory`, which
  returns a connected `(server-side, client-side)` transport pair: a test stands up a real
  `McpServer` and a real `McpClient` over it and exercises the real handshake, dispatch,
  decode, and reverse-direction paths. Drive lifecycle through `Scope.run { McpServer.init(...) }`
  for the managed path, or `initUnscoped` + an explicit `closeNow` for the manual path.
- **Lifecycle hygiene is observed, not assumed.** `McpServerLifecycleTest` wraps the transport
  in a `ClosingTransport` that counts `close()` calls and asserts the count is **exactly 1**
  even when the scope body aborts, pinning the close-exactly-once invariant. When you touch the
  close paths, assert the count, do not eyeball it.
- **Compile-time contracts.** The direction/type carrier guarantees and the structured-output
  typing are asserted at compile time (`internal/CompileSpineTest`, the `_: McpServer = srv`
  assignments in `McpHandlerTest`); a wrong-direction registration or a mistyped accessor
  result is a compile failure, not a runtime check.
- **Totality is tested on the decode side.** `McpEnumSchemaTest`,
  `McpCompletionRefSchemaTest`, and `McpSamplingContentSchemaTest` feed an unknown wire string
  through the schema and assert a `Result.Failure` (never a thrown panic). When you add a
  closed enum or a discriminator, add the unknown-arm case.

## Decision checklist for a contributor

- [ ] New peer-supplied vocabulary: closed enum through `McpEnumSchema.closed` (no `Schema`
      in `derives`), or a hand-rolled singleton discriminator schema that throws
      `TypeMismatchException` on an unknown arm? An unknown wire value must be a typed
      `Result.Failure`, never a panic.
- [ ] New endpoint: server-side as an `McpHandler` carrier leaf + factory, reverse-direction
      as an `McpClientHandler` carrier? Direction and `(In, Out)` baked into the carrier so a
      mis-registration is a compile error.
- [ ] New handler factory: clause-interleaved (`[In]` annotated, `[Out]` inferred), `inline`
      only if it expands a schema macro at the call site, explicit return type?
- [ ] Content-bearing outcome: constructed through an empty-dropping smart constructor so blank
      text never reaches the wire? Optional record field uses the `.empty` sentinel?
- [ ] Reverse-direction or capability-gated path: default route gates on capability first with
      `-32601`, and the server-side issue path recovers a `-32601` into
      `McpCapabilityNotAdvertisedException`?
- [ ] Lifecycle: `init` is `Scope`-managed and releases via `closeDirect`; any new
      `initUnscoped` carries the close-obligation in its scaladoc; `closeDirect` runs in-place,
      `close`/`closeNow` spawn a detached fiber?
- [ ] New `Unsafe` method: detach-then-reattach bridge for reverse/notification methods, atomic
      pure read for accessors, each with its `// Unsafe:` rationale comment, and
      `AllowUnsafe.embrace.danger` scoped to the narrowest expression?
- [ ] New error: a leaf mixing the operation-trait(s) it can occur in, with the right reserved
      code, not a new open extension point? Author `.error` codes stay outside `-32003..-32000`?
- [ ] New client read method: a typed default lane that delegates to a raw escape-hatch lane,
      raising the missing-vs-decode distinction?
- [ ] Tests: extend `Test`, drive the real `JsonRpcTransport.inMemory` pair (no mocks), assert
      close-count for lifecycle changes, and add the unknown-arm decode case for new wire
      vocabulary?
