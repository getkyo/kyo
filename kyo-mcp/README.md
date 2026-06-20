<!-- doctest:setup
```scala
import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

case class AddIn(a: Int, b: Int) derives Schema, CanEqual
case class Sum(value: Int) derives Schema, CanEqual
case class Weather(city: String) derives Schema, CanEqual
case class AddOverflow(a: Int, b: Int) derives Schema, CanEqual
```
-->

# kyo-mcp

`kyo-mcp` is the MCP module: a server you expose to LLM hosts (Claude Desktop, IDE plugins, anything that speaks MCP), and a client you use to talk to other MCP servers. Both run on JVM, JavaScript, and Scala Native, share the same handler vocabulary, and ride on top of `kyo-jsonrpc` over any `JsonRpcTransport` (stdio, unix domain socket, web socket, in-memory pipe).

A server is built from `McpHandler` values: one per tool, resource, resource template, prompt, or completion endpoint. You hand a varargs list of handlers to `McpServer.init`; the engine runs the protocol for you: handshake, capability advertisement, and dispatch. Inside a handler body the live peer is reachable through the `Mcp.*` accessors (`Mcp.server` for reverse-direction calls back to the client, `Mcp.progress` for monotonic progress notifications, `Mcp.cancelled` for the cancellation promise). The same `McpHandler` type also drives the client side for reverse-direction methods (`sampling/createMessage`, `roots/list`, `elicitation/create`).

```scala
val addTool =
    McpHandler.tool[AddIn](
        name = "add",
        description = "Adds two integers"
    ) { in =>
        McpContent.text(s"${in.a + in.b}")
    }
```

## Building a server

To stand up an MCP server you hand a transport and a varargs list of handlers to `McpServer.init`; the engine takes care of dispatch, handshake, and capability advertisement. The result is `McpServer < (Async & Scope)`: `Async` because background fibers drive the JSON-RPC dispatch, `Scope` because those fibers and the transport must be released when the enclosing scope exits.

The minimal end-to-end server defines one handler, starts `JsonRpcTransport.stdio()`, hands both to `McpServer.initWith`, and keeps the process alive:

```scala
val addTool =
    McpHandler.tool[AddIn](
        name = "add",
        description = "Adds two integers"
    ) { in =>
        McpContent.text(s"${in.a + in.b}")
    }

val program: Unit < (Async & Scope) =
    JsonRpcTransport.stdio().map(t => McpServer.initWith(t, addTool)(_ => Async.never))
```

The handler sees a typed `AddIn` value; the engine decoded it from the JSON-RPC `params` object using the auto-derived `Schema[AddIn]`.

`McpServer.initWith(transport, handlers*)(f)` is the common shape: get the server, run `f(server)`, release at scope exit. `McpServer.init(transport, handlers*)` returns the bare `McpServer < (Async & Scope)` for callers that need to thread the server through more complex flow. Both have curried `(transport, config)(handlers*)` overloads when a non-default `McpConfig` is required, and unscoped `initUnscoped` / `initUnscopedWith` variants for handlers whose lifetime exceeds any single scope (the caller is responsible for `close`).

### Running a server as a process

The `val program: Unit < (Async & Scope)` above is an effect description, not a running process. Wrap it in a `KyoApp` to produce a `main`-method-bearing object that the JVM can launch:

```scala
import kyo.*

object AddServer extends KyoApp:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual

    run {
        val addTool =
            McpHandler.tool[AddIn](name = "add", description = "Adds two integers") { in =>
                McpContent.text(s"${in.a + in.b}")
            }
        JsonRpcTransport.stdio().map { transport =>
            McpServer.initWith(transport, addTool) { _ =>
                Async.never
            }
        }
    }

end AddServer
```

`KyoApp.run { ... }` accepts any `Any < (Async & Scope & ...)`, runs the effect, and releases the scope when the body completes. `Async.never` parks the body forever; the inbound stdio dispatch fiber drives all work, so the main fiber has nothing to do besides hold the scope open until the JVM is killed (Ctrl-C, parent process exit, etc.). For a server that should exit after some condition, replace `Async.never` with a `Fiber.Promise[Unit, Sync]` that the handler closures complete when ready.

