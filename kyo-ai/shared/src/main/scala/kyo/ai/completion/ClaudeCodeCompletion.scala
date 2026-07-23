package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** Explicit opt-out of the subscription guarantee. Top-level in `kyo.ai.completion` (like the
  * `kyo.ai.provider` flag at `Config.scala`), so the fully-qualified name is the property key:
  * `-Dkyo.ai.completion.inheritApiCredentials=true` lets a command harness inherit the ambient
  * `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` / `CLAUDE_API_KEY` and bill through the API. Default `false`
  * strips them so the CLI uses its own OAuth subscription. The base URL is not a credential and rides
  * separately: `ANTHROPIC_BASE_URL` always passes through and a non-empty `Config.apiUrl` overrides it,
  * matching the native path. A resolve-once pure field read, so `claudeCommand` stays pure.
  */
private[kyo] object inheritApiCredentials extends StaticFlag[Boolean](false)

/** Claude Code command-backed completion adapter.
  *
  * Claude Code is driven through `claude -p` with SDK `stream-json` input and output. The leading system
  * message rides the CLI system prompt; the rest of the conversation is delivered as one stream-json user
  * event, since the CLI's stdin accepts only user-role messages (see [[ClaudeCodeWire.turnInput]]). Kyo
  * tools, the result tool included, are exposed as an ephemeral in-process MCP server, so the model receives
  * the same result contract the Anthropic backend sends: offered rather than forced. The result call ends the
  * turn: the MCP handler records the first call's arguments verbatim and signals the runner, which kills the
  * CLI process instead of waiting for the turn to end (mirroring the eval loop's stop at the first accepted
  * result). The captured arguments ride a result_tool call for the eval loop to decode; this adapter never
  * parses a tool payload. Streaming rides the same MCP result tool and kill-on-call and emits the captured
  * envelope as its single fragment. Encoding/decoding live in [[ClaudeCodeWire]]; this object owns only the
  * process and MCP orchestration.
  */
