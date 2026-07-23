<!-- doctest:setup
```scala
import kyo.*

case class WeatherIn(city: String) derives Schema, CanEqual
case class Forecast(city: String, summary: String, tempC: Int) derives Schema, CanEqual
case class ExplainIn(topic: String, language: Maybe[String]) derives Schema, CanEqual
case class ForecastUnavailable(city: String) derives Schema, CanEqual
case class Confirm(approved: Boolean) derives Schema, CanEqual

def lookupForecast(city: String): Forecast < Sync = Forecast(city, "clear", 21)
def renderChartPng(f: Forecast): String           = "iVBORw0KGgo="
def externalTempReading(city: String): String     = "21"
val png: McpMimeType                              = McpMimeType("image/png")

val weather = McpHandler.tool[WeatherIn]("weather", "Current conditions for a city") { in =>
    lookupForecast(in.city)
}
val explain = McpHandler.prompt[ExplainIn]("explain", "Explain a topic to the user") { in =>
    val lang = in.language.getOrElse("English")
    McpHandler.PromptOutcome.of(
        Present(s"Explaining ${in.topic}"),
        McpHandler.PromptMessage.user(s"Explain ${in.topic} in $lang.")
    )
}
val info = McpInfo("weather-cli")
val caps = McpCapabilities.Client()
```
-->

# kyo-mcp

`kyo-mcp` is the Model Context Protocol module: a server you expose to LLM hosts (Claude Desktop, IDE agents, anything that speaks MCP) and a client you use to drive other MCP servers. Both run on JVM, JavaScript, and Scala Native, and both ride on top of `kyo-jsonrpc` over any `JsonRpcTransport` (stdio, Unix domain socket, in-memory pipe, or a custom wire). You describe a server as a flat list of handler values, one per endpoint, and hand them to `McpServer.init`; the engine runs the protocol for you. You drive a server with `McpClient.init`, which performs the `initialize` handshake eagerly and then exposes one typed method per MCP request.

The first thing you write is a handler and an `init` call, not a protocol type. A tool is `val weather = McpHandler.tool[WeatherIn]("weather", "...") { in => lookupForecast(in.city) }`; the engine decodes the inbound `params` into your `In` and encodes your return value back to the wire. Hand that handler to `McpServer.init`, point a client at the same transport, and a typed `callTool` round-trips a decoded `Forecast`:

```scala
val roundTrip: Forecast < (Async & Scope & Abort[McpException]) =
    JsonRpcTransport.inMemory.map { (serverTransport, clientTransport) =>
        McpServer.init(serverTransport, weather).map { _ =>
            McpClient.initWith(clientTransport, info, caps) { client =>
                client.callTool[Forecast]("weather")(WeatherIn("Paris"))
            }
        }
    }
```

The engine derives the tool's advertised output schema from `Forecast`, encodes the returned value into both a human-readable text leaf and a machine-readable `structuredContent` slot, and `callTool[Forecast]("weather")(WeatherIn("Paris"))` decodes that slot back into a `Forecast`. The architectural carrier (`McpHandler[In, Out, +E]` and its client-side mirror `McpClientHandler[In, Out, +E]`) and the typed-versus-raw lane distinction matter once you go past the first call, so this README introduces them where the curriculum reaches them rather than up front. The sections below build out from this one tool to the full server and client surface.

## Standing up a server

`McpServer.init` is where a list of handlers becomes a running peer. You hand it a transport and a varargs list of `McpHandler` values; it returns `McpServer < (Async & Scope)`. The `Async` is there because background fibers drive the JSON-RPC read/dispatch/write loop, and the `Scope` is there because those fibers and the transport must be released when the enclosing scope exits.

The minimal end-to-end server declares one handler, opens a transport, hands both to `McpServer.initWith`, and parks:

```scala
val program: Unit < (Async & Scope) =
    JsonRpcTransport.stdio().map { transport =>
        McpServer.initWith(transport, weather)(_ => Async.never)
    }
```