Handshake plumbing is invisible: the engine owns the `initialize` request and the `notifications/initialized` follow-up. User handlers never see those methods.

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

The typed overload aborts with `McpToolStructuredMissingException` when the server returns `ToolOutcome.structuredContent = Absent`; reach for the untyped variant when the tool emits unstructured content.

The remaining client surface is a small set of typed extension methods, each named after the underlying MCP request: `listTools`, `listResources`, `listResourceTemplates`, `readResource`, `listPrompts`, `getPrompt`, `complete`, `setLogLevel`, `subscribeResource`, `unsubscribeResource`, `ping`, `notifyRootsListChanged`. Each is fully callable at the safe tier and returns a typed result. `subscribeResource(uri)` / `unsubscribeResource(uri)` ride the spec's resource-subscription protocol and only succeed when the server has at least one route declared with `subscribe = true`; `ping` is a `Unit`-returning handshake-liveness check.

`setLogLevel(level: McpServer.LogLevel)` takes the typed log level (eight cases Debug through Emergency); the server-side `notifyLog(level, data, logger)` matches.

The list-shaped methods return `McpClient.Page[A]` which also provides factories `McpClient.Page.empty[A]` and `McpClient.Page.of(items, next)` plus a `.isLast` predicate for cursor-based iteration.

```scala
val tools: McpClient.Page[McpHandler.ToolMeta] < (Async & Scope & Abort[McpException | Closed]) =
    val info = McpInfo(name = "calc-tester")
    val caps = McpCapabilities.Client()
    JsonRpcTransport.inMemory.map { (_, clientT) =>
        McpClient.initWith(clientT, info, caps)(_.listTools())
    }
end tools
```

Each cursor-paginated list returns `McpClient.Page[A](items, nextCursor, meta)`. The `meta: Maybe[Structure.Value]` field is the spec's advisory `_meta` carve-out; the same `_meta` passthrough rides on `ToolOutcome` and `PromptOutcome` and is the only place the typed API hands you the raw JSON shape.

## Building handlers

Every MCP endpoint, whether a tool, resource, prompt, or completion, is an `McpHandler[In, Out, +E]` value: the sealed user-facing carrier pairing the descriptive metadata (name, schemas, capability hints, annotations) with the implementation closure the engine invokes on inbound requests. Construct values via the role-tagged factories on the companion; each factory takes the handler closure directly so there is no separate route descriptor step.

Five factories cover the standard MCP surface: `tool`, `resource`, `resourceTemplate`, `prompt`, `completion`. `toolMulti` is a multi-content variant of `tool`; `completionWith` is a 2-arg variant of `completion` that also threads the previously-filled-arguments context; `custom` lets you bolt a raw typed JSON-RPC method into the same engine for protocol extensions. The factories interleave their type clauses so the call site annotates only `[In]` and the compiler infers `[Out]` from the handler's return type.

The handler closure receives `In` and returns `Out < (Async & Abort[McpException | JsonRpcResponse.Halt])`. The per-request context is reachable through the `Mcp.*` accessors (`Mcp.server`, `Mcp.progress`, `Mcp.requestId`, `Mcp.cancelled`, `Mcp.extras`) rather than an explicit parameter; see the per-request context subsection further down.

### Tool handlers

In MCP, a tool is a server-exposed function the LLM client can decide to call. The model receives the list of tool descriptions during the handshake and may decide, mid-conversation, that it needs one. The client then sends `tools/call` with the tool name and a JSON arguments object; the server runs the tool and returns the result as content (text, image, audio, embedded resource) that flows back into the model's context. Tools are the main extension point for letting an LLM act on the outside world: search the web, query a database, hit a private API, run a calculator, render a chart.

`McpHandler.tool[In]` is the single-content tool factory: the handler returns a value `<: McpContent` and the engine wraps it in a `ToolOutcome` with `content = Chunk(out)`. The `Out` type parameter is inferred from the handler's return type via clause interleaving, so the call site only needs `[In]`. `McpHandler.toolMulti[In]` is the multi-content sibling: the handler returns a full `ToolOutcome` and is free to emit multiple content leaves and a `structuredContent` payload.