private[completion] object ClaudeCodeCompletion extends HarnessCompletion("Claude Code"):

    import ClaudeCodeWire.*

    private case class McpServerConfig(`type`: String, url: String, timeout: Int, alwaysLoad: Boolean) derives Schema
    private case class McpConfig(mcpServers: Map[String, McpServerConfig]) derives Schema
    private case class McpBridge(
        config: String,
        allowedTools: Chunk[String],
        executedTools: AtomicRef[Chunk[ExecutedTool]],
        resultCapture: AtomicRef[Maybe[String]],
        resultSignal: Fiber.Promise[Unit, Any]
    )

    // The subscription guarantee: these ambient API credentials are stripped by default so the CLI uses its
    // own OAuth login rather than silently diverting billing to a metered key; inheritApiCredentials is the
    // opt-out. The base URL is NOT here (routing, not a credential): claudeCommand carries a non-empty
    // Config.apiUrl as ANTHROPIC_BASE_URL and lets an ambient one pass through.
    private[completion] val strippedEnvVars =
        Set("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_API_KEY")

    private enum CliResult derives CanEqual:
        case Completed(out: Chunk[Byte], err: Chunk[Byte], code: Process.ExitCode)
        case Captured(out: Chunk[Byte])
        case TimedOut
    end CliResult

    // The CLI's WebSocket MCP client requests the "mcp" subprotocol and, per RFC 6455 section 4.1, MUST fail
    // the connection when the server's 101 does not select it. So the server must advertise "mcp" for the
    // handshake to echo it back; without this the CLI drops the socket after the upgrade and the bridge's
    // tools never reach the model.
    private val mcpWebSocketConfig = HttpWebSocket.Config(subprotocols = Seq("mcp"))

    override def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]],
        // The CLI wire carries no token counts, so the sink is never written; usage stays Absent by design.
        usageSink: AtomicRef[Maybe[Completion.Usage]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        // Streaming rides the SAME MCP result tool and kill-on-call as the completion path. No user tools
        // here, so the bridge threads no LLM.State (withResultBridge, the result-only variant of
        // withMcpBridge), which is what lets it run inside the Stream's element row (no LLM). The captured
        // envelope is emitted as the single fragment; a resultless turn ends the stream with
        // AIStreamIncompleteException (no eval-loop repair on this path). The AIGenException->AIStreamException
        // mapping is in the recover below.
        turnInput(context).map { input =>
            Stream[String, Async & Scope & Abort[AIStreamException]] {
                // Map bridge/runner AIGenException failures to AIStreamException. An AIStreamException (a
                // resultless-turn AIStreamIncompleteException, or a classified provider leaf) is re-raised
                // type-preserving; only a genuinely-unclassified AIGenException wraps as a harness malfunction
                // (status Absent). Preserves the halt/retry policy.
                Abort.recover[AIGenException] {
                    case e: AIStreamException => Abort.fail(e)
                    case e                    => Abort.fail(streamFailure(Absent, e.getMessage))
                } {
                    withResultBridge(config, resultTool, resultSchema) { bridge =>
                        runClaudeCommand(
                            config,
                            claudeCommand(config, commandArgs(config, context, bridge.config, bridge.allowedTools))
                                .stdin(input + "\n"),
                            config.timeout,
                            bridge
                        ).andThen(bridge.resultCapture.get).map {
                            case Present(envelope) => Emit.value(Chunk(envelope))
                            case Absent            => Abort.fail(AIStreamIncompleteException(""))
                        }
                    }
                }
            }
        }
    end streamFragments

    protected def run(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
        Scope.run {
            withMcpBridge(config, tools, resultSchema) { bridge =>
                for
                    input <- turnInput(context)
                    _ <- Log.debug(
                        s"kyo-ai Claude Code mode=result messages=${context.compacted.size} tools=${tools.map(_.name).mkString(",")}"
                    )
                    raw <- runClaudeCommand(
                        config,
                        claudeCommand(config, commandArgs(config, context, bridge.config, bridge.allowedTools))
                            .stdin(input + "\n"),
                        config.timeout,
                        bridge
                    )
                    executed <- bridge.executedTools.get
                    captured <- bridge.resultCapture.get
                    // A per-generation unique token for the wire-minted call ids (captured result,
                    // lost-event executed tool), so replaying a CC-built context through an HTTP wire never
                    // carries duplicate tool_use ids across generations.
                    callIdSeed <- Random.nextStringAlphanumeric(12)
                    messages   <- readMessages(raw, executed, captured, callIdSeed)
                yield messages
            }
        }
    end run

    /** The CLI invocation, carrying [[Config.reasoningBudget]] as `MAX_THINKING_TOKENS`, the effective output
      * cap as `CLAUDE_CODE_MAX_OUTPUT_TOKENS`, and a non-empty [[Config.apiUrl]] as `ANTHROPIC_BASE_URL`.
      *
      * Without MAX_THINKING_TOKENS the CLI picks its own budget and the configured value is silently inert
      * (the env var is verified to set `thinking.budget_tokens` on the wire). Reasoning OFF is a separate
      * state, stated explicitly for the same reason: left unsaid, the CLI reasons under a ceiling sized for a
      * reply that does not reason. CLAUDE_CODE_MAX_OUTPUT_TOKENS carries this config's own ceiling, sized from
      * its entry (not shared across backends); the harness applies it to EACH internal attempt and retries a
      * reply that reaches it, so a colliding invocation spends several times the ceiling before it reports.
      * `apiUrl` here is the bare base (the CLI appends its own `/v1/...`), unlike the Anthropic provider whose
      * default embeds `/v1`; nothing is set unless the user configures a URL, and an ambient ANTHROPIC_BASE_URL
      * passes through (routing is not a credential).
      */
    private[completion] def claudeCommand(config: Config, args: Chunk[String]): Command =
        val env = Map(
            "ENABLE_TOOL_SEARCH"            -> "false",
            "CLAUDE_CODE_MAX_OUTPUT_TOKENS" -> config.effectiveMaxOutputTokens.toString,
            // Essential traffic only: without this the CLI issues a second, separately-billed session-title
            // request alongside every generation (verified live), so this path and the native one would
            // differ in request count and cost. Telemetry rides the same switch.
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" -> "1",
            // Hermeticity: the CLI auto-loads the cwd's project memory into the session even with
            // --setting-sources "" (verified live). The Anthropic path never sees that ambient content, so a
            // generation would diverge by whatever the user's project memory holds. This switch removes the
            // injection, the memory_paths advertisement, and the CLI's memory writes, keeping the session's
            // context exactly the kyo-built one.
            "CLAUDE_CODE_DISABLE_AUTO_MEMORY" -> "1"
        ) ++ config.resolvedAmount.fold(
            // Turning reasoning off has to be said out loud: with neither variable set the harness keeps its
            // own default and reasons anyway while the ceiling shrinks to the no-reasoning size, the pairing
            // that stops a reply before it produces anything (verified live: ~threefold fewer output tokens).
            // This is the switch encoding of off, so it rides only where the entry's encoding says reasoning
            // exists to turn off.
            if config.reasoningEnabled then Map.empty[String, String]
            else
                config.reasoningOff match
                    case Config.ReasoningOff.EnvSwitch => Map("CLAUDE_CODE_DISABLE_THINKING" -> "1")
                    case _                             => Map.empty[String, String]
        ) {
            case Config.Amount.Budget(tokens) => Map("MAX_THINKING_TOKENS" -> tokens.toString)
            // A level reaches this arm only through an entry declared graded on this harness, which mis-states
            // it: this harness takes a token budget and nothing else. Nothing rides and no warn covers it (the
            // statement matches the declared encoding); the declaration is what is wrong, and fixing it is the fix.
            case Config.Amount.Level(_) => Map.empty[String, String]
        }
            ++ (if config.apiUrl.nonEmpty then Map("ANTHROPIC_BASE_URL" -> config.apiUrl) else Map.empty[String, String])
        val base = Command(args*).envAppend(env)
        // Subscription guarantee: strip ambient API credentials by default so the CLI uses its own OAuth
        // login; inheritApiCredentials is the opt-out that lets it inherit the ambient key.
        if inheritApiCredentials() then base else base.envRemove(strippedEnvVars)
    end claudeCommand

    private def runClaudeCommand(
        config: Config,
        command: Command,
        timeout: Duration,
        bridge: McpBridge
    )(using Frame): String < (Async & Abort[AIGenException]) =
        Abort.run[CommandException] {
            Scope.run {
                for
                    proc      <- command.spawn
                    outputFib <- Fiber.init(Scope.run(proc.collectOutput))
                    // Kill the CLI the moment the MCP result handler captures the call arguments: the result
                    // contract ends the turn, as the eval loop stops at the first accepted result on the HTTP
                    // path. The fiber is Scope-managed, so a turn that ends without a result interrupts it.
                    _        <- Fiber.init(bridge.resultSignal.get.andThen(proc.destroyForcibly))
                    code     <- proc.waitFor(timeout)
                    captured <- bridge.resultCapture.get
                    result <- captured match
                        case Present(_) =>
                            // Result captured: the exit code is the kill's, not a failure. The kill here is
                            // idempotent cover for the signal fiber racing this read. Collect the stdout that
                            // made it out as best-effort transcript, but bound the drain: a descendant process
                            // holding the inherited stdout pipe would hang this success path forever. A short
                            // cap degrades to empty output, which readMessages tolerates.
                            proc.destroyForcibly.andThen(
                                Abort.run[Timeout](Async.timeout(1.second)(outputFib.get)).map {
                                    case Result.Success((out, _)) => CliResult.Captured(out)
                                    case _                        => CliResult.Captured(Chunk.empty)
                                }
                            )
                        case Absent =>
                            code match
                                case Present(code) =>
                                    outputFib.get.map { case (out, err) => CliResult.Completed(out, err, code) }
                                case Absent =>
                                    proc.destroyForcibly.andThen(proc.waitFor(5.seconds)).andThen(Kyo.lift(CliResult.TimedOut))
                yield result
            }
        }.map {
            case Result.Success(CliResult.Captured(outBytes)) =>
                String(outBytes.toArray)
            case Result.Success(CliResult.Completed(outBytes, _, code)) if code.isSuccess =>
                String(outBytes.toArray)
            case Result.Success(CliResult.Completed(outBytes, errBytes, code)) =>
                val out = String(outBytes.toArray)
                val err = String(errBytes.toArray)
                // The --max-turns 2 cap (see ClaudeCodeWire.baseCliArgs) ends a resultless invocation with
                // error_max_turns and a non-zero exit: this backend's normal turn boundary, not a failure, so
                // the transcript flows to readMessages and the eval loop replays and iterates (how the forced
                // turn stays reachable). Every other non-zero exit classifies from the terminal result event's
                // structured api_error_status (via failureStatus), never by regexing stdout. No structured
                // status (a hard crash with no result event) -> Absent -> AIHarnessException.
                endedAtTurnCap(out).map {
                    case true  => Kyo.lift[String, Any](out)
                    case false =>
                        // Stopping at the output ceiling is a normal outcome the CLI reports in its own field,
                        // so it is recognized before the unexplained-failure path runs; otherwise it arrives as
                        // a harness malfunction carrying the whole transcript, naming neither cause nor fix.
                        stoppedAtOutputLimit(out).map {
                            case true =>
                                Abort.fail(AIOutputLimitException(
                                    config.provider.name,
                                    config.modelName,
                                    Present(config.effectiveMaxOutputTokens)
                                ))
                            case false =>
                                failureStatus(out).map(status =>
                                    Abort.fail(commandFailure(status, s"exited with ${code.toInt}: $out$err"))
                                )
                        }
                }
            case Result.Success(CliResult.TimedOut) =>
                Abort.fail(AICompletionTimeoutException("Claude Code", timeout))
            case Result.Failure(ex) =>
                // A spawn/IO failure (missing binary, killed shell): no structured status -> broken harness.
                Abort.fail(commandFailure(Absent, ex.getMessage))
            case Result.Panic(ex) =>
                Abort.panic(ex)
        }
    end runClaudeCommand

    /** The tool set exposed as plain per-tool MCP handlers: every tool except the result tool, which
      * rides [[resultToolHandler]] instead so it never double-registers as a user tool.
      */
    private[completion] def userToolInfos(tools: Chunk[Tool.internal.Info[?, ?, LLM]]): Chunk[Tool.internal.Info[?, ?, LLM]] =
        tools.filterNot(_.name == Completion.resultToolName)

    /** The in-process MCP server exposing kyo's tools to the CLI, the result tool included.
      *
      * User tools execute in-process, threading `LLM.State` through an `AtomicRef` under a mutex.
      * The result tool executes nothing: its handler records the first call's arguments verbatim and
      * completes the kill signal, taking the same mutex first so a user tool that is mid-run
      * finishes and commits its state before the process dies. Later result calls leave the first
      * capture in place, matching the eval loop's set-once result capture.
      */
    private def withMcpBridge[A, S](
        config: Config,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(body: McpBridge => A < S)(using Frame): A < (S & LLM & Async & Scope & Abort[AIGenException]) =
        val userTools = userToolInfos(tools)
        for
            state         <- LLM.state
            stateRef      <- AtomicRef.init(state)
            executed      <- AtomicRef.init(Chunk.empty[ExecutedTool])
            resultCapture <- AtomicRef.init(Maybe.empty[String])
            resultSignal  <- Fiber.Promise.init[Unit, Any]
            meter         <- Meter.initMutex
            handlers = userTools.map(tool => mcpToolHandler(stateRef, executed, meter, tool))
                .append(resultToolHandler(
                    resultCapture,
                    resultSignal,
                    meter,
                    resultDescription(tools),
                    StrictSchema.requireAll(resultSchema)
                ))
            server <- Abort.run[HttpBindException] {
                HttpServer.init(0, "127.0.0.1")(
                    HttpHandler.webSocket("mcp", mcpWebSocketConfig) { (_, ws) =>
                        Scope.run {
                            JsonRpcHttpTransport.webSocket(ws).map { transport =>
                                McpServer.initWith(transport, handlers.toSeq*) { _ =>
                                    ws.onPeerClose
                                }
                            }
                        }
                    }
                )
            }.map {
                case Result.Success(bound) => bound
                case Result.Failure(bindEx) =>
                    Abort.fail(AIProviderUnavailableException(
                        "Claude Code",
                        s"failed to bind the MCP bridge server: ${bindEx.getMessage}"
                    ))
                case Result.Panic(ex) => Abort.panic(ex)
            }
            url     = s"ws://127.0.0.1:${server.port}/mcp"
            timeout = math.min(Int.MaxValue.toLong, math.max(1000L, config.timeout.toMillis)).toInt
            bridge = McpBridge(
                Json.encode(McpConfig(Map(mcpServerName -> McpServerConfig("ws", url, timeout, alwaysLoad = true)))),
                userTools.map(_.name).append(Completion.resultToolName).map(name => s"$mcpToolPrefix$name"),
                executed,
                resultCapture,
                resultSignal
            )
            result <- body(bridge)
            next   <- stateRef.get
            _      <- LLM.setState(next)
        yield result
        end for
    end withMcpBridge

    /** The result-only MCP bridge for the STREAMING path. Streaming carries no user tools, so this
      * advertises just the result tool and threads no `LLM.State`; that missing `LLM` row is what lets
      * it run inside the Stream's element row (`Async & Scope`), where the completion path's
      * `withMcpBridge` (which reads `LLM.state`) cannot. The server/handler/capture/signal shape is the
      * same; the server-init block is duplicated from `withMcpBridge`.
      */
    private def withResultBridge[A, S](
        config: Config,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(body: McpBridge => A < S)(using Frame): A < (S & Async & Scope & Abort[AIGenException]) =
        for
            executed      <- AtomicRef.init(Chunk.empty[ExecutedTool])
            resultCapture <- AtomicRef.init(Maybe.empty[String])
            resultSignal  <- Fiber.Promise.init[Unit, Any]
            meter         <- Meter.initMutex
            handlers = Chunk(resultToolHandler(
                resultCapture,
                resultSignal,
                meter,
                resultDescription(resultTool),
                StrictSchema.requireAll(resultSchema)
            ))
            server <- Abort.run[HttpBindException] {
                HttpServer.init(0, "127.0.0.1")(
                    HttpHandler.webSocket("mcp", mcpWebSocketConfig) { (_, ws) =>
                        Scope.run {
                            JsonRpcHttpTransport.webSocket(ws).map { transport =>
                                McpServer.initWith(transport, handlers.toSeq*) { _ =>
                                    ws.onPeerClose
                                }
                            }
                        }
                    }
                )
            }.map {
                case Result.Success(bound) => bound
                case Result.Failure(bindEx) =>
                    Abort.fail(AIProviderUnavailableException(
                        "Claude Code",
                        s"failed to bind the MCP bridge server: ${bindEx.getMessage}"
                    ))
                case Result.Panic(ex) => Abort.panic(ex)
            }
            url     = s"ws://127.0.0.1:${server.port}/mcp"
            timeout = math.min(Int.MaxValue.toLong, math.max(1000L, config.timeout.toMillis)).toInt
            bridge = McpBridge(
                Json.encode(McpConfig(Map(mcpServerName -> McpServerConfig("ws", url, timeout, alwaysLoad = true)))),
                Chunk(s"$mcpToolPrefix${Completion.resultToolName}"),
                executed,
                resultCapture,
                resultSignal
            )
            result <- body(bridge)
        yield result
    end withResultBridge

    /** The result tool's model-visible description: the one the eval loop's result tool declares, so
      * both backends advertise identical text.
      */
    private def resultDescription(tools: Chunk[Tool.internal.Info[?, ?, LLM]])(using Frame): String =
        Maybe.fromOption(tools.collectFirst { case t if t.name == Completion.resultToolName => t.description })
            .getOrElse(Tool.internal.resultToolDefinition.infos.head.description)

    /** The MCP handler for the result tool. Advertises kyo's result-envelope schema and description
      * (constructed with an explicit ToolMeta because the schema is a runtime value, not a derived
      * `Schema[In]`), decodes the call arguments only as an opaque value tree, and records the first
      * call's raw JSON. It executes nothing: decoding, conformance, and repair belong to the eval
      * loop, which receives the captured arguments as a result_tool call.
      */
    private def resultToolHandler(
        capture: AtomicRef[Maybe[String]],
        signal: Fiber.Promise[Unit, Any],
        meter: Meter,
        description: String,
        schema: JsonSchema
    )(using Frame): McpHandler[?, ?, ?] =
        new McpHandler.ToolMultiHandler[Structure.Value, Nothing](
            McpHandler.ToolMeta(
                name = Completion.resultToolName,
                description = Maybe.when(description.nonEmpty)(description),
                inputSchema = schema,
                outputSchema = Absent,
                annotations = Absent
            ),
            summon[Schema[Structure.Value]],
            input =>
                Abort.run[Closed] {
                    meter.run {
                        val raw = Json.encode(input)
                        capture.getAndUpdate(prev => if prev.isEmpty then Present(raw) else prev).map { prev =>
                            signal.completeUnitDiscard.andThen {
                                if prev.isEmpty then
                                    McpHandler.ToolOutcome.ok(McpContent.text("Result received."))
                                else
                                    McpHandler.ToolOutcome.ok(McpContent.text(
                                        "A result was already recorded; only the first invocation is considered."
                                    ))
                            }
                        }
                    }
                }.map {
                    case Result.Success(outcome) => outcome
                    case _                       => McpHandler.ToolOutcome.error(toolError(Completion.resultToolName, "bridge closed"))
                },
            Chunk.empty
        )
    end resultToolHandler

    private def mcpToolHandler(
        stateRef: AtomicRef[LLM.State],
        executedTools: AtomicRef[Chunk[ExecutedTool]],
        meter: Meter,
        tool: Tool.internal.Info[?, ?, LLM]
    )(using Frame): McpHandler[?, ?, ?] =
        given Schema[Any] = tool.inputSchema.asInstanceOf[Schema[Any]]
        McpHandler.toolRaw[Any](tool.name, tool.description) { input =>
            Abort.run[Closed] {
                meter.run {
                    stateRef.get.map { state =>
                        Abort.run[Throwable] {
                            val run = tool.run.asInstanceOf[Any => Any < LLM](input)
                            LLM.runWith(state)(run) { (next, out) =>
                                (next, out)
                            }
                        }.map {
                            case Result.Success((next, out)) =>
                                val outputSchema = tool.outputSchema.asInstanceOf[Schema[Any]]
                                val text         = Json.encode[Any](out)(using outputSchema, summon[Frame])
                                val structured   = Structure.encode[Any](out)(using outputSchema)
                                val arguments    = Structure.encode[Any](input)(using tool.inputSchema.asInstanceOf[Schema[Any]])
                                stateRef.set(next).andThen(executedTools.getAndUpdate(_.append(ExecutedTool(
                                    tool.name,
                                    arguments,
                                    text
                                ))).unit).andThen(
                                    McpHandler.ToolOutcome.okWith(
                                        content = Chunk(McpContent.text(text)),
                                        structuredContent = Present(structured)
                                    )
                                )
                            case Result.Failure(ex) =>
                                val text      = toolError(tool.name, ex.toString)
                                val arguments = Structure.encode[Any](input)(using tool.inputSchema.asInstanceOf[Schema[Any]])
                                executedTools.getAndUpdate(_.append(ExecutedTool(tool.name, arguments, text))).unit.andThen(
                                    McpHandler.ToolOutcome.error(text)
                                )
                            case Result.Panic(ex) =>
                                val text      = toolError(tool.name, ex.toString)
                                val arguments = Structure.encode[Any](input)(using tool.inputSchema.asInstanceOf[Schema[Any]])
                                executedTools.getAndUpdate(_.append(ExecutedTool(tool.name, arguments, text))).unit.andThen(
                                    McpHandler.ToolOutcome.error(text)
                                )
                        }
                    }
                }
            }.map {
                case Result.Success(outcome) => outcome
                case Result.Failure(ex)      => McpHandler.ToolOutcome.error(toolError(tool.name, ex.toString))
                case Result.Panic(ex)        => McpHandler.ToolOutcome.error(toolError(tool.name, ex.toString))
            }
        }
    end mcpToolHandler

    private def toolError(name: String, detail: String): String =
        p"""
            Tool '$name' failed:
            $detail
        """
    end toolError

end ClaudeCodeCompletion
