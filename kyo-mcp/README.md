<!-- doctest:setup
```scala
import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

case class AddIn(a: Int, b: Int) derives Schema, CanEqual
case class Sum(value: Int) derives Schema, CanEqual
case class Weather(city: String) derives Schema, CanEqual
```
-->

# kyo-mcp

Model Context Protocol implementation for building MCP servers and clients on top of `kyo-jsonrpc`. An MCP server registers typed tools, resources, and prompts; an MCP client calls them. Both sides share one engine: the `initialize` handshake, capability negotiation, and progress / cancellation plumbing are owned by the library so handler code stays focused on the actual tool, resource, or prompt logic.

Handlers are typed by user case classes (`Schema[In]` derives the wire decoder AND the `tools/list` `inputSchema` advertisement in one move), errors fan out into a sealed hierarchy keyed by pipeline stage, and the reverse direction (server-initiated `sampling/createMessage`, `roots/list`, `elicitation/create`) is reachable from any handler through the `Mcp.*` accessors that read the per-request context out of a `Local`. The same JSON-RPC transports that ship with `kyo-jsonrpc` (`stdio`, paired in-memory, custom byte-stream lifts) carry MCP traffic without ceremony.

```scala
val addTool: McpHandler[AddIn, McpContent, McpException] =
    McpHandler.tool[AddIn](
        name        = "add",
        description = "Adds two integers"
    ) { in =>
        McpContent.text(s"${in.a + in.b}")
    }
```

The single tool above is a full server handler. The next sections wire it into a live server, pair it with a client, walk through the other route kinds, and cover the surrounding pieces: errors, capabilities, lifecycle, reverse-direction calls, transports, and cross-platform behaviour.

## Building a server

`McpServer.init` takes a transport and a varargs list of handlers. The result is `McpServer < (Async & Scope)`: `Async` because background fibers drive the JSON-RPC dispatch, `Scope` because those fibers and the transport must be released when the enclosing scope exits.

The minimal end-to-end server: define one handler, start `JsonRpcTransport.stdio()`, hand both to `McpServer.initWith`, and keep the process alive.

```scala
val addTool: McpHandler[AddIn, McpContent, McpException] =
    McpHandler.tool[AddIn](
        name        = "add",
        description = "Adds two integers"
    ) { in =>
        McpContent.text(s"${in.a + in.b}")
    }

val program: Unit < (Async & Scope) =
    JsonRpcTransport.stdio().map(t => McpServer.initWith(t, addTool)(_ => Async.never))
```

The handler sees a typed `AddIn` value; the engine decoded it from the JSON-RPC `params` object using the auto-derived `Schema[AddIn]`. That same `Schema[AddIn]` also feeds the `tools/list` response: the engine derives a `Json.JsonSchema` from it at registration time and stamps it into the tool's `inputSchema` field. One `derives Schema` carries both the wire decode and the schema advertisement.

`McpServer.initWith(transport, handlers*)(f)` is the common shape: get the server, run `f(server)`, release at scope exit. `McpServer.init(transport, handlers*)` returns the bare `McpServer < (Async & Scope)` for callers that need to thread the server through more complex flow. Both have curried `(transport, config)(handlers*)` overloads when a non-default `McpConfig` is required, and unscoped `initUnscoped` / `initUnscopedWith` variants for handlers whose lifetime exceeds any single scope (the caller is responsible for `close`).

Handshake plumbing is invisible: the engine owns the `initialize` request and the `notifications/initialized` follow-up. User handlers never see those methods, and a `Schema`-less route name like `"initialize"` cannot collide with a registered tool name because the engine intercepts it first.

## Building a client

`McpClient.init` is the mirror of `McpServer.init` with an additional pair of mandatory arguments: the `clientInfo: McpInfo` and the `capabilities: McpCapabilities.Client` the client advertises during the handshake. The result is `McpClient < (Async & Scope & Abort[McpException | Closed])` because the initialise handshake runs eagerly inside `init` and surfaces handshake failures (protocol-version mismatch, transport closed mid-handshake) directly in the effect row.

```scala
val info = McpInfo(name = "calc-tester")
val caps = McpCapabilities.Client()

val program: McpHandler.ToolOutcome < (Async & Scope & Abort[McpException | Closed]) =
    for
        transport <- JsonRpcTransport.stdio()
        client    <- McpClient.init(transport, info, caps)
        result    <- client.callTool[AddIn]("add", AddIn(2, 3))
    yield result
```

`McpInfo(name = "calc-tester")` uses the Audit-B2 default for `version` (`"0.0.0"`); ship a real version string in production code. `McpInfo` also carries an optional `title: Maybe[String]` (§3.20) for a human-readable display name distinct from the wire-stable `name`. `McpCapabilities.Client()` advertises no client capabilities (the zero-arg call works because all four fields default to `Absent` or `Map.empty`).