```scala
val weatherTool =
    McpHandler.tool[Weather](
        name = "weather",
        description = "Looks up weather for a city",
        annotations = McpHandler.ToolAnnotations(
            title = Present("Weather Lookup"),
            readOnlyHint = Present(true),
            destructiveHint = Absent,
            idempotentHint = Present(true),
            openWorldHint = Present(true)
        )
    ) { req =>
        McpContent.text(s"Sunny in ${req.city}")
    }
```

The `ToolAnnotations` record captures the spec's display and behavioural hints; every field is optional.

> **Note:** Pick `tool` when one content leaf suffices; reach for `toolMulti` when the tool emits a mixed bag (text plus an image, say) or wants to populate the typed `structuredContent` slot that `callToolTyped[In, Out]` decodes against.

`ToolAnnotations.noop` is the empty-record default; leave `annotations` unset and the field is omitted on the wire. `ResourceAnnotations` works the same way.

### Resource handlers

Resources are server-exposed read-only data the client can fetch by URI. Where tools execute behaviour on demand, resources expose state the client wants to read into the model's context: a project file, a database table snapshot, a configuration document, a piece of conversation history. The client discovers what is available via `resources/list` (and `resources/templates/list` for the templated kind) and fetches the bytes via `resources/read` with the URI in `params`. The server replies with `Chunk[ResourceContents]` (one or more `Text(uri, mimeType, text)` or `Blob(uri, mimeType, base64)` entries) which the client then forwards to the model.

`McpHandler.resource(uri, name, ...)` registers a fixed-URI resource; `McpHandler.resourceTemplate(uriTemplate, name, ...)` registers an RFC 6570 URI-template resource matching every URI that fits the pattern (e.g. `file:///{path}`, where one handler serves many paths). Both handlers return `Chunk[McpHandler.ResourceContents]`; both URI inputs are typed opaque values (`McpResourceUri` for full URIs, `McpResourceUri.Template` for templates), never raw `String`.

```scala
val readmeUri = McpResourceUri("file:///README.md")
val readme =
    McpHandler.resource(
        uri = readmeUri,
        name = "readme",
        description = "Project README"
    ) {
        Chunk(McpHandler.ResourceContents.text(readmeUri, "Hello, world!", Present(McpMimeType("text/markdown"))))
    }
```

The handler is a by-name effectful value; it takes no parameter because the URI is fixed at registration. Reference the captured `readmeUri` val when the body needs the URI string in the produced `ResourceContents`. For URI-template resources where the URI is dynamic per request, the closure does take the matched URI:

```scala
val tmpl = McpResourceUri.Template("file:///{path}")
val fileTemplate =
    McpHandler.resourceTemplate(uriTemplate = tmpl, name = "file") { uri =>
        val bindings = tmpl.extract(uri)
        val path     = bindings.flatMap(b => Maybe.fromOption(b.get("path"))).getOrElse("")
        Chunk(McpHandler.ResourceContents.text(uri, s"contents for $path", Absent))
    }
```

`McpResourceUri.Template.extract(uri)` resolves the inbound URI against the template's `{name}` placeholders and returns `Present(Map(name -> value))` on a match, `Absent` otherwise. RFC 6570 Level 1 (`{var}`) is supported.

> **Caution:** `Template.extract` captures reserved characters like `/`, so `Template("file:///{path}").extract("file:///foo/bar.txt")` binds `path -> "foo/bar.txt"`, not just `path -> "foo"`. Strict path-segment matching is not the default; if you need it, post-process the captured value.

`McpHandler.resource(..., subscribe = false)` is the default; passing `subscribe = true` on a per-resource basis is what causes the server to advertise `resources.subscribe = true` and accept `subscribeResource` calls from the client. A client `subscribeResource` against a server with no opted-in route is rejected by the capability gate.

### Prompt handlers