`McpServer.initWith(transport, handlers*)(f)` is the common shape: it stands up the server, runs `f(server)`, and releases at scope exit. `McpServer.init(transport, handlers*)` returns the bare `McpServer < (Async & Scope)` when you need to thread the handle through richer flow. Both carry a curried `(transport, config)(handlers*)` overload for a non-default `McpConfig`, covered under [Configuration](#configuration).

### Running a server as a process

The `program` value above is an effect description, not a running process. A `KyoApp` turns it into a launchable object:

```scala doctest:expect=skipped
import kyo.*

object WeatherServer extends KyoApp:
    case class WeatherIn(city: String) derives Schema, CanEqual
    case class Forecast(city: String, summary: String, tempC: Int) derives Schema, CanEqual

    run {
        val weather =
            McpHandler.tool[WeatherIn]("weather", "Current conditions for a city") { in =>
                Forecast(in.city, "clear", 21)
            }
        JsonRpcTransport.stdio().map { transport =>
            McpServer.initWith(transport, weather)(_ => Async.never)
        }
    }
end WeatherServer
```

`Async.never` parks the main fiber forever: the inbound stdio dispatch fiber drives all the work, so the main fiber only has to hold the scope open until the host stops the process. For a server that should shut itself down, replace `Async.never` with a `Fiber.Promise[Unit, Sync]` that a handler completes when it is time to exit.

> **Note:** The handshake is invisible. The engine owns the `initialize` request and the `notifications/initialized` follow-up, so no handler ever sees those methods. The handshake is once-only: a second `initialize` is rejected with `McpHandshakeAlreadyInitializedException`, and any non-`ping` request that arrives before the handshake completes is rejected with `McpHandshakeNotInitializedException`. `ping` is the single liveness request admitted before the handshake.

## Authoring handlers

Every server endpoint is an `McpHandler` value built by a role-tagged factory on the `McpHandler` companion, where the role names what the MCP host can do with it: a tool is a function the model may call, a resource is read-only data the host can fetch, a prompt is a parameterised message template, a completion is IDE-style argument autocomplete, and a custom method is a vendor extension. The factories share one shape: you annotate `[In]` at the call site and the compiler infers `[Out]` from your handler's return type through clause interleaving, so the only type you ever write is the request type.

The per-request context (who is calling, the cancellation signal, the progress channel, the live peer for reverse calls) is not a handler parameter. It is reached through the `Mcp.*` accessors covered in [Inside a handler](#inside-a-handler-the-per-request-context). That keeps every handler signature down to `In => Out`.

### Tools

A tool is the endpoint the model calls to take an action or compute a result. `tool[In]` is the default: you return one value of any type with a `Schema`, and the engine encodes it into both a text mirror (so a model that only reads text still sees the answer) and the `structuredContent` slot (so a typed client gets the value back). The returned type drives the advertised `outputSchema`, so the three stay in agreement by construction.

```scala
val weatherTool =
    McpHandler.tool[WeatherIn]("weather", "Current conditions for a city") { in =>
        lookupForecast(in.city)
    }
```

When one returned value is not enough (you want several content leaves, an image alongside text, an in-band `isError` flag the model should see, or hand-built `structuredContent`), drop to `toolRaw[In]`, which returns a full `ToolOutcome`:

```scala
val weatherWithChart =
    McpHandler.toolRaw[WeatherIn]("weather.chart", "Forecast with a chart") { in =>
        lookupForecast(in.city).map { f =>
            McpHandler.ToolOutcome.ok(
                McpContent.text(f.summary),
                McpContent.image(renderChartPng(f), png)
            )
        }
    }
```

When you need a single typed result the engine should serialise for you, use `tool[In]`. When you need several content leaves or in-band `isError`, use `toolRaw[In]`. A typed client pairs naturally with `tool` (see [the typed lane](#driving-a-server-the-client)); a `toolRaw` handler that does not populate `structuredContent` pairs with the client's raw lane instead.

`ToolOutcome.ok(content, more*)` and `ToolOutcome.error(message, more*)` are the smart constructors. Both drop blank text leaves so a whitespace-only `McpContent.Text` never reaches the wire.

> **Note:** `ToolOutcome.error` is for failures the model should see and may retry on; it sets `isError = true` and carries the message as a text leaf. For a protocol fault the model must not see, abort an `McpException` (or a registered `.error[E2]`, below) instead, which becomes a JSON-RPC error rather than a tool result.

### Resources and resource templates

A resource is read-only data the host can fetch by URI. Use `resource` when the URI is fixed and known at registration; use `resourceTemplate` when the URI carries variables (an RFC 6570 template such as `weather:///{city}/history`). A fixed resource's handler is a by-name value because the engine only dispatches to it when the inbound URI equals the registered one; a template's handler receives the matched URI and its extracted bindings.

```scala
val readme =
    McpHandler.resource(
        McpResourceUri("weather:///readme"),
        name = "readme",
        description = "How to read these forecasts",
        mimeType = Present(McpMimeType("text/markdown"))
    ) {
        Chunk(McpHandler.ResourceBody.text("# Weather\nForecasts are wishful thinking."))
    }
```

```scala
val history =
    McpHandler.resourceTemplate(
        McpResourceUri.Template("weather:///{city}/history"),
        name = "history",
        description = "Recent observations for a city"
    ) { matched =>
        matched.requireVariable("city").map { city =>
            Chunk(McpHandler.ResourceBody.text(s"history for $city"))
        }
    }
```

> **Note:** `ResourceBody` is URI-less by design. `ResourceBody.text(content, mime = Absent)` takes no `uri` parameter; the engine stamps the registered (fixed) or matched (template) URI onto every body. A body whose URI disagrees with its registration is unrepresentable, so do not go looking for a `uri` argument. This is distinct from `ResourceContents`, the client read type below, which does carry the URI.

> **Caution:** `ResourceMatch.requireVariable(name)` aborts `McpInvalidArgumentException` when the binding is absent, rather than silently returning an empty string. Reach for it on required path segments. `matched.variable(name)` returns a `Maybe[String]` for the genuinely optional ones. Template extraction captures reserved characters including `/`, so `weather:///{path}` binds `path` to `a/b.txt` whole, not just `a`.

### Prompts

A prompt is a named, parameterised template the host expands into conversation messages. `prompt[In]` is the typed form: it derives the advertised argument list from `In`'s fields (a `Maybe` field becomes an optional argument, `required = false`) and decodes the inbound `Map[String, String]` into `In` for you. `ExplainIn` carries a `Maybe[String]` `language` field, so the handler reads it with `getOrElse` and the advertised `language` argument is optional:

```scala
val explainPrompt =
    McpHandler.prompt[ExplainIn]("explain", "Explain a topic to the user") { in =>
        val lang = in.language.getOrElse("English")
        McpHandler.PromptOutcome.of(
            Present(s"Explaining ${in.topic}"),
            McpHandler.PromptMessage.user(s"Explain ${in.topic} in $lang.")
        )
    }
```

> **Caution:** A required `In` field that is absent from the inbound map is a typed decode failure (`McpInvalidArgumentException`), never a silent `""`. Optionality is expressed by making the field a `Maybe`, exactly as `language` is above.

When you need per-argument metadata that has no home on a bare field (a description or title shown in the host's argument picker), use the raw `prompt(name, description, arguments)` form over `Map[String, String]`, supplying the `PromptArgument` list yourself:

```scala
val explainRaw =
    McpHandler.prompt(
        "explain.raw",
        "Explain a topic",
        Chunk(McpHandler.PromptArgument("topic", Present("the subject"), required = true))
    ) { args =>
        McpHandler.PromptOutcome.of(
            Absent,
            McpHandler.PromptMessage.user(s"Explain ${args.getOrElse("topic", "")}.")
        )
    }
```

When the argument list is just typed fields, use `prompt[In]`. When an argument needs a description or title for the host's picker, use the raw `prompt(name, description, arguments)` form. `PromptMessage.messages(...)` is the empty-dropping constructor: a message whose content is a blank text leaf is dropped, mirroring `ToolOutcome.content`.

### Completions

A completion supplies argument autocomplete for a prompt or a resource template, the way an IDE suggests values as you type. The 1-argument `completion` form receives only the `CompletionArg` (the argument name and the partial value the user has typed):

```scala
val topicCompletion =
    McpHandler.completion(McpHandler.CompletionRef.Prompt("explain")) { arg =>
        val all     = Chunk("functions", "fibers", "schemas")
        val matches = all.filter(_.startsWith(arg.value))
        McpHandler.CompletionOutcome(matches, total = Present(matches.size), hasMore = Present(false))
    }
```

`completion[In](promptHandler)` reads the ref off an existing prompt handler value (here the `explain` prompt built above) so you do not restate the prompt name a third time:

```scala
val topicCompletion2 =
    McpHandler.completion(explain) { arg =>
        McpHandler.CompletionOutcome.of(arg.value)
    }
```

> **Unlike** `completion`, `completionWith(ref)` gives the handler a second argument, `Maybe[CompletionArg.Context]`, carrying the values the user has already filled for other arguments of the same prompt. A handler registered with the 1-argument `completion` form always receives `Absent` for that context (the engine discards it); register with `completionWith` when your suggestions depend on the other arguments. The 1-argument form is not a wrapper that "usually" omits context: it structurally never has it.

### Custom methods

`custom[In](method)` registers an arbitrary JSON-RPC method outside the standard MCP surface, for vendor extensions. It is `Kind.Custom`, so it is excluded from capability derivation and from the `*/list` advertisements; the host has to know the method name out of band.

```scala
val ping =
    McpHandler.custom[Unit]("vendor/ping") { _ =>
        "pong"
    }
```

### Typed domain errors

A handler body can `Abort.fail` with one of your own error types, and `.error[E2](code, message)` maps that type to a wire `code` and `message`. The mapping rides the same handler value; aborting an `E2` becomes a JSON-RPC error the client receives as an `McpException` carrying that code.

```scala
val forecastTool =
    McpHandler
        .tool[WeatherIn]("weather", "Current conditions for a city") { in =>
            if in.city.isEmpty then Abort.fail(ForecastUnavailable(in.city))
            else lookupForecast(in.city)
        }
        .error[ForecastUnavailable](code = -40001, message = "forecast-unavailable")
```

The alternative overload reads the code and message from an `McpHandler.McpErrorCode[E2]` given, so a shared error family states its code once rather than at each registration. Author codes in the framework-reserved range `-32000..-32003` are rejected when the server starts, so a domain error cannot collide with a protocol code.

## Inside a handler: the per-request context

A handler signature is just `In => Out`, with no context parameter, because the engine binds the per-request context into a `Local` for the duration of each dispatch. Handler code reaches into it through the `Mcp.*` accessors, which read that local: the live peer, the inbound request id, the cancellation signal, the inbound `_meta` extras, and the progress channel. Treat them as one mental model: ambient state the engine has already bound by the time your body runs.

```scala
val slowForecast =
    McpHandler.tool[WeatherIn]("weather.slow", "A forecast that reports progress") { in =>
        Mcp.progress(0.5, Present(1.0), Present("fetching")).andThen {
            lookupForecast(in.city)
        }
    }
```

> **Note:** `Mcp.progress(...)` is a silent no-op when the client supplied no `_meta.progressToken`. That is the normal "I do not want progress" path, so a handler calls it unconditionally without guarding. Its own row is `Async & Abort[McpConnectionClosedException]` (the notification can race a closing transport); that leaf is tracked and propagates into the tool's own `E`, so the handler discharges nothing and just sequences past it with `andThen`.

`Mcp.server` returns the live `McpServer` handle for reverse-direction calls. It panics (a kernel-level `IllegalStateException`) when called outside a handler, because that is a programmer error rather than a runtime condition; the accessors therefore do not widen your `Abort` row.

> **Unlike** `Mcp.server`, `Mcp.serverMaybe` is total: it returns `Maybe[McpServer]`, `Absent` outside a handler. Use `serverMaybe` from code that might run in either place; use `server` inside a handler where the panic would only ever signal a bug.

The remaining accessors round out the context: `Mcp.requestId` (the inbound JSON-RPC id, `Absent` for a notification), `Mcp.cancelled` (a promise that completes when the peer cancels this request, to race against your work), and the `_meta` readers. `Mcp.extras` returns the raw `Maybe[Structure.Value]`; `Mcp.extras[T]` decodes that slot to a typed `T`:

```scala
case class TraceMeta(traceId: String) derives Schema, CanEqual

val traced =
    McpHandler.tool[WeatherIn]("weather.traced", "Forecast that reads trace metadata") { in =>
        Mcp.extras[TraceMeta].map { meta =>
            val traceId = meta.map(_.traceId).getOrElse("untraced")
            lookupForecast(in.city).map(f => f.copy(summary = s"${f.summary} [$traceId]"))
        }
    }
```

> **Caution:** `Mcp.extras[T]` decodes, so it can fail: its row is honestly `Sync & Abort[McpDecodeException]`. A present-but-non-conforming `_meta` aborts `McpDecodeException` rather than silently yielding `Absent`. The untyped `Mcp.extras` never fails because it does no decoding.

For reverse-direction calls (the server asking the client to do something), `Mcp.requestSampling`, `Mcp.requestElicitation`, and `Mcp.requestRoots` reach the client directly without exposing the `McpServer` handle, so the handle cannot leak into a detached fiber. They are covered with their client-side answers under [Reverse direction](#reverse-direction-server-originated-requests).

## Driving a server: the client

`McpClient.init` is the mirror of `McpServer.init` with two mandatory extra arguments: the `clientInfo: McpInfo` and the `capabilities: McpCapabilities.Client` you advertise during the handshake. Its result is `McpClient < (Async & Scope & Abort[McpInitFailure])`, because the `initialize` handshake runs eagerly inside `init` and surfaces handshake faults (a protocol-version mismatch, a transport that closed mid-handshake) directly in the row.

The whole client surface is organised around one distinction: a **typed default lane** that decodes the response into your type, and a **raw escape hatch** that hands you the protocol record. The typed lane is the plain name because it is the recommended one. The two handshake arguments are a `val info = McpInfo("weather-cli")` and a `val caps = McpCapabilities.Client()` (an empty client advertises no reverse-direction capabilities); the client blocks below pass that pair to every `init`:

```scala
val forecast: Forecast < (Async & Scope & Abort[McpException]) =
    JsonRpcTransport.stdio().map { transport =>
        McpClient.initWith(transport, info, caps) { client =>
            client.callTool[Forecast]("weather")(WeatherIn("Paris"))
        }
    }
```

> **Note:** `callTool[Out](name)(input)` is the typed default: it decodes the tool's `structuredContent` into `Out`. The clauses are interleaved so you annotate only `[Out]` (the type the compiler cannot infer) and `In` is read off `input`, mirroring the server's `tool[In]` factory. This is the reverse of the usual "plain name means low level" convention, because typed is the lane you want. The raw `ToolOutcome` lane is the separately named `callToolRaw[In]`. The same pairing holds for `readResource[Out]` versus `readResourceRaw`, and `getPrompt[Out]` versus `getPromptRaw`.

`tool[In]` on the server and `callTool[Out](name)(input)` on the client are a coupled pair: the server's `tool` factory encodes the returned value into `structuredContent`, and `callTool` decodes it, so the two agree by construction. When you call `callTool[Out](name)(input)` against a server using `toolRaw` that did not populate `structuredContent`, the call aborts `McpToolStructuredMissingException`; that server pairs with `callToolRaw` instead.

```scala
val rawOutcome: McpHandler.ToolOutcome < (Async & Scope & Abort[McpException]) =
    JsonRpcTransport.stdio().map { transport =>
        McpClient.initWith(transport, info, caps) { client =>
            client.callToolRaw[WeatherIn]("weather.chart", WeatherIn("Paris"))
        }
    }
```

`readResource[Out]` decodes a resource that serves a single text leaf of serialized JSON into `Out`:

```scala
val readme: String < (Async & Scope & Abort[McpException]) =
    JsonRpcTransport.stdio().map { transport =>
        McpClient.initWith(transport, info, caps) { client =>
            client.readResourceRaw(McpResourceUri("weather:///readme")).map { contents =>
                contents.headMaybe match
                    case Present(McpHandler.ResourceContents.Text(_, _, text)) => text
                    case _                                                     => ""
            }
        }
    }
```

> **Caution:** `readResource[Out]` is strict about shape. A Blob leaf or a text leaf that does not decode to `Out` aborts `McpToolStructuredDecodeException`; an empty response aborts `McpToolStructuredMissingException`. Use `readResourceRaw` for binary or multi-leaf resources, as the example above does.

> **Note:** `getPrompt[Out]` decodes the prompt's `_meta` slot (not its rendered messages) into `Out`, and a prompt with no `_meta` aborts `McpToolStructuredMissingException`. Most callers want the rendered messages, so most callers want `getPromptRaw`, which returns the `PromptOutcome`; the typed lane is for prompts that ship structured `_meta`. `getPromptChecked[Out]` first validates your argument map against the server's advertised `PromptMeta`, aborting `McpInvalidArgumentException` for an unknown key or a missing required argument before the request goes out.

### Listing and streaming

The `list*` methods return one `Page[A]` (its `items`, and a `nextCursor` that is `Absent` on the last page); you re-call with the cursor to walk the pages yourself. The `stream*` methods do that walk for you, draining every page into one `Stream`.

When you want one page (to show the first screen, or to drive your own pagination UI), use `listTools` / `listResources` / `listResourceTemplates` / `listPrompts`. When you want every item regardless of paging, use `streamTools` / `streamResources` / `streamResourceTemplates` / `streamPrompts`.

```scala
val allToolNames: Chunk[String] < (Async & Scope & Abort[McpException]) =
    JsonRpcTransport.stdio().map { transport =>
        McpClient.initWith(transport, info, caps) { client =>
            client.streamTools.map(_.name).run
        }
    }
```

### Other requests and negotiated state

The remaining requests each name their MCP operation: `complete(ref, arg, context)` for a completion suggestion, `setLogLevel(level)` for the minimum log level you want to receive, `ping` for a liveness round trip, `subscribeResource(uri)` / `unsubscribeResource(uri)` for the resource-subscription protocol, and `notifyRootsListChanged` to tell the server your roots changed.

After the handshake, the negotiated state is available without a `Maybe`, because a scoped client handle is always post-handshake: `serverCapabilities`, `serverInfo`, and `protocolVersion` return concrete values. `supports(cap)` is the predicate form, so you can branch on a capability without catching an error:

```scala
val canSubscribe: Boolean < (Async & Scope & Abort[McpInitFailure]) =
    JsonRpcTransport.stdio().map { transport =>
        McpClient.initWith(transport, info, caps) { client =>
            client.supports(McpCapabilities.Name.Resources)
        }
    }
```

> **Note:** `supports(cap)` and the local capability guard agree exactly. A request whose capability the server never advertised is rejected by the guard with `McpCapabilityNotAdvertisedException` (its `peer` is `Peer.Server`) before any bytes leave the client, so the failure is local and immediate rather than a round trip. `subscribeResource` is the common case: it only succeeds against a server that registered at least one `resource(..., subscribe = true)` handler, because that flag is what makes the server advertise `resources.subscribe`.

## Reverse direction: server-originated requests

MCP is bidirectional: the server can ask the client to do things the client is uniquely able to do, and the client answers with reverse-direction handlers. The server side issues the calls (`requestSampling` to have the client's model generate text, `requestElicitation` to have the client collect input from the user, `requestRoots` to ask which filesystem roots the client exposes). The client side answers them with `McpClientHandler` values passed to `McpClient.init`. The seam between the two is capability advertisement, validated when the client comes up.

A weather tool that wants the model to phrase the forecast asks for sampling from inside its handler:

```scala
val phrasedForecast =
    McpHandler.tool[WeatherIn]("weather.phrased", "A forecast phrased by the model") { in =>
        lookupForecast(in.city).map { f =>
            val req = McpServer.SamplingRequest.user(
                s"Phrase this forecast warmly: ${f.summary}, ${f.tempC}C",
                maxTokens = 200
            )
            Abort.run[McpRequestSamplingFailure](Mcp.requestSampling(req)).map {
                case Result.Success(resp) => f.copy(summary = resp.contentText.getOrElse(f.summary))
                case _                    => f
            }
        }
    }
```

The client answers `sampling/createMessage` by registering `McpClientHandler.onSampling` and advertising the matching capability:

```scala
val samplingAnswer =
    McpClientHandler.onSampling { req =>
        McpServer.SamplingResponse.assistant(
            McpContent.text("A balmy day to be outside."),
            model = Present("model-x"),
            stopReason = Present(McpServer.SamplingResponse.StopReason.EndTurn)
        )
    }

val samplingClient: McpClient < (Async & Scope & Abort[McpInitFailure]) =
    JsonRpcTransport.stdio().map { transport =>
        val caps = McpCapabilities.Client.of(McpCapabilities.Name.Sampling)
        McpClient.init(transport, McpInfo("weather-cli"), caps, samplingAnswer)
    }
```

> **Caution:** The reverse handler and the advertised capability must agree, and the agreement is checked at client init. Registering `onSampling` without `sampling` in the `McpCapabilities.Client` you pass to `McpClient.init` is rejected with `McpCapabilityNotAdvertisedException` (its `peer` is `Peer.Client`), and the client never comes up. `onSampling` requires `sampling`, `onElicitation` requires `elicitation`, `onRoots` requires `roots`; the notification sinks (`onLog`, `onResourceUpdated`, `onNotification`) require none.

The same reverse call is reachable two ways inside a handler. `Mcp.requestSampling(req)` is the direct accessor used above; it does not expose the `McpServer` handle, so the handle cannot leak into a detached fiber. `Mcp.server.map(_.requestSampling(req))` is the handle-exposing form, for a handler that needs the handle across several calls. Prefer the direct accessor.

### Elicitation

Elicitation asks the client to collect structured input from the user. `requestElicitationAs[A]` derives the requested JSON Schema from `A` and decodes the client's accepted payload back into an `ElicitationOutcome[A]`. With a `case class Confirm(approved: Boolean) derives Schema, CanEqual` as the requested shape, a tool can gate a costly lookup on the user's answer:

```scala
val elicitTool =
    McpHandler.tool[WeatherIn]("weather.confirm", "Confirm before a costly lookup") { in =>
        Mcp.server.map { server =>
            Abort.run[McpRequestElicitationAsFailure](server.requestElicitationAs[Confirm](s"Look up ${in.city}?")).map {
                case Result.Success(McpServer.ElicitationOutcome.Accept(c)) if c.approved => lookupForecast(in.city)
                case _                                                                    => Forecast(in.city, "declined", 0)
            }
        }
    }
```

The client answers `elicitation/create` with `McpClientHandler.onElicitation`, returning an `ElicitationResponse` whose `action` is `Accept`, `Decline`, or `Cancel`:

```scala
val elicitAnswer =
    McpClientHandler.onElicitation { req =>
        McpServer.ElicitationResponse.accept(Confirm(true))
    }
```

> **Caution:** With `requestElicitationAs[A]`, an `Accept` whose payload does not decode to `A`, or an `Accept` carrying no content at all, aborts `McpToolStructuredDecodeException`. The typed outcome is total over the three actions, but the decode of an accepted payload can still fail.

### Roots and notifications

`requestRoots` asks the client which filesystem roots it exposes; the client answers with `onRoots`, returning `Chunk[McpServer.Root]`. Beyond the request/response pairs, the server can fire one-way notifications: `notifyToolsListChanged`, `notifyResourcesListChanged`, `notifyResourceUpdated(uri)`, `notifyPromptsListChanged`, and the typed `notifyLog[T](level, data, logger)` for structured server-to-client logging. The client consumes the typed notifications with `onLog` (over `McpNotification.Log`) and `onResourceUpdated` (over `McpNotification.ResourceUpdated`); `onNotification[In](method)` is the untyped fire-and-forget sink for any other notification, and `customClient[In](method)` is the reverse-direction escape hatch for a vendor request method.

```scala
val rootsAnswer =
    McpClientHandler.onRoots {
        Chunk(McpServer.Root(McpResourceUri("file:///workspace")))
    }

val logSink =
    McpClientHandler.onLog { log =>
        Console.printLine(s"[${log.level}] server log")
    }
```

> **Note:** A notification sink's `Out` is structurally `Unit`: there is no `Schema[Out]` to fill, so a reply-bearing route for a notification will not type-check. The client cannot accidentally answer a fire-and-forget notification.

## Content and identifiers

Tool results, prompt messages, and sampling turns all carry the same content union, and every identifier on the public surface is a typed opaque newtype rather than a raw `String`. The newtypes share one convention: `parse` validates a user-supplied string and returns a `Maybe`, while `apply` is the trusted constructor for a value you already know is valid.

`McpContent` is the content union (`Text`, `Image`, `Audio`, `EmbeddedResource`, `ResourceLink`) with the factories `text`, `image`, `audio`, and `embedded`:

```scala
val summaryLeaf = McpContent.text("Clear, 21C")
val chartLeaf   = McpContent.image(renderChartPng(Forecast("Paris", "clear", 21)), png)
```

`McpContent.Role` is the conversation role (`User`, `Assistant`, `System`). `SamplingContent` is the restricted subset (`Text`, `Image`, `Audio` only) the MCP spec allows in sampling messages; `toMcpContent` widens a `SamplingContent` back to the full `McpContent`.

The identifiers:

```scala
val uri      = McpResourceUri.parse("weather:///readme")          // Maybe[McpResourceUri]
val template = McpResourceUri.Template.parse("weather:///{city}") // Maybe[Template]
val mime     = McpMimeType.parse("text/markdown")                 // Maybe[McpMimeType]
```

> **Note:** `parse` returns `Absent` rather than throwing: `McpResourceUri.parse` rejects blank or whitespace-only input, `Template.parse` requires at least one `{`, and `McpMimeType.parse` enforces the RFC 6838 grammar. Use `apply` only where the value is statically known good (a literal in your own code). `McpCursor` is the one identifier with no public constructor at all: the engine mints it into `Page.nextCursor` and you feed it back unchanged, so `asString` exists only for logging.

## Configuration

`McpConfig.default` is the starting point, and you override individual fields with `with*` setters; you reach for one when a default is wrong for your deployment. Pass the result through the curried `(transport, config)(handlers*)` overload of `init`.

```scala
val config =
    McpConfig.default
        .withServerInfo(McpInfo("weather-server", "1.2.0"))
        .withCapabilityGate(McpConfig.CapabilityGateMode.LogOnly)
        .withHandshakeTimeout(10.seconds)

val configured: Unit < (Async & Scope) =
    JsonRpcTransport.stdio().map { transport =>
        McpServer.initWith(transport, config)(weather)(_ => Async.never)
    }
```

The dials worth knowing:

- `withCapabilityGate(mode)` chooses how an inbound request for an unadvertised capability is handled: `RejectUnsupported` (the default) aborts it, `LogOnly` logs and proceeds, `Off` disables the gate.
- `withHandshakeOrder(order)` chooses whether the server waits for the client's `notifications/initialized` (`RequireInitializedNotification`, the default) or admits requests right after the `initialize` request (`RequireInitializeRequestOnly`).
- `withDeclaredCapabilities(caps)` turns off the default auto-derivation and makes you responsible for the advertisement (see the note below).
- `withJsonRpc(slot)` overrides the embedded JSON-RPC behaviour (cancellation, progress, the per-request dispatch timeout). The default sets no per-request timeout (`Duration.Infinity`), so a long-running tool is never cut off by the engine; override the slot only when you want a deadline.
- `withHandshakeTimeout`, `withInstructions`, `withSupportedProtocolVersions`, and `withAutoNotifyListChanged` set the remaining fields.

> **Note:** By default the server derives its advertised capabilities from the registered handlers: a `tool` handler advertises `tools`, a `subscribe = true` resource advertises `resources.subscribe`, and so on. With `RejectUnsupported`, an inbound method whose capability was not derived is rejected. `withDeclaredCapabilities(...)` disables that derivation, so it becomes your job to keep the advertised set matched to the handlers; a mismatch surfaces as `McpCapabilityNotAdvertisedException` at dispatch.

> **Caution:** `McpConfig.require` runs eagerly at every `init` call, throwing `IllegalArgumentException` for an empty `supportedProtocolVersions`, a non-positive `handshakeTimeout`, or an invalid JSON-RPC slot. The failure is at startup, not lazily on first dispatch. `withHandshakeTimeout` additionally clamps a non-positive duration up to `1.milli` at the setter, so the illegal state is hard to reach in the first place.

`McpConfig.ProtocolVersion` is the negotiated wire version (`current` is `2025-06-18`); like the other opaque identifiers it is constructed via `parse` against the supported set.

## Errors

Every protocol failure is an `McpException`, and the error model is per operation: each public method's `Abort` row is a precise sealed trait naming exactly the leaves that method can produce, so a `when` over the row is exhaustive and the compiler tells you the moment you miss a case. `callTool` aborts `McpCallToolFailure`, `readResource` aborts `McpReadResourceFailure`, `getPrompt` aborts `McpGetPromptFailure`, the `list*` methods abort `McpListFailure`, `complete` aborts `McpCompleteFailure`, the reverse calls abort `McpRequestSamplingFailure` / `McpRequestElicitationFailure` / `McpRequestRootsFailure`, and the eager handshake aborts `McpInitFailure`; the raw and checked lanes name their own (`McpCallToolRawFailure`, `McpGetPromptCheckedFailure`, and so on). A closed transport is not a separate `Closed` leaking through every row: it is the typed leaf `McpConnectionClosedException`, an `McpException` mixed into every operation trait, so the same case covers it everywhere. The notification and progress sinks, which can only ever fail by racing a closing transport, carry the single-leaf row `Abort[McpConnectionClosedException]`.

```scala
val handled: String < (Async & Scope & Abort[McpInitFailure]) =
    JsonRpcTransport.stdio().map { transport =>
        val info = McpInfo("weather-cli")
        val caps = McpCapabilities.Client()
        McpClient.initWith(transport, info, caps) { client =>
            Abort.recover[McpCallToolFailure] {
                case _: McpConnectionClosedException     => "transport closed"
                case e: McpToolStructuredDecodeException => s"bad shape: ${e.getMessage}"
                case e                                   => s"error: ${e.getMessage}"
            } {
                client.callTool[Forecast]("weather")(WeatherIn("Paris")).map(_.summary)
            }
        }
    }
```

> **Note:** A domain error the peer registered with `.error[E2]` arrives as `McpRemoteApplicationException`, with the `remoteCode`, `remoteMessage`, and `remoteData` triple preserved verbatim. `remoteCode` is the only stable discriminator for a user-defined error family, because the message and data are owned by the sender. This is how the `ForecastUnavailable` mapping from [Typed domain errors](#typed-domain-errors) reaches the caller: as a remote exception carrying code `-40001`.

The typed-decode failures are three distinct leaves: `McpToolStructuredMissingException` (no `structuredContent` for a typed call), `McpToolStructuredDecodeException` (present but undecodable), and `McpDecodeException` (a typed `_meta` or projection that did not conform). Keeping them apart is what lets a handler tell "the server sent nothing" from "the server sent the wrong shape," and each appears as a leaf of exactly the operation rows that can raise it.

### Catching Java throwables

[Typed domain errors](#typed-domain-errors) showed the back half of the loop: a handler aborts a typed `E2` and `.error[E2]` maps it to a wire code. This is the front half, the step a real handler hits first. Most tools wrap a synchronous host or library call (a file read, a `parseInt`, a JDBC query) that signals failure by throwing a Java `Throwable`, and kyo does not auto-convert an escaping throwable into a typed `Abort` row. An uncaught throw from a handler body is a panic: it surfaces at the engine's dispatch boundary, and because the panic is opaque, the client sees a bare `-32603` internal error rather than a code it can discriminate.

`Abort.catching[T]` narrows the throw to a typed `Abort[T]` row at the boundary, and `Abort.recover` (or a direct `Abort.fail`) converts that row into the same registered `.error[E2]` the typed-domain-error section installed:

```scala
val parsedForecast =
    McpHandler
        .tool[WeatherIn]("weather.parsed", "Forecast from an external reading") { in =>
            Abort.catching[NumberFormatException](Sync.defer(externalTempReading(in.city).toInt))
                .map(tempC => Forecast(in.city, "clear", tempC))
                .handle(Abort.recover[NumberFormatException](_ => Abort.fail(ForecastUnavailable(in.city))))
        }
        .error[ForecastUnavailable](code = -40001, message = "forecast-unavailable")
```

`externalTempReading(in.city).toInt` is the throwing Java call: `String.toInt` raises `NumberFormatException` on a non-numeric reading. Without the `Abort.catching`, that exception escapes as a `-32603` panic; with it, the throw becomes a typed row, `Abort.recover` rewrites it to `ForecastUnavailable`, and `.error[ForecastUnavailable]` stamps the wire code. The caller then receives the same `McpRemoteApplicationException(-40001, "forecast-unavailable", ...)` a deliberate `Abort.fail(ForecastUnavailable(...))` would have produced, completing the path from "Java throw" to "wire code the caller can match on."

> **Caution:** `Abort.catching[T]` narrows only `T` and its subtypes; any other throwable still escapes as a `-32603` panic. Name the exact exception the wrapped call documents (here `NumberFormatException`), and widen to `Abort.catching[Throwable]` only at a boundary that genuinely must map every failure to one wire code.

## Lifecycle

Both peers offer a scoped and an unscoped path. `init` (and `initWith`) is `Scope`-managed: the handle is released exactly once when the scope exits, which is what you want by default. `initUnscoped` (and `initUnscopedWith`) hands you a handle you own and must close yourself, ideally under `Scope.ensure`, for the rare case where the handle's lifetime exceeds any single scope; an unclosed unscoped handle leaks the reader/writer fibers and the transport on interrupt.

For manual close, there is a trio: `close` waits a 30-second grace period for in-flight requests to drain, `close(grace)` waits a bound you choose, and `closeNow` closes immediately. `awaitDrain` blocks until in-flight requests finish without closing.

```scala
val drainThenClose: Unit < (Async & Abort[McpException]) =
    JsonRpcTransport.inMemory.map { (serverTransport, clientTransport) =>
        McpServer.initUnscoped(serverTransport, weather).map { server =>
            McpClient.initUnscoped(clientTransport, McpInfo("weather-cli"), McpCapabilities.Client()).map { client =>
                client.callTool[Forecast]("weather")(WeatherIn("Paris")).map { _ =>
                    client.close(5.seconds).andThen(server.closeNow)
                }
            }
        }
    }
```

> **Caution:** `close` defaults to a 30-second grace period on purpose. The immediate `closeNow` (which is `close(Duration.Zero)`) fails every in-flight request with `McpConnectionClosedException` the instant it runs, so prefer the graceful `close` or a bounded `close(grace)` unless you genuinely need to drop everything now.

## Transports and cross-platform behaviour

kyo-mcp ships no transports of its own; it uses the `JsonRpcTransport` factories from `kyo-jsonrpc`. `stdio()` is the line-delimited stdin/stdout transport for a CLI server a host launches as a subprocess. `inMemory` returns a cross-wired pair, ideal for wiring a client and a server in one process (every example in this README that uses `inMemory` does exactly that). `fromWire(...)` lifts a byte-stream transport plus framer plus codec into the envelope seam for a custom wire.

The full surface compiles and runs on JVM, JavaScript, and Scala Native, with no platform restriction: `JsonRpcTransport.unixDomain(path)` works everywhere, running over the platform kyo-net transport (JVM posix/NIO, Native posix, JS/Wasm Node's `net` module). On JS/Wasm it needs a Node.js runtime, since a browser has no sockets.

## Putting it together

A single in-process wiring that exercises a tool, a resource, a typed prompt, the typed client lane, and a graceful shutdown:

```scala
val endToEnd: (Forecast, String) < (Async & Scope & Abort[McpException]) =
    val weatherTool =
        McpHandler.tool[WeatherIn]("weather", "Current conditions for a city") { in =>
            lookupForecast(in.city)
        }
    val readme =
        McpHandler.resource(McpResourceUri("weather:///readme"), "readme") {
            Chunk(McpHandler.ResourceBody.text("forecasts are wishful thinking"))
        }
    val explain =
        McpHandler.prompt[ExplainIn]("explain", "Explain a topic") { in =>
            McpHandler.PromptOutcome.of(
                Present(s"Explaining ${in.topic}"),
                McpHandler.PromptMessage.user(s"Explain ${in.topic}.")
            )
        }

    JsonRpcTransport.inMemory.map { (serverTransport, clientTransport) =>
        McpServer.init(serverTransport, weatherTool, readme, explain).map { _ =>
            McpClient.init(clientTransport, McpInfo("weather-cli"), McpCapabilities.Client()).map { client =>
                for
                    forecast <- client.callTool[Forecast]("weather")(WeatherIn("Paris"))
                    names    <- client.streamTools.map(_.name).run
                yield (forecast, names.mkString(","))
            }
        }
    }
end endToEnd
```