`client.callTool[AddIn]` is the untyped overload: the server's `ToolOutcome` is returned verbatim (`content: Chunk[McpContent]`, `isError: Boolean`, `structuredContent: Maybe[Structure.Value]`). When the tool produces a typed result and you want the engine to decode it for you, use the typed overload:

```scala
val typed: Sum < (Async & Scope & Abort[McpException | Closed]) =
    JsonRpcTransport.stdio().map { transport =>
        val info = McpInfo(name = "calc-tester")
        val caps = McpCapabilities.Client()
        McpClient.initWith(transport, info, caps) { client =>
            client.callToolTyped[AddIn, Sum]("add", AddIn(2, 3))
        }
    }
```

The typed overload aborts with `McpToolStructuredMissingException` when the server returns `ToolOutcome.structuredContent = Absent`; reach for the untyped variant when the tool emits unstructured content. `callTool[In]` and `callToolTyped[In, Out]` are distinct names so Scala 3 extension-method resolution is unambiguous: the compiler can tell them apart without inspecting the type-argument list.

The remaining client surface is a small set of typed extension methods, each named after the underlying MCP request: `listTools`, `listResources`, `listResourceTemplates`, `readResource`, `listPrompts`, `getPrompt`, `complete`, `setLogLevel`, `subscribeResource`, `unsubscribeResource`, `ping`, `notifyRootsListChanged`. Each is fully callable at the safe tier and returns a typed result. (`subscribeResource(uri)` / `unsubscribeResource(uri)` ride the spec's resource-subscription protocol and only succeed when the server has at least one route declared with `subscribe = true`; `ping` is a `Unit`-returning handshake-liveness check.)

`setLogLevel(level: McpServer.LogLevel)` takes the typed log level (eight cases Debug through Emergency); the server-side `notifyLog(level, data, logger)` matches.

The list-shaped methods return `McpClient.Page[A]` which also provides factories `McpClient.Page.empty[A]` and `McpClient.Page.of(items, next)` plus a `.isLast` predicate for cursor-based iteration.

```scala
val tools: McpClient.Page[McpHandler.ToolMeta] < (Async & Scope & Abort[McpException | Closed]) =
    val info = McpInfo(name = "calc-tester")
    val caps = McpCapabilities.Client()
    JsonRpcTransport.inMemory.map { (_, clientT) =>
        McpClient.initWith(clientT, info, caps)(_.listTools())
    }
```

Each cursor-paginated list returns `McpClient.Page[A](items, nextCursor, meta)` so the page boundary is named, not the tuple-positional `(Chunk[A], Maybe[String])` shape (Audit-A3 / INV-023). The `meta: Maybe[Structure.Value]` field is the spec §3.7 advisory carve-out; the same `_meta` passthrough also rides on `ToolOutcome` and `PromptOutcome` and is the only place the typed API hands you the raw JSON shape.

## Handlers

`McpHandler[In, Out, +E]` is the sealed user-facing carrier for every MCP endpoint: it pairs the descriptive metadata (name, schemas, capability hints, annotations) with the implementation closure the engine invokes on inbound requests. Construct values via the role-tagged factories on the companion; each factory takes the handler closure directly so there is no separate route descriptor step.

Five factories cover the standard MCP surface: `tool`, `resource`, `resourceTemplate`, `prompt`, `completion`. `toolMulti` is a multi-content variant of `tool`; `completionWith` is a 2-arg variant of `completion` that also threads the previously-filled-arguments context; `custom` lets you bolt a raw typed JSON-RPC method into the same engine for protocol extensions. The factories interleave their type clauses so the call site annotates only `[In]` and the compiler infers `[Out]` from the handler's return type.

The handler closure receives `In` and returns `Out < (Async & Abort[McpException | JsonRpcResponse.Halt])`. The per-request context is reachable through the `Mcp.*` accessors (`Mcp.server`, `Mcp.progress`, `Mcp.requestId`, `Mcp.cancelled`, `Mcp.extras`) rather than an explicit parameter; see the `per-request context` subsection further down.

### Tool handlers

**In MCP, a tool is a server-exposed function the LLM client can decide to call.** The model receives the list of tool descriptions during the handshake and may decide, mid-conversation, that it needs one. The client then sends `tools/call` with the tool name and a JSON arguments object; the server runs the tool and returns the result as content (text, image, audio, embedded resource) that flows back into the model's context. Tools are the main extension point for letting an LLM act on the outside world: search the web, query a database, hit a private API, run a calculator, render a chart.

`McpHandler.tool[In]` is the single-content tool factory: the handler returns a value `<: McpContent` and the engine wraps it in a `ToolOutcome` with `content = Chunk(out)`. The `Out` type parameter is inferred from the handler's return type via clause interleaving, so the call site only needs `[In]`. `McpHandler.toolMulti[In]` is the multi-content sibling: the handler returns a full `ToolOutcome` and is free to emit multiple content leaves and a `structuredContent` payload.

```scala
val weatherTool: McpHandler[Weather, McpContent, McpException] =
    McpHandler.tool[Weather](
        name        = "weather",
        description = "Looks up weather for a city",
        annotations = McpHandler.ToolAnnotations(
            title           = Present("Weather Lookup"),
            readOnlyHint    = Present(true),
            destructiveHint = Absent,
            idempotentHint  = Present(true),
            openWorldHint   = Present(true)
        )
    ) { req =>
        McpContent.text(s"Sunny in ${req.city}")
    }
```

The `ToolAnnotations` record captures the spec's display and behavioural hints; every field is optional. Pick `tool` when one content leaf suffices; reach for `toolMulti` when the tool emits a mixed bag (text plus an image, say) or wants to populate the typed `structuredContent` slot that `callToolTyped[In, Out]` decodes against.

`ToolAnnotations.noop` is the empty-record default; the factory translates equality with `.noop` into wire-`Absent` so the field is omitted from the JSON envelope. `ResourceAnnotations.noop` follows the same noop-omit pattern.

### Resource handlers

**Resources are server-exposed read-only data the client can fetch by URI.** Where tools execute behaviour on demand, resources expose state the client wants to read into the model's context: a project file, a database table snapshot, a configuration document, a piece of conversation history. The client discovers what is available via `resources/list` (and `resources/templates/list` for the templated kind) and fetches the bytes via `resources/read` with the URI in `params`. The server replies with `Chunk[ResourceContents]` — one or more `Text(uri, mimeType, text)` or `Blob(uri, mimeType, base64)` entries — which the client then forwards to the model.

`McpHandler.resource(uri, name, ...)` registers a fixed-URI resource; `McpHandler.resourceTemplate(uriTemplate, name, ...)` registers an RFC 6570 URI-template resource matching every URI that fits the pattern (e.g. `file:///{path}`, where one handler serves many paths). Both handlers return `Chunk[McpHandler.ResourceContents]`; both URI inputs are typed opaque values (`McpResourceUri` for full URIs, `McpResourceUri.Template` for templates), never raw `String` (INV-022).

```scala
val readmeUri = McpResourceUri("file:///README.md")
val readme: McpHandler[McpResourceUri, Chunk[McpHandler.ResourceContents], McpException] =
    McpHandler.resource(
        uri         = readmeUri,
        name        = "readme",
        description = "Project README",
        mimeType    = Present(McpMimeType("text/markdown"))
    ) {
        Chunk(McpHandler.ResourceContents.text(readmeUri, "Hello, world!", Present(McpMimeType("text/markdown"))))
    }
```

The handler is a by-name effectful value; it takes no parameter because the URI is fixed at registration. Reference the captured `readmeUri` val when the body needs the URI string in the produced `ResourceContents`. For URI-template resources where the URI is dynamic per request, the closure does take the matched URI:

```scala
val tmpl = McpResourceUri.Template("file:///{path}")
val fileTemplate: McpHandler[McpResourceUri, Chunk[McpHandler.ResourceContents], McpException] =
    McpHandler.resourceTemplate(uriTemplate = tmpl, name = "file") { uri =>
        val bindings: Maybe[Map[String, String]] = tmpl.extract(uri)
        val path = bindings.flatMap(b => Maybe.fromOption(b.get("path"))).getOrElse("")
        Chunk(McpHandler.ResourceContents.text(uri, s"contents for $path", Absent))
    }
```

`McpResourceUri.Template.extract(uri)` resolves the inbound URI against the template's `{name}` placeholders and returns `Present(Map(name -> value))` on a match, `Absent` otherwise. RFC 6570 Level 1 (`{var}`) is supported; captured values include reserved characters like `/`, so `{path}` against `file:///foo/bar.txt` binds `path -> "foo/bar.txt"`.

`McpHandler.ResourceContents.text(uri, text, mimeType)` and `McpHandler.ResourceContents.blob(uri, blob, mimeType)` are the two factories for resource read results; pick `text` for UTF-8 payloads and `blob` for base64-encoded binary.

`McpContent` has five cases (`Text`, `Image`, `Audio`, `EmbeddedResource`, `ResourceLink`); `ResourceLink(uri, name, ...)` is the typed link variant for search-style tools that point into the resource catalogue rather than embedding payload inline.

`McpResourceUri.parse(s)` is the validated user-facing constructor: it returns `Absent` when `s` is empty or whitespace. `McpResourceUri.apply(s)` is the trusted call-site constructor used at the library boundary and inside this README's doctest blocks. Use `parse` for any URI that comes from outside your code; use `apply` only when you have already validated the string.

The same parse-vs-apply pattern (and a final `.asString` accessor for wire conversion) applies to `McpResourceUri.Template`, `McpMimeType`, and `McpConfig.ProtocolVersion`; all four opaque newtypes share the same shape.

`McpHandler.resource(..., subscribe = false)` is the default; passing `subscribe = true` on a per-resource basis is what causes the server to advertise `resources.subscribe = true` and accept `subscribeResource` calls from the client. A client `subscribeResource` against a server with no opted-in route is rejected by the capability gate.

### Prompt handlers

**A prompt in MCP is a server-supplied, parameterised prompt template the client can fetch and feed to the LLM.** The server publishes a named prompt with declared arguments (`prompts/list`); the client fetches it with `prompts/get` and a `Map[String, String]` of argument values; the server renders the prompt into one or more conversation turns (a `Chunk[PromptMessage]` with user/assistant `Role`s) which the client then passes to the model as conversation context. Use prompts to ship a curated way to ask the LLM something rather than expose raw text: a "code review" prompt that bundles instructions, style guides, and the user-supplied code into a coherent message sequence is a typical case.

`McpHandler.prompt(name, description, arguments)` registers a prompt the client can fetch by name. The handler receives `Map[String, String]` (the arguments the client sent) and returns `PromptOutcome` (the rendered messages).

```scala
val explainPrompt: McpHandler[Map[String, String], McpHandler.PromptOutcome, McpException] =
    McpHandler.prompt(
        name        = "explain",
        description = "Explain a topic",
        arguments   = Chunk(McpHandler.PromptArgument(
            name        = "topic",
            description = Present("topic to explain"),
            required    = true
        ))
    ) { args =>
        val topic = args.getOrElse("topic", "")
        McpHandler.PromptOutcome(
            description = Present(s"Explain $topic"),
            messages = Chunk(McpHandler.PromptMessage(
                role    = McpContent.Role.User,
                content = McpContent.text(s"Please explain $topic.")
            ))
        )
    }
```

The declared `arguments` populate the `prompts/list` advertisement. The runtime map the handler receives is not validated against the declared list; enforce required arguments inside the handler when that matters.

### Completion handlers

**Completion in MCP is IDE-style argument autocompletion — not LLM text generation.** When a user (in an IDE, agent harness, or chat client) is filling in the arguments of a prompt the server published, or filling in the variables of a resource-template URI, the client can call `completion/complete` with what has been typed so far and ask the server for ranked suggestions. The server returns a short list of strings the UI can render as a dropdown. The model is not involved; this is the same shape as bash tab-complete or an IDE autocompletion popup, just carried over JSON-RPC.

Two pieces of context name the cursor:
- `CompletionRef` says *what is being completed*: `Prompt(name)` if the user is filling the arguments of a server-published prompt, `Resource(uri)` if the user is filling the variables of a resource template's URI.
- `CompletionArg(name, value)` says *which argument is in focus* and *what has been typed so far*: `name` is the argument name (e.g. `"topic"`), `value` is the partial string the user has entered (e.g. `"k"`).

The handler matches `arg.value` against its candidate strings and returns a `CompletionOutcome(values, total, hasMore)`. `values` is the suggestion list; `total` and `hasMore` are optional pagination hints when the server has more candidates than it is returning in one shot.

`McpHandler.completion(ref)` registers a completion provider; the closure receives the focused `CompletionArg` and produces the outcome. Reach for `McpHandler.completionWith(ref)` when the handler also needs the optional `Context` carrying values the user has already filled for *other* arguments of the same prompt (so a topic-completion can depend on, say, a previously-chosen language).

```scala
// Argument autocompletion for the `topic` argument of the "explain" prompt.
// When the user types "k" the server suggests "kyo".
val topicCompletion: McpHandler[(McpHandler.CompletionRef, McpHandler.CompletionArg), McpHandler.CompletionOutcome, McpException] =
    McpHandler.completion(McpHandler.CompletionRef.Prompt("explain")) { arg =>
        McpHandler.CompletionOutcome(
            values  = Chunk("kyo", "scala", "mcp").filter(_.startsWith(arg.value)),
            total   = Absent,
            hasMore = Absent
        )
    }
```

`CompletionRef.Prompt("explain")` ties this completion to the `"explain"` prompt registered earlier (the example renders argument suggestions for its `"topic"` argument). The handler inspects `arg.value` (the partial string), filters its candidate list by prefix, and returns the survivors.

`McpClient.complete(ref, arg)` currently forwards `Absent` for context, so handlers that inspect `contextOpt` via `completionWith` will receive `Absent` when called through the built-in client; that hook exists for handlers under future client integrations that thread the partially-filled argument map through per spec §3.17.

`McpHandler[In, Out, +E].error[E2](code, message)` adds an entry to the handler's typed error channel; the handler then aborts with values of type `E2` and the engine maps them to the spec'd JSON-RPC error code on the wire.

### Custom handlers

**Custom handlers expose arbitrary JSON-RPC methods that aren't part of the standard MCP surface.** MCP servers and clients are free to define extension methods (vendor namespaces like `acme/serverStatus`, draft spec additions, internal RPCs between trusted peers); the engine has no special knowledge of them but will dispatch by name like any built-in method.

`McpHandler.custom[In](method)(handler)` is the factory. The shape mirrors the built-in ones; the only difference is that the engine treats the handler as `Kind.Custom` — it does not contribute to capability auto-derivation and does not appear in the standard `*/list` advertisements, since clients can only discover custom methods through out-of-band agreement.

### The per-request context

Handler closures take only the typed `In` parameter; per-request fields are reached through the `Mcp.*` accessors, each of which reads from a per-request `Local` the engine binds at dispatch time:

- `Mcp.cancelled`: yields the `Fiber.Promise[Unit, Sync]` that completes when the peer cancels this request. Race it against the handler's work.
- `Mcp.requestId`: the JSON-RPC id of the inbound request (`Absent` for notifications).
- `Mcp.extras`: protocol-specific extra fields from the inbound envelope.
- `Mcp.server`: the live `McpServer` handle for reverse-direction calls (`requestSampling`, `requestRoots`, `requestElicitation`) and notifications. INV-024: typed `McpServer`, never `McpServer.Unsafe`.
- `Mcp.progress(progress, total, message)`: reports an MCP-shaped progress notification keyed on the `_meta.progressToken` the client supplied. A no-op when the client did not supply a token, and silently dropped per `McpProgressPolicy` when no `progressToken` was extracted by the engine.

`Mcp.server.flatMap(_.requestSampling(req))` lets a tool handler ask the client to run an LLM completion mid-handler; `Mcp.server.flatMap(_.requestElicitation(req))` lets the handler collect additional user input. Both use the typed request and response records (`McpServer.SamplingRequest` / `McpServer.SamplingResponse`, `McpServer.ElicitationRequest` / `McpServer.ElicitationResponse`); the reverse-direction wire shape is owned by the library.

Calling any `Mcp.*` accessor outside an active route handler raises an `IllegalStateException` (kernel panic). This is a programmer-error path, not a domain failure, so the accessors do not widen handler effect rows with a typed `Abort`.

### Why one trait, not three concrete types

The engine wiring is identical for every route kind: each one ultimately becomes a `JsonRpcRoute` registered on the underlying `JsonRpcHandler`. Carrying a single sealed `McpHandler` lets `McpServer.init(transport, handlers*)` accept a heterogeneous varargs list (one tool, two resources, three prompts) without an artificial LUB or a wrapper type. The role-tagged factories carry the kind discriminator on the value, not the type.

## Errors

`McpException` is the root error type, organised by the pipeline stage where each failure arises. Pattern-match on the four sealed subcategory traits to discriminate:

- `McpHandshakeException`: errors surfaced during the `initialize` handshake (premature methods, version mismatch).
- `McpDispatchException`: errors surfaced during method routing (unknown tool / resource / prompt, unadvertised capability, invalid argument).
- `McpExecutionException`: errors surfaced during handler execution or structured-payload validation (typed `callTool` missing structured content, rejected sampling, declined elicitation).
- `McpApplicationException`: user-domain errors from handler bodies.

```scala
def stage(e: McpException): String = e match
    case _: McpHandshakeException   => "handshake"
    case _: McpDispatchException    => "dispatch"
    case _: McpExecutionException   => "execution"
    case _: McpApplicationException => "application"
```

`McpException` extends `JsonRpcApplicationError`, so every `McpException` is also a valid `JsonRpcError` and travels through `Abort[JsonRpcError | ...]` rows transparently. The inherited `Schema[JsonRpcError]` encodes any `McpException` as the wire triple `(code, message, data)`; no separate `Schema[McpException]` is required.

### Application errors

The hierarchy is sealed end-to-end. Register typed user-domain errors per handler via `.error[E2](code, message)` on the `McpHandler` value rather than by extending `McpApplicationException` directly. The mapping installs a wire-code/message pair that the engine emits whenever the handler aborts with an `E2` value, matching the `kyo-jsonrpc` route convention.

Three application-error leaves ship with the library: `McpToolExecutionException(tool, reason, cause)`, `McpResourceReadException(uri, reason, cause)`, and `McpPromptRenderException(name, reason, cause)`. Each carries a typed `cause: Throwable | Text` and aborts directly inside tool, resource, and prompt handlers without needing a per-route `.error` registration.

When the receiving peer reads a wire error response, it arrives as `McpRemoteApplicationException(remoteCode, remoteMessage, remoteData)` ; the wire triple is preserved verbatim from the sender. The caller pattern-matches on `remoteCode` to discriminate across user-defined error families. The handshake-failure leaves and dispatch-failure leaves (`McpUnknownToolException` and the rest) remain distinct because they are framework-generated, not user-mapped.

### Catching Java throwables

`Sync.defer { javaCallThatThrows() }` is the kyo idiom for wrapping a synchronous side effect, but kyo does not auto-convert escaping `Throwable`s into typed `Abort` rows. An uncaught `IOException` from `Files.readString` inside `Sync.defer` surfaces as a panic at the engine dispatch boundary and the client sees `-32603 InternalError`. Convert to a typed `Abort` row at the boundary:

```scala
case class ReadFile(path: String)              derives Schema, CanEqual
case class FsError(reason: String, path: String) derives Schema, CanEqual

val readTool: McpHandler[ReadFile, McpContent, McpException | FsError] =
    McpHandler.tool[ReadFile](name = "read", description = "Read a file") { req =>
        Abort.catching[java.io.IOException](
            Sync.defer(java.nio.file.Files.readString(java.nio.file.Paths.get(req.path)))
        )
            .map(s => McpContent.text(s))
            .handle(Abort.recover[java.io.IOException] { ex =>
                Abort.fail(FsError(reason = ex.getMessage, path = req.path))
            })
    }
    .error[FsError](code = -32001, message = "filesystem-error")
```

The `Abort.catching[java.io.IOException]` narrows the throw to a typed effect-row entry; `Abort.recover` converts it into the registered `FsError`. Once `.error[FsError]` is wired, the wire response carries code `-32001` and the schema-encoded `FsError` in the `data` slot, and the client receives `McpRemoteApplicationException(-32001, "filesystem-error", Present(...))`.

### Why the stage trait, not a flat enum

Pipeline stage is the only axis along which an error-handling caller actually wants to fan out. A flat enum forces every caller to enumerate every leaf even when the only distinction that matters is "the handshake never completed" vs "the handler aborted". The sealed traits keep pattern-matching by stage one line long while the leaf case classes preserve the precise diagnostic for logging.

## The capability gate

MCP peers advertise their feature set during the handshake. `McpCapabilities.Server` is what the server sends in the `initialize` response; `McpCapabilities.Client` is what the client sends in the `initialize` request. Each is a typed record with optional fields per capability domain (`tools`, `resources`, `prompts`, `logging`, `completions` on the server side; `sampling`, `roots`, `elicitation` on the client side).

By default the server derives its advertised capabilities from the registered handlers: a server with `McpHandler.tool` handlers advertises `ToolsCapability`, one with `McpHandler.resource` advertises `ResourcesCapability`, and so on. Override the derivation by setting `McpConfig.declaredCapabilities(...)`:

```scala
val explicitCaps: McpConfig =
    McpConfig.default.declaredCapabilities(
        McpCapabilities.Server(
            tools     = Present(McpCapabilities.ToolsCapability(listChanged = true)),
            resources = Present(McpCapabilities.ResourcesCapability(subscribe = true, listChanged = true))
        )
    )
```

Once the handshake completes, `server.clientCapabilities` and `client.serverCapabilities` expose the negotiated record as `Maybe[McpCapabilities.{Client, Server}]` (`Absent` before the handshake finishes). The handler-time `Context` does not expose capabilities directly because handlers that depend on an opt-in capability are dispatch-gated by `McpConfig.capabilityGate` ; if a client calls a method whose required capability the server did not advertise (or vice versa), the engine fails with `McpCapabilityNotAdvertisedException` before the handler runs.

The `capabilityName: McpCapabilities.Name` field on `McpCapabilityNotAdvertisedException` (and the enum's eight cases: Tools, Resources, Prompts, Sampling, Roots, Logging, Completions, Elicitation) lets handler code pattern-match on which capability the peer required.

## Lifecycle

Three pairs of methods control a server's or client's life:

- `init` / `initUnscoped`: bring up a peer and run the handshake. Scoped variants release at scope exit; unscoped variants require manual close.
- `close` / `close(grace)` / `closeNow`: tear it down. The no-arg `close(using Frame)` defaults to a 30-second grace period (Audit-B1 / INV-028); `closeNow` is the explicit immediate variant.
- `awaitDrain` (server only): blocks until in-flight requests have drained, without closing.

```scala
val gracefulShutdown: Unit < (Async & Scope & Abort[McpException | Closed]) =
    val info = McpInfo(name = "calc-tester")
    val caps = McpCapabilities.Client()
    JsonRpcTransport.stdio().map { transport =>
        McpClient.initWith(transport, info, caps) { client =>
            client.close(5.seconds)
        }
    }
```

The 30-second default matches the `HttpServer.close` precedent and avoids the previous `Duration.Zero` footgun where in-flight requests would fail with `Closed` the instant the caller called `close`. Use `closeNow` when you have already decided the connection is dead and want immediate teardown; use `close(grace)` when you want a bounded drain window.

### Scoped vs unscoped

The scoped `init` is the default and what almost all callers want: the server / client closes when the enclosing `Scope` exits, the dispatch fiber is interrupted, and the transport's `close` runs. The unscoped variant exists for handlers whose lifetime exceeds any single scope ; a server that runs for the entire process, say. An unscoped peer that never has `close` called leaks the dispatch fiber and never releases the transport.

## Reverse-direction calls

A live MCP server can also originate requests to the client. The three spec-defined reverse-direction methods are surfaced as typed extension methods on `McpServer`, each guarded by the client's advertised capabilities:

- `requestSampling(req)`: ask the client to run an LLM completion. Requires the client's `sampling` capability.
- `requestRoots`: list the workspace roots the client is willing to expose. Requires the client's `roots` capability.
- `requestElicitation(req)`: collect additional input from the user. Requires the client's `elicitation` capability.

```scala
val askLLM: McpHandler[Weather, McpContent, McpException] =
    McpHandler.tool[Weather]("askLLM") { req =>
        val sampling = McpServer.SamplingRequest(
            messages         = Chunk(McpServer.SamplingRequest.Message(
                role    = McpContent.Role.User,
                content = McpServer.SamplingContent.Text(s"What is the weather like in ${req.city}?")
            )),
            modelPreferences = Present(McpServer.SamplingRequest.ModelPreferences(
                hints           = Chunk(McpServer.SamplingRequest.ModelHint(name = Present("claude-3-5-sonnet"))),
                intelligencePriority = Present(0.8)
            )),
            maxTokens        = 256
        )
        Mcp.server.flatMap(srv => Abort.run[Closed](srv.requestSampling(sampling))).map {
            case Result.Success(resp) => resp.content match
                case t: McpContent.Text => McpContent.text(t.text)
                case _                  => McpContent.text("(non-text response)")
            case _: Result.Failure[?] => McpContent.text("(sampling unavailable)")
            case _: Result.Panic      => McpContent.text("(sampling panic)")
        }
    }
```

`SamplingRequest.includeContext` accepts the enum `IncludeContext.{None, ThisServer, AllServers}`; `ElicitationResponse.action` carries `ElicitationResponse.Action.{Accept, Decline, Cancel}` so handler code can pattern-match on the user's choice.

`SamplingRequest.Message.content` is typed `SamplingContent`, a sealed subset of `McpContent` covering only `Text`, `Image`, and `Audio`. The wider `EmbeddedResource` and `ResourceLink` cases are disallowed in sampling requests; call `samplingContent.toMcpContent` to lift back to the broader type when forwarding the response into a normal tool result.

`SamplingResponse.stopReason` is `Maybe[SamplingResponse.StopReason]` with cases `EndTurn`, `StopSequence`, `MaxTokens`. The schema decodes any unrecognised wire string to `EndTurn`, so callers that need to distinguish "server said endTurn" from "server said something we don't recognise" must inspect the underlying envelope.

The reverse-direction surface is fully typed end-to-end: `McpServer.SamplingRequest`, `McpServer.SamplingResponse`, `McpServer.ElicitationRequest`, `McpServer.ElicitationResponse`, `McpServer.Root`. The only `Structure.Value` slots are the spec-open `metadata` / `content` fields whose shape is genuinely open (INV-021 allowlist).

Notifications (`server.notifyToolsListChanged`, `server.notifyResourcesListChanged`, `server.notifyResourceUpdated(uri)`, `server.notifyPromptsListChanged`, `server.notifyLog(level, data, logger)`) are the fire-and-forget mirrors. Each is gated by the matching capability advertisement.

## Transports

`kyo-mcp` does not ship its own transports; it reuses every `JsonRpcTransport` factory from `kyo-jsonrpc`. The deployment shapes mirror that module exactly:

- `JsonRpcTransport.stdio()`: line-delimited stdio for CLI-style MCP servers spawned by a host process. The standard MCP deployment.
- `JsonRpcTransport.inMemory`: a pair of cross-wired transports for tests. `(a, b)` pair where `a.send` arrives on `b.incoming`.
- `JsonRpcTransport.fromWire(wire, framer, codec)`: lift a custom `JsonRpcWireTransport` (a TCP socket, a named pipe, a test double).
- `JsonRpcTransport.unixDomain(path)`: Unix domain socket; JVM-only.

The minimal server above uses `stdio()` for exactly the standard MCP deployment shape. For tests, pair a server and a client over `inMemory`:

```scala
val pairedTest: McpClient.Page[McpHandler.ToolMeta] < (Async & Scope & Abort[McpException | Closed]) =
    val addTool = McpHandler.tool[AddIn]("add") { in =>
        McpContent.text(s"${in.a + in.b}")
    }
    JsonRpcTransport.inMemory.map { (serverT, clientT) =>
        McpServer.initWith(serverT, addTool) { _ =>
            val info = McpInfo(name = "test-client")
            val caps = McpCapabilities.Client()
            McpClient.initWith(clientT, info, caps)(_.listTools())
        }
    }
```

For framers and codecs (line-delimited vs `Content-Length`, strict vs lenient JSON-RPC 2.0), see the `kyo-jsonrpc` README. The MCP engine sets `JsonRpcCodec.Strict2_0` by default through `McpConfig.defaultJsonRpcConfig`; override via `McpConfig.jsonRpc(...)` when a peer requires the lenient codec.

## Configuration

`McpConfig` collects the per-peer behaviour knobs:

```scala
val tuned: McpConfig =
    McpConfig.default
        .handshakeTimeout(10.seconds)
        .capabilityGate(McpConfig.CapabilityGateMode.LogOnly)
        .autoNotifyListChanged(false)
```

The fluent setters (`serverInfo`, `instructions`, `supportedProtocolVersions`, `declaredCapabilities`, `handshakeTimeout`, `handshakeOrder`, `capabilityGate`, `autoNotifyListChanged`, `jsonRpc`) each return a new `McpConfig` with the named field overridden. Pass the result to `McpServer.init(transport, config)(handlers*)` or `McpClient.init(transport, info, caps, config)(handlers*)`.

`McpConfig.require(c)` validates the config (non-empty `supportedProtocolVersions`, positive `handshakeTimeout`, and delegates to `JsonRpcHandler.Config.require` for the embedded JSON-RPC slot). Every `init` variant calls it; an invalid config throws `IllegalArgumentException` at init time, not lazily.

`supportedProtocolVersions: Chunk[McpConfig.ProtocolVersion]` accepts the typed protocol-version newtype; use `McpConfig.ProtocolVersion.parse(wireString)` to construct from external input and `.asString` to recover the wire form.

### Handshake order and capability gate

`HandshakeOrder.RequireInitializedNotification` (default) waits for the client's `notifications/initialized` notification before dispatching regular requests; `RequireInitializeRequestOnly` accepts requests as soon as the `initialize` request itself completes. Pick the latter only for clients that do not emit the follow-up notification.

`CapabilityGateMode.RejectUnsupported` (default) fails with `McpCapabilityNotAdvertisedException` when a method requires an unadvertised capability; `LogOnly` records the gap and still dispatches; `Off` disables the gate entirely. Use `LogOnly` while developing against a non-compliant peer; reach for `RejectUnsupported` in production.

### JSON-RPC defaults

`McpConfig.defaultJsonRpcConfig` is the embedded `JsonRpcHandler.Config` pre-populated with MCP-specific policy adapters: `McpCancellationPolicy.default` (the MCP `notifications/cancelled` shape), `McpProgressPolicy.default` (the MCP `notifications/progress` shape with `_meta.progressToken` extraction), `McpUnknownMethodPolicy.default` (the strict preset per Q-016), and `JsonRpcCodec.Strict2_0`. Override individual slots via `.jsonRpc(...)` ; for example, to allow long-running tools with no per-request timeout, the default already sets `Duration.Infinity`.

## Cross-platform behaviour

The shared API compiles and runs on JVM, JavaScript, and Scala Native. The cross-platform exceptions are inherited from the transport layer:

| Surface | JVM | JS | Native |
|---------|-----|----|----|
| `McpServer`, `McpClient`, all handlers | yes | yes | yes |
| `McpHandler` factories, `McpContent`, `McpHandler.ResourceContents` | yes | yes | yes |
| `McpException` hierarchy and `Schema` derivations | yes | yes | yes |
| `JsonRpcTransport.stdio` | yes | yes | yes |
| `JsonRpcTransport.inMemory` | yes | yes | yes |
| `JsonRpcTransport.unixDomain` | yes | aborts | aborts |

The full MCP surface is cross-platform. The only per-platform restriction comes from `JsonRpcTransport.unixDomain`, which aborts with `UnsupportedOperationException` on JS and Native because the NIO Unix-domain APIs are JVM-only. Cross-platform MCP code uses `stdio` or a custom `fromWire` transport instead.