A prompt in MCP is a server-supplied, parameterised prompt template the client can fetch and feed to the LLM. The server publishes a named prompt with declared arguments (`prompts/list`); the client fetches it with `prompts/get` and a `Map[String, String]` of argument values; the server renders the prompt into one or more conversation turns (a `Chunk[PromptMessage]` with user/assistant `Role`s) which the client then passes to the model as conversation context. Use prompts to ship a curated way to ask the LLM something rather than expose raw text: a "code review" prompt that bundles instructions, style guides, and the user-supplied code into a coherent message sequence is a typical case.

`McpHandler.prompt(name, description, arguments)` registers a prompt the client can fetch by name. The handler receives `Map[String, String]` (the arguments the client sent) and returns `PromptOutcome` (the rendered messages).

```scala
val explainPrompt =
    McpHandler.prompt(
        name = "explain",
        description = "Explain a topic",
        arguments = Chunk(McpHandler.PromptArgument(
            name = "topic",
            description = Present("topic to explain"),
            required = true
        ))
    ) { args =>
        val topic = args.getOrElse("topic", "")
        McpHandler.PromptOutcome(
            description = Present(s"Explain $topic"),
            messages = Chunk(McpHandler.PromptMessage(
                role = McpContent.Role.User,
                content = McpContent.text(s"Please explain $topic.")
            ))
        )
    }
```

The declared `arguments` populate the `prompts/list` advertisement. The runtime map the handler receives is not validated against the declared list; enforce required arguments inside the handler when that matters.

### Completion handlers

Completion in MCP is IDE-style argument autocompletion, not LLM text generation. When a user (in an IDE, agent harness, or chat client) is filling in the arguments of a prompt the server published, or filling in the variables of a resource-template URI, the client can call `completion/complete` with what has been typed so far and ask the server for ranked suggestions. The server returns a short list of strings the UI can render as a dropdown. The model is not involved; this is the same shape as bash tab-complete or an IDE autocompletion popup, just carried over JSON-RPC.

Two pieces of context name the cursor:
- `CompletionRef` says *what is being completed*: `Prompt(name)` if the user is filling the arguments of a server-published prompt, `Resource(uri)` if the user is filling the variables of a resource template's URI.
- `CompletionArg(name, value)` says *which argument is in focus* and *what has been typed so far*: `name` is the argument name (e.g. `"topic"`), `value` is the partial string the user has entered (e.g. `"k"`).

The handler matches `arg.value` against its candidate strings and returns a `CompletionOutcome(values, total, hasMore)`. `values` is the suggestion list; `total` and `hasMore` are optional pagination hints when the server has more candidates than it is returning in one shot.

`McpHandler.completion(ref)` registers a completion provider; the closure receives the focused `CompletionArg` and produces the outcome. Reach for `McpHandler.completionWith(ref)` when the handler also needs the optional `Context` carrying values the user has already filled for *other* arguments of the same prompt (so a topic-completion can depend on, say, a previously-chosen language).

```scala
val topicCompletion =
    McpHandler.completion(McpHandler.CompletionRef.Prompt("explain")) { arg =>
        McpHandler.CompletionOutcome(
            values = Chunk("kyo", "scala", "mcp").filter(_.startsWith(arg.value)),
            total = Absent,
            hasMore = Absent
        )
    }
```

`CompletionRef.Prompt("explain")` ties this completion to the `"explain"` prompt registered earlier. The handler inspects `arg.value` (the partial string), filters its candidate list by prefix, and returns the survivors.

`McpClient.complete(ref, arg)` currently forwards `Absent` for context, so handlers that inspect `contextOpt` via `completionWith` will receive `Absent` when called through the built-in client.

`McpHandler[In, Out, +E].error[E2](code, message)` adds an entry to the handler's typed error channel; the handler then aborts with values of type `E2` and the engine maps them to the spec'd JSON-RPC error code on the wire.

### Custom handlers

Custom handlers expose arbitrary JSON-RPC methods that aren't part of the standard MCP surface. MCP servers and clients are free to define extension methods (vendor namespaces like `acme/serverStatus`, draft spec additions, internal RPCs between trusted peers); the engine has no special knowledge of them but will dispatch by name like any built-in method.

`McpHandler.custom[In](method)(handler)` is the factory. The shape mirrors the built-in ones; the engine treats the handler as `Kind.Custom`, so it does not contribute to capability auto-derivation and does not appear in the standard `*/list` advertisements (clients discover custom methods through out-of-band agreement).

### The per-request context

Handler closures take only the typed `In` parameter; per-request fields are reached through the `Mcp.*` accessors, each of which reads from a per-request `Local` the engine binds at dispatch time:

- `Mcp.cancelled`: yields the `Fiber.Promise[Unit, Sync]` that completes when the peer cancels this request. Race it against the handler's work.
- `Mcp.requestId`: the JSON-RPC id of the inbound request (`Absent` for notifications).
- `Mcp.extras`: protocol-specific extra fields from the inbound envelope.
- `Mcp.server`: the live `McpServer` handle for reverse-direction calls (`requestSampling`, `requestRoots`, `requestElicitation`) and notifications. Typed `McpServer`, never `McpServer.Unsafe`.
- `Mcp.progress(progress, total, message)`: reports an MCP-shaped progress notification keyed on the `_meta.progressToken` the client supplied.

`Mcp.server.map(_.requestSampling(req))` lets a tool handler ask the client to run an LLM completion mid-handler; `Mcp.server.map(_.requestElicitation(req))` lets the handler collect additional user input. Both use the typed request and response records (`McpServer.SamplingRequest` / `McpServer.SamplingResponse`, `McpServer.ElicitationRequest` / `McpServer.ElicitationResponse`).

> **Note:** `Mcp.progress` is a silent no-op when the client did not supply a `_meta.progressToken` on the inbound request. That is the expected-normal path, not an error: clients ask for progress by sending a token, and a request without one is the client's way of saying "I do not want progress events." Handlers can call `Mcp.progress` unconditionally without guarding on token presence.

Calling any `Mcp.*` accessor outside an active route handler raises an `IllegalStateException` (kernel panic). This is a programmer-error path, not a domain failure, so the accessors do not widen handler effect rows with a typed `Abort`.

## Content and URIs

The MCP wire protocol exchanges a small set of payload shapes: content leaves in tool results, sampling messages, and prompt messages; resource contents from `resources/read`; URIs and URI templates that name resources; media types that describe payload encodings. `kyo-mcp` exposes each as a typed value, never raw `String` or raw JSON.

### Content

Tool results, sampling messages, and prompts all carry the same content union, so a handler can build one payload shape and reuse it across every place MCP exchanges content. That union is `McpContent`, sealed across five cases: `Text`, `Image`, `Audio`, `EmbeddedResource`, and `ResourceLink`; the companion ships `text(...)`, `image(...)`, `audio(...)`, and `resource(...)` factories that fill in `Annotations.noop` for the optional annotations slot.

```scala
val txt: McpContent = McpContent.text("hello")
val img: McpContent = McpContent.image(data = "<base64>", mimeType = McpMimeType("image/png"))
```

`McpContent.Role` is the `User / Assistant / System` enum used on prompt and sampling messages; its wire form is the lowercase string.

`McpHandler.ResourceContents` is the read-side payload returned by `resource` and `resourceTemplate` handlers. Two cases (`Text` and `Blob`) with the same `uri` / `mimeType` shell:

```scala
val read: Chunk[McpHandler.ResourceContents] = Chunk(
    McpHandler.ResourceContents.text(
        McpResourceUri("file:///hello.txt"),
        "hello, world",
        Present(McpMimeType("text/plain"))
    )
)
```

Pick `text` for UTF-8 payloads; reach for `blob` for base64-encoded binary.

### URIs, templates, and media types

Four opaque newtypes share one construction convention. Each has:

- A validated `parse(s: String): Maybe[T]` for user input from outside your code.
- A trusted `apply(s: String): T` for the library boundary and call sites where the string has already been validated.
- An `.asString` accessor for wire conversion.

The four types:

- `McpResourceUri`: a typed resource URI. `parse` returns `Absent` on empty or whitespace input.
- `McpResourceUri.Template`: an RFC 6570 Level 1 URI template. Adds `.extract(uri): Maybe[Map[String, String]]` for matching an inbound URI against the template and capturing the `{var}` bindings.
- `McpMimeType`: an RFC 6838 media type. `parse` validates the `type/subtype[;params]` shape and returns `Absent` on malformed input.
- `McpConfig.ProtocolVersion`: an MCP protocol-version string. `parse` returns `Present(v)` only when `v` is in `McpConfig.ProtocolVersion.supported`.

Use `parse` for any string that comes from outside your code (handler input, configuration files, command-line arguments); use `apply` only when you have already validated the string.

The wire codecs for these four types accept any peer-supplied string unchecked, so your `parse` call at the boundary is the only validation.

## Reverse-direction calls

A live MCP server can also originate requests to the client. The three spec-defined reverse-direction methods are surfaced as typed extension methods on `McpServer`, each guarded by the client's advertised capabilities:

- `requestSampling(req)`: ask the client to run an LLM completion. Requires the client's `sampling` capability.
- `requestRoots`: list the workspace roots the client is willing to expose. Requires the client's `roots` capability.
- `requestElicitation(req)`: collect additional input from the user. Requires the client's `elicitation` capability.

These are reached from inside a handler body through `Mcp.server`:

```scala
val askLLM =
    McpHandler.tool[Weather]("askLLM") { req =>
        val sampling = McpServer.SamplingRequest(
            messages = Chunk(McpServer.SamplingRequest.Message(
                role = McpContent.Role.User,
                content = McpServer.SamplingContent.Text(s"What is the weather like in ${req.city}?")
            )),
            modelPreferences = Present(McpServer.SamplingRequest.ModelPreferences(
                hints = Chunk(McpServer.SamplingRequest.ModelHint(name = Present("claude-3-5-sonnet"))),
                intelligencePriority = Present(0.8)
            )),
            maxTokens = 256
        )
        Mcp.server.map(srv => Abort.run[Closed](srv.requestSampling(sampling))).map {
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

The reverse-direction surface is fully typed end-to-end: `McpServer.SamplingRequest`, `McpServer.SamplingResponse`, `McpServer.ElicitationRequest`, `McpServer.ElicitationResponse`, `McpServer.Root`. The only `Structure.Value` slots are the spec-open `metadata` / `content` fields whose shape is genuinely open.

Notifications (`server.notifyToolsListChanged`, `server.notifyResourcesListChanged`, `server.notifyResourceUpdated(uri)`, `server.notifyPromptsListChanged`, `server.notifyLog(level, data, logger)`) are the fire-and-forget mirrors. Each is gated by the matching capability advertisement.

## Error hierarchy

When a handler aborts, when the handshake fails, when a tool name is unknown, when an inbound request asks for an unadvertised capability: the engine surfaces every failure as a typed value in the sealed `McpException` hierarchy. The hierarchy is organised by the pipeline stage where each failure arises, so pattern-matching by stage is one line long while the leaf case classes preserve the precise diagnostic for logging:

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

When the receiving peer reads a wire error response, it arrives as `McpRemoteApplicationException(remoteCode, remoteMessage, remoteData)`; the wire triple is preserved verbatim from the sender.

> **Note:** Pattern-match on `remoteCode` to discriminate user-defined error families on the client side. `remoteCode` is the only stable discriminator. `remoteMessage` is a human-readable string the server is free to change between releases, and `remoteData` is an open JSON payload whose schema is owned by the sender's error type. The framework-generated leaves (`McpUnknownToolException` and the rest) stay distinct Scala types, so match them by type.

### Catching Java throwables

MCP handlers commonly wrap synchronous Java calls that throw; kyo does not auto-convert escaping `Throwable`s into typed `Abort` rows. An uncaught `ArithmeticException` from `Math.addExact` inside `Sync.defer` surfaces as a panic at the engine dispatch boundary and the client sees `-32603 InternalError`. Convert to a typed `Abort` row at the boundary:

```scala
val safeAddTool: McpHandler[AddIn, McpContent, McpException | AddOverflow] =
    McpHandler.tool[AddIn](name = "add", description = "Adds two integers (overflow-checked)") { in =>
        Abort.catching[ArithmeticException](
            Sync.defer(java.lang.Math.addExact(in.a, in.b))
        )
            .map(sum => McpContent.text(s"$sum"))
            .handle(Abort.recover[ArithmeticException] { _ =>
                Abort.fail(AddOverflow(a = in.a, b = in.b))
            })
    }
        .error[AddOverflow](code = -32001, message = "add-overflow")
```

The `Abort.catching[ArithmeticException]` narrows the throw to a typed effect-row entry; `Abort.recover` converts it into the registered `AddOverflow`. Once `.error[AddOverflow]` is wired, the wire response carries code `-32001` and the schema-encoded `AddOverflow` in the `data` slot, and the client receives `McpRemoteApplicationException(-32001, "add-overflow", Present(...))`.

## Configuration

`McpConfig` collects the per-peer behaviour knobs you reach for when the defaults are not what you want: a stricter handshake timeout for a flaky transport, an explicit list of advertised capabilities for a server that does not want auto-derivation, a non-default JSON-RPC codec for a non-compliant peer.

```scala
val tuned =
    McpConfig.default
        .handshakeTimeout(10.seconds)
        .capabilityGate(McpConfig.CapabilityGateMode.LogOnly)
        .autoNotifyListChanged(false)
```

The fluent setters (`serverInfo`, `instructions`, `supportedProtocolVersions`, `declaredCapabilities`, `handshakeTimeout`, `handshakeOrder`, `capabilityGate`, `autoNotifyListChanged`, `jsonRpc`) each return a new `McpConfig` with the named field overridden. Pass the result to `McpServer.init(transport, config)(handlers*)` or `McpClient.init(transport, info, caps, config)(handlers*)`.

`McpConfig.require(c)` validates the config (non-empty `supportedProtocolVersions`, positive `handshakeTimeout`, and delegates to `JsonRpcHandler.Config.require` for the embedded JSON-RPC slot). Every `init` variant calls it; an invalid config throws `IllegalArgumentException` at init time, not lazily.

`supportedProtocolVersions: Set[McpConfig.ProtocolVersion]` accepts the typed protocol-version newtype; use `McpConfig.ProtocolVersion.parse(wireString)` to construct from external input and `.asString` to recover the wire form.

### Handshake order

Two modes decide when the engine starts dispatching regular requests after `initialize`:

- `HandshakeOrder.RequireInitializedNotification` (default) waits for the client's `notifications/initialized` notification before dispatching regular requests.
- `HandshakeOrder.RequireInitializeRequestOnly` accepts requests as soon as the `initialize` request itself completes. Pick this only for clients that do not emit the follow-up notification.

### Identification and capability advertisement

Both sides exchange an identification record during the handshake so each peer knows who it is talking to; the record type is `McpInfo(name, version, title?)`. The server publishes one as `McpConfig.serverInfo`; the client passes one to `McpClient.init` as `clientInfo`. `name` is the wire-stable identifier other peers see; the optional `title` is the human-readable display name. The default `McpInfo(name = "kyo-mcp")` uses `version = McpConfig.ProtocolVersion.kyoMcpVersion`; ship a real version string in production code.

`McpCapabilities.Server` and `McpCapabilities.Client` are the typed records the two sides exchange during the handshake. Each is a record of optional sub-records, one per capability domain (`tools`, `resources`, `prompts`, `logging`, `completions` on the server side; `sampling`, `roots`, `elicitation` on the client side).

```scala
val explicitCaps =
    McpConfig.default.declaredCapabilities(
        McpCapabilities.Server(
            tools = Present(McpCapabilities.ToolsCapability(listChanged = true)),
            resources = Present(McpCapabilities.ResourcesCapability(subscribe = true, listChanged = true))
        )
    )
```

By default the server derives its advertised capabilities from the registered handlers: a server with `McpHandler.tool` handlers advertises `ToolsCapability`, one with `McpHandler.resource` advertises `ResourcesCapability`, and so on. Setting `McpConfig.declaredCapabilities(...)` disables the derivation; you become responsible for matching the advertisement to what you register.

Once the handshake completes, `server.clientCapabilities` and `client.serverCapabilities` expose the negotiated record as `Maybe[McpCapabilities.{Client, Server}]`.

> **Note:** `server.clientCapabilities`, `server.clientInfo`, and `server.protocolVersion` (and the matching `client.serverCapabilities`, `client.serverInfo`, `client.protocolVersion`) all return `Absent` until the `initialize` handshake completes. Touching them right after `init` returns can race the handshake; the engine has set `Async & Scope` aside for the dispatch fiber, but the handshake response may not yet have been received. The accessors do not block.

### Capability negotiation

The capability gate is `McpConfig`'s strictest dial: it decides what the engine does when a request asks for a capability the negotiated handshake did not include. The relevant knob is `McpConfig.capabilityGate(mode)`:

- `CapabilityGateMode.RejectUnsupported` (default): fail with `McpCapabilityNotAdvertisedException` before the handler runs. This is the production setting.
- `CapabilityGateMode.LogOnly`: log the gap and dispatch anyway. Use while developing against a non-compliant peer.
- `CapabilityGateMode.Off`: disable the gate entirely.

> **Caution:** `CapabilityGateMode.Off` admits every method regardless of advertisement. It is a dev-only escape hatch (for diagnosing "method not advertised" failures against a peer whose capability advertisement is wrong); it removes the only line of defence against accidentally calling unimplemented surface, so do not ship it. `RejectUnsupported` is the default for that reason.

`McpCapabilityNotAdvertisedException` carries a `requiredCapability: McpCapabilities.Name` field; the enum's eight cases (`Tools`, `Resources`, `Prompts`, `Sampling`, `Roots`, `Logging`, `Completions`, `Elicitation`) let handler code pattern-match on which capability the peer required.

### JSON-RPC defaults

The default `McpConfig` already wires MCP-shaped cancellation, progress (with `_meta.progressToken` extraction), and strict unknown-method handling, and sets no per-request timeout (`Duration.Infinity`) so long-running tools are not cut off. Override any slot via `.jsonRpc(...)`.

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
end pairedTest
```

For framers and codecs (line-delimited vs `Content-Length`, strict vs lenient JSON-RPC 2.0), see the `kyo-jsonrpc` README. The MCP engine sets the strict `Schema[JsonRpcEnvelope]` by default through `McpConfig.defaultJsonRpcConfig`; override via `McpConfig.jsonRpc(...)` when a peer requires the lenient schema.

## Lifecycle

Three pairs of methods control a server's or client's life:

- `init` / `initUnscoped`: bring up a peer and run the handshake. Scoped variants release at scope exit; unscoped variants require manual close.
- `close` / `close(grace)` / `closeNow`: tear it down. The no-arg `close(using Frame)` defaults to a 30-second grace period; `closeNow` is the explicit immediate variant.
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
end gracefulShutdown
```

The 30-second default avoids the `Duration.Zero` footgun where in-flight requests would fail with `Closed` the instant the caller called `close`. Use `closeNow` when you have already decided the connection is dead and want immediate teardown; use `close(grace)` when you want a bounded drain window.

> **Note:** Scope-managed `init` releases via `closeNow`, not `close(30.seconds)`. The 30-second grace period applies only to callers using `initUnscoped` / `initUnscopedWith` who invoke `close` themselves. If a scoped server must drain in-flight requests on shutdown, call `awaitDrain` before the scope exits or `close(grace)` explicitly inside the body; do not rely on the scope's automatic release to give you a grace period.

### Scoped vs unscoped

The scoped `init` is the default and what almost all callers want: the server / client closes when the enclosing `Scope` exits, the dispatch fiber is interrupted, and the transport's `close` runs. The unscoped variant exists for handlers whose lifetime exceeds any single scope; a server that runs for the entire process, say. An unscoped peer that never has `close` called leaks the dispatch fiber and never releases the transport.

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
