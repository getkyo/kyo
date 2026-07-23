package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** Claude Code command-backed completion adapter.
  *
  * Claude Code is driven through `claude -p` with SDK `stream-json` input and output. System messages are
  * passed as the CLI system prompt, and every non-system Kyo message is encoded as one stream-json event
  * using Claude's native content blocks: text, image, tool_use, and tool_result. Kyo tools are exposed to
  * Claude Code as an ephemeral in-process MCP server, so the CLI can use native tool execution while the
  * adapter folds the resulting transcript back into ordinary Kyo messages.
  */
private[completion] object ClaudeCodeCompletion extends HarnessCompletion("Claude Code"):

    private case class InputSource(`type`: String, media_type: String, data: String) derives Schema
    private case class InputContent(
        `type`: String,
        text: Maybe[String] = Absent,
        id: Maybe[String] = Absent,
        name: Maybe[String] = Absent,
        input: Maybe[Structure.Value] = Absent,
        tool_use_id: Maybe[String] = Absent,
        content: Maybe[String] = Absent,
        source: Maybe[InputSource] = Absent,
        is_error: Maybe[Boolean] = Absent
    ) derives Schema
    private case class InputMessage(role: String, content: List[InputContent]) derives Schema
    private case class InputEvent(`type`: String, message: InputMessage) derives Schema

    private case class MessageContent(
        `type`: String,
        id: Maybe[String] = Absent,
        text: Maybe[String] = Absent,
        name: Maybe[String] = Absent,
        input: Maybe[Structure.Value] = Absent,
        tool_use_id: Maybe[String] = Absent,
        content: Maybe[String] = Absent,
        is_error: Maybe[Boolean] = Absent
    ) derives Schema
    private case class OutputMessage(role: String, content: List[MessageContent]) derives Schema
    private case class OutputEvent(
        `type`: String,
        message: Maybe[OutputMessage] = Absent,
        result: Maybe[String] = Absent,
        subtype: Maybe[String] = Absent
    ) derives Schema

    private case class McpServerConfig(`type`: String, url: String, timeout: Int, alwaysLoad: Boolean) derives Schema
    private case class McpConfig(mcpServers: Map[String, McpServerConfig]) derives Schema
    private case class McpBridge(config: String, allowedTools: Chunk[String], executedTools: AtomicRef[Chunk[ExecutedTool]])
    private case class ExecutedTool(name: String, arguments: Structure.Value, output: String)

    private val apiKeyEnvVars =
        Set("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CLAUDE_API_KEY")
    private val mcpServerName = "kyo"
    private val mcpToolPrefix = s"mcp__${mcpServerName}__"

    private enum CliResult derives CanEqual:
        case Completed(out: Chunk[Byte], err: Chunk[Byte], code: Process.ExitCode)
        case TimedOut

    override def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        turnInput(context).map { case (input, prompt) =>
            Stream[String, Async & Scope & Abort[AIStreamException]] {
                Abort.run[CommandException] {
                    claudeCommand(streamCommandArgs(config, context, resultSchema, prompt))
                        .stdin(input + "\n")
                        .spawn
                }.map {
                    case Result.Success(proc) => emitProcessFragments(proc)
                    case Result.Failure(ex)   => Abort.fail(streamFailure(ex.getMessage))
                    case Result.Panic(ex)     => Abort.panic(ex)
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
            val userTools = tools.filterNot(_.name == Completion.resultToolName)
            withMcpBridge(config, userTools) { bridge =>
                for
                    (input, prompt) <- turnInput(context)
                    schema = Structure.encode(resultSchema)
                    _ <- Log.debug(
                        s"kyo-ai Claude Code mode=result messages=${context.compacted.size} tools=${tools.map(_.name).mkString(",")}"
                    )
                    raw      <- runCli(config, context, schema, input, prompt, bridge)
                    executed <- bridge.map(_.executedTools.get).getOrElse(Kyo.lift(Chunk.empty))
                    messages <- readMessages(raw, executed)
                yield messages
            }
        }
    end run

    private def runCli(
        config: Config,
        context: Context,
        schema: Structure.Value,
        input: String,
        prompt: Maybe[String],
        bridge: Maybe[McpBridge]
    )(using Frame): String < (Async & Abort[AIGenException]) =
        runClaudeCommand(claudeCommand(commandArgs(config, context, schema, prompt, bridge)).stdin(input + "\n"), config.timeout)
    end runCli

    private def claudeCommand(args: Chunk[String]): Command =
        Command(args*).envRemove(apiKeyEnvVars).envAppend(Map("ENABLE_TOOL_SEARCH" -> "false"))

    private def runClaudeCommand(command: Command, timeout: Duration)(using Frame): String < (Async & Abort[AIGenException]) =
        Abort.run[CommandException] {
            Scope.run {
                for
                    proc      <- command.spawn
                    outputFib <- Fiber.init(Scope.run(proc.collectOutput))
                    code      <- proc.waitFor(timeout)
                    result <- code match
                        case Present(code) =>
                            outputFib.get.map { case (out, err) => CliResult.Completed(out, err, code) }
                        case Absent =>
                            proc.destroyForcibly.andThen(proc.waitFor(5.seconds)).andThen(Kyo.lift(CliResult.TimedOut))
                yield result
            }
        }.map {
            case Result.Success(CliResult.Completed(outBytes, _, code)) if code.isSuccess =>
                String(outBytes.toArray)
            case Result.Success(CliResult.Completed(outBytes, errBytes, code)) =>
                val out = String(outBytes.toArray)
                val err = String(errBytes.toArray)
                Abort.fail(commandFailure(s"exited with ${code.toInt}: $out$err"))
            case Result.Success(CliResult.TimedOut) =>
                Abort.fail(AIProviderUnavailableException("Claude Code", "turn timed out waiting for CLI completion"))
            case Result.Failure(ex) =>
                Abort.fail(commandFailure(ex.getMessage))
            case Result.Panic(ex) =>
                Abort.panic(ex)
        }
    end runClaudeCommand

    private def withMcpBridge[A, S](
        config: Config,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(body: Maybe[McpBridge] => A < S)(using Frame): A < (S & LLM & Async & Scope & Abort[AIGenException]) =
        if tools.isEmpty then body(Absent)
        else
            for
                state    <- LLM.state
                stateRef <- AtomicRef.init(state)
                executed <- AtomicRef.init(Chunk.empty[ExecutedTool])
                meter    <- Meter.initMutex
                handlers = tools.map(tool => mcpToolHandler(stateRef, executed, meter, tool))
                server <- HttpServer.init(0, "127.0.0.1")(
                    HttpHandler.webSocket("mcp") { (_, ws) =>
                        Scope.run {
                            JsonRpcHttpTransport.webSocket(ws).map { transport =>
                                McpServer.initWith(transport, handlers.toSeq*) { _ =>
                                    ws.onPeerClose
                                }
                            }
                        }
                    }
                )
                url     = s"ws://127.0.0.1:${server.port}/mcp"
                timeout = math.min(Int.MaxValue.toLong, math.max(1000L, config.timeout.toMillis)).toInt
                bridge = McpBridge(
                    Json.encode(McpConfig(Map(mcpServerName -> McpServerConfig("ws", url, timeout, alwaysLoad = true)))),
                    tools.map(tool => s"$mcpToolPrefix${tool.name}"),
                    executed
                )
                result <- body(Present(bridge))
                next   <- stateRef.get
                _      <- LLM.setState(next)
            yield result
        end if
    end withMcpBridge

    private def mcpToolHandler(
        stateRef: AtomicRef[LLM.State],
        executedTools: AtomicRef[Chunk[ExecutedTool]],
        meter: Meter,
        tool: Tool.internal.Info[?, ?, LLM]
    )(using Frame): McpHandler[?, ?, ?] =
        // Skolem-opens the existential Info[?, ?, LLM] by matching it against its own case class
        // shape; in/out bind fresh type variables so every Schema below is the concrete Schema[in]/
        // Schema[out] info itself carries, with no cast anywhere in the handler body.
        tool match
            case info: Tool.internal.Info[in, out, LLM] =>
                given Schema[in] = info.inputSchema
                McpHandler.toolRaw[in](info.name, info.description) { input =>
                    Abort.run[Closed] {
                        meter.run {
                            stateRef.get.map { state =>
                                Abort.run[Throwable] {
                                    LLM.runWith(state)(info.run(input)) { (next, out) =>
                                        (next, out)
                                    }
                                }.map {
                                    case Result.Success((next, out)) =>
                                        val text       = Json.encode(out)(using info.outputSchema, summon[Frame])
                                        val structured = Structure.encode(out)(using info.outputSchema)
                                        val arguments  = Structure.encode(input)(using info.inputSchema)
                                        stateRef.set(next).andThen(executedTools.getAndUpdate(_.append(ExecutedTool(
                                            info.name,
                                            arguments,
                                            text
                                        ))).unit).andThen(
                                            McpHandler.ToolOutcome.okWith(
                                                content = Chunk(McpContent.text(text)),
                                                structuredContent = Present(structured)
                                            )
                                        )
                                    case Result.Failure(ex) =>
                                        val text      = toolError(info.name, ex.toString)
                                        val arguments = Structure.encode(input)(using info.inputSchema)
                                        executedTools.getAndUpdate(_.append(ExecutedTool(info.name, arguments, text))).unit.andThen(
                                            McpHandler.ToolOutcome.error(text)
                                        )
                                    case Result.Panic(ex) =>
                                        val text      = toolError(info.name, ex.toString)
                                        val arguments = Structure.encode(input)(using info.inputSchema)
                                        executedTools.getAndUpdate(_.append(ExecutedTool(info.name, arguments, text))).unit.andThen(
                                            McpHandler.ToolOutcome.error(text)
                                        )
                                }
                            }
                        }
                    }.map {
                        case Result.Success(outcome) => outcome
                        case Result.Failure(ex)      => McpHandler.ToolOutcome.error(toolError(info.name, ex.toString))
                        case Result.Panic(ex)        => McpHandler.ToolOutcome.error(toolError(info.name, ex.toString))
                    }
                }
    end mcpToolHandler

    private def toolError(name: String, detail: String): String =
        p"""
            Tool '$name' failed:
            $detail
        """
    end toolError

    private def commandArgs(
        config: Config,
        context: Context,
        schema: Structure.Value,
        prompt: Maybe[String],
        bridge: Maybe[McpBridge]
    )(using Frame): Chunk[String] =
        val base =
            Chunk(
                "claude",
                "-p",
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--verbose",
                "--no-session-persistence",
                "--tools",
                "",
                "--disable-slash-commands",
                "--strict-mcp-config",
                "--mcp-config",
                bridge.map(_.config).getOrElse("""{"mcpServers":{}}"""),
                "--disallowedTools",
                "ToolSearch",
                "--setting-sources",
                "",
                "--permission-mode",
                "dontAsk",
                "--model",
                config.modelName,
                "--json-schema",
                Json.encode(schema)
            )
        val withAllowed =
            bridge match
                case Present(value) if value.allowedTools.nonEmpty =>
                    base.append("--allowedTools").append(value.allowedTools.mkString(","))
                case _ =>
                    base
        val system     = outputInstructions(context, schema, bridge.nonEmpty)
        val withSystem = if system.isEmpty then withAllowed else withAllowed.append("--system-prompt").append(system)
        prompt match
            case Present(value) => withSystem.append(value)
            case Absent         => withSystem
    end commandArgs

    private def streamCommandArgs(config: Config, context: Context, resultSchema: JsonSchema, prompt: Maybe[String])(using
        Frame
    ): Chunk[String] =
        val base =
            Chunk(
                "claude",
                "-p",
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--verbose",
                "--no-session-persistence",
                "--tools",
                "",
                "--disable-slash-commands",
                "--strict-mcp-config",
                "--mcp-config",
                """{"mcpServers":{}}""",
                "--disallowedTools",
                "ToolSearch",
                "--setting-sources",
                "",
                "--permission-mode",
                "dontAsk",
                "--model",
                config.modelName,
                "--json-schema",
                Json.encode(resultSchema)
            )
        val system     = streamInstructions(context, resultSchema)
        val withSystem = if system.isEmpty then base else base.append("--system-prompt").append(system)
        prompt match
            case Present(value) => withSystem.append(value)
            case Absent         => withSystem
    end streamCommandArgs

    private def turnInput(context: Context)(using Frame): (String, Maybe[String]) < Abort[AIGenException] =
        val messages = context.compacted.filterNot(_.role == Role.System)
        val input =
            messages.lastMaybe match
                case Present(_: UserMessage) => messages
                case Present(_)              => messages.append(UserMessage("Continue.", Absent))
                case Absent                  => Chunk(UserMessage("Continue.", Absent))
        inputJsonLines(input).map(_ -> Absent)
    end turnInput

    private def streamInstructions(context: Context, resultSchema: JsonSchema)(using Frame): String =
        val instruction =
            p"""
                Answer only the final user message. Earlier user messages are conversation history, not active requests.
                Never replay, concatenate, summarize, or include earlier assistant outputs in the new result.
                The result value must contain only the answer requested by the final user message.
                For streaming string results, output exactly the string requested by the final user message and no prior history text.
                Return only JSON matching this result envelope schema:
                ${Json.encode(resultSchema)}
            """
        (context.compacted.collect { case SystemMessage(content, _, _) => content } :+ instruction).mkString("\n\n")
    end streamInstructions

    private def outputInstructions(context: Context, schema: Structure.Value, hasTools: Boolean)(using Frame): String =
        val instruction =
            if hasTools then
                p"""
                    Answer only the final user message. Earlier user messages are conversation history, not active requests.
                    Never replay, concatenate, summarize, or include earlier assistant outputs in the new result.
                    Return only JSON matching this output schema:
                    ${Json.encode(schema)}
                    Use the available MCP tools when they are needed to answer the user.
                """
            else
                p"""
                    Answer only the final user message. Earlier user messages are conversation history, not active requests.
                    Never replay, concatenate, summarize, or include earlier assistant outputs in the new result.
                    Return only JSON matching this result envelope schema:
                    ${Json.encode(schema)}
                """
        (context.compacted.collect { case SystemMessage(content, _, _) => content } :+ instruction).mkString("\n\n")
    end outputInstructions

    private[kyo] def inputJsonLines(messages: Chunk[Message])(using Frame): String < Abort[AIGenException] =
        inputEvents(messages).map(_.map(Json.encode(_)).mkString("\n"))
    end inputJsonLines

    private def inputEvents(messages: Chunk[Message])(using Frame): Chunk[InputEvent] < Abort[AIGenException] =
        Kyo.foreach(messages)(inputEventsForMessage).map(_.flattenChunk)
    end inputEvents

    private def inputEventsForMessage(message: Message)(using Frame): Chunk[InputEvent] < Abort[AIGenException] =
        message match
            case AssistantMessage(_, calls, _, _) if calls.exists(_.function == Completion.resultToolName) =>
                Kyo.foreach(calls.filter(_.function == Completion.resultToolName)) { call =>
                    InputEvent(
                        "assistant",
                        InputMessage(
                            Role.Assistant.name,
                            List(InputContent(
                                "text",
                                text = Present(
                                    renderResultHistory(call.arguments)
                                )
                            ))
                        )
                    )
                }.map { resultEvents =>
                    val nonResult = AssistantMessage(
                        "",
                        calls.filterNot(_.function == Completion.resultToolName)
                    )
                    if nonResult.calls.isEmpty then resultEvents
                    else inputEvent(nonResult).map(event => resultEvents.prepended(event))
                }
            case ToolMessage(callId, _, _, _) if callId.id == "harness-result" =>
                Chunk.empty[InputEvent]
            case _ =>
                inputEvent(message).map(Chunk(_))
    end inputEventsForMessage

    private def inputEvent(message: Message)(using Frame): InputEvent < Abort[AIGenException] =
        message match
            case SystemMessage(content, _, _) =>
                Abort.fail(AIDecodeException(s"Claude Code system message was not routed to system prompt: $content"))
            case UserMessage(content, Present(image), _, _) =>
                InputEvent(
                    "user",
                    InputMessage(
                        Role.User.name,
                        List(
                            InputContent("text", text = Present(content)),
                            InputContent("image", source = Present(InputSource("base64", "image/jpeg", image.base64)))
                        )
                    )
                )
            case UserMessage(content, _, _, _) =>
                InputEvent("user", InputMessage(Role.User.name, List(InputContent("text", text = Present(content)))))
            case AssistantMessage(content, calls, _, _) =>
                Kyo.foreach(calls)(inputToolCall).map { callBlocks =>
                    val contentBlocks =
                        (if content.nonEmpty then List(InputContent("text", text = Present(content))) else Nil) ++ callBlocks.toList
                    InputEvent("assistant", InputMessage(Role.Assistant.name, contentBlocks))
                }
            case ToolMessage(callId, content, _, _) =>
                InputEvent(
                    "user",
                    InputMessage(
                        Role.User.name,
                        List(
                            InputContent(
                                "tool_result",
                                tool_use_id = Present(callId.id),
                                content = Present(content),
                                is_error = Present(isToolError(content))
                            )
                        )
                    )
                )
    end inputEvent

    private def inputToolCall(call: Call)(using Frame): InputContent < Abort[AIGenException] =
        Json.decode[Structure.Value](call.arguments) match
            case Result.Success(arguments) =>
                InputContent(
                    "tool_use",
                    id = Present(call.id.id),
                    name = Present(inputToolName(call.function)),
                    input = Present(arguments)
                )
            case Result.Failure(err) =>
                Abort.fail(AIDecodeException(s"Claude Code received invalid tool-call arguments: $err\n${call.arguments}"))
            case Result.Panic(ex) =>
                Abort.panic(ex)
    end inputToolCall

    private def isToolError(content: String): Boolean =
        content.contains("Tool '") && content.contains("failed:")

    private def inputToolName(name: String): String =
        if name == Completion.resultToolName || name.startsWith(mcpToolPrefix) then name
        else s"$mcpToolPrefix$name"
    end inputToolName

    private def renderResultArguments(arguments: String)(using Frame): String =
        Json.decode[Structure.Value](arguments) match
            case Result.Success(Structure.Value.Record(fields)) if fields.exists(_._1 == "resultValue") =>
                fields.collectFirst {
                    case ("resultValue", Structure.Value.Str(raw)) =>
                        Json.decode[Structure.Value](raw) match
                            case Result.Success(decoded) => Json.encode(decoded)
                            case _                       => Json.encode(Structure.Value.Str(raw))
                    case ("resultValue", value) =>
                        Json.encode(value)
                }.getOrElse(arguments)
            case Result.Success(value) =>
                Json.encode(value)
            case _ =>
                arguments
    end renderResultArguments

    private def renderResultHistory(arguments: String)(using Frame): String =
        val rendered = renderResultArguments(arguments)
        Json.decode[Structure.Value](rendered) match
            case Result.Success(Structure.Value.Record(fields)) =>
                val assignments = fields.map { (name, value) =>
                    s"$name = ${renderScalar(value)}"
                }.mkString("\n")
                p"""
                    Kyo history record for one previous assistant result. Use it only when the current user asks for prior values.
                    Do not replay this record. Do not include this record in a future answer unless the current user asks for these exact fields.
                    Exact JSON:
                    $rendered
                    Exact fields:
                    $assignments
                """
            case _ =>
                p"""
                    Kyo history record for one previous assistant result. Use it only when the current user asks for prior values.
                    Do not replay this record. Do not include this record in a future answer unless the current user asks for this exact value.
                    Exact JSON:
                    $rendered
                """
        end match
    end renderResultHistory

    private def renderScalar(value: Structure.Value)(using Frame): String =
        value match
            case Structure.Value.Str(value)     => value
            case Structure.Value.Bool(value)    => value.toString
            case Structure.Value.Integer(value) => value.toString
            case Structure.Value.Decimal(value) => value.toString
            case other                          => Json.encode(other)
    end renderScalar

    private def emitProcessFragments(proc: Process)(using Frame): Unit < (Emit[Chunk[String]] & Async & Scope & Abort[AIStreamException]) =
        stdoutLines(proc).fold((Absent: Maybe[String], Chunk.empty[String])) { case ((fragment, output), line) =>
            outputFragment(line) match
                case Result.Success(Present(next)) =>
                    Kyo.lift((Present(next), output.append(line)))
                case Result.Success(Absent) =>
                    Kyo.lift((fragment, output.append(line)))
                case Result.Failure(err) =>
                    Abort.fail(AIStreamDeltaException(err))
                case Result.Panic(ex) =>
                    Abort.panic(ex)
        }.map { case (fragment, output) =>
            proc.waitFor.map { code =>
                if !code.isSuccess then
                    Abort.fail(streamFailure(s"exited with ${code.toInt}: ${output.mkString("\n")}"))
                else
                    fragment match
                        case Present(value) => Emit.value(Chunk(value))
                        case Absent         => Abort.fail(AIStreamIncompleteException(output.mkString("\n")))
            }
        }
    end emitProcessFragments

    private def stdoutLines(proc: Process)(using Frame): Stream[String, Sync & Scope] =
        Stream[String, Sync & Scope] {
            proc.stdout
                .mapChunkPure(bytes => Chunk(String(bytes.toArray)))
                .fold("") { (buffer, text) =>
                    val next  = buffer + text
                    val parts = next.split("\n", -1).toIndexedSeq
                    val ready = Chunk.from(parts.dropRight(1).map(_.trim).filter(_.nonEmpty))
                    val rest  = parts.lastOption.getOrElse("")
                    if ready.isEmpty then rest
                    else Emit.value(ready).andThen(rest)
                }.map { rest =>
                    val trimmed = rest.trim
                    if trimmed.nonEmpty then Emit.value(Chunk(trimmed))
                    else Kyo.unit
                }
        }
    end stdoutLines

    private def outputFragment(line: String)(using Frame): Result[String, Maybe[String]] =
        Json.decode[OutputEvent](line) match
            case Result.Success(event) =>
                Result.Success(event.result.map(resultFragment))
            case Result.Failure(err) =>
                Result.Failure(s"Claude Code emitted malformed stream-json: $err\n$line")
            case Result.Panic(ex) =>
                Result.Panic(ex)
    end outputFragment

    private def resultFragment(raw: String)(using Frame): String =
        Json.decode[Structure.Value](raw) match
            case Result.Success(_) => raw
            case _ =>
                Json.encode(Structure.Value.Record(Chunk("resultValue" -> Structure.Value.Str(raw))))
    end resultFragment

    private[kyo] def readMessages(output: String)(using Frame): Chunk[Message] < Abort[AIGenException] =
        readMessages(output, Chunk.empty)

    private def readMessages(output: String, executedTools: Chunk[ExecutedTool])(using Frame): Chunk[Message] < Abort[AIGenException] =
        readEvents(output).map { parsed =>
            val structuredOutput = parsed.reverse.flatMap(_.message.toList).flatMap(_.content).collectFirst {
                case MessageContent("tool_use", _, _, Present("StructuredOutput"), Present(input), _, _, _) => Json.encode(input)
            }
            val transcript = transcriptMessages(parsed, executedTools)
            parsed.reverse.collectFirst { case e if e.result.nonEmpty => e.result.get } match
                case Some(result) =>
                    resultOutput(result).map(transcript.concat)
                case None if structuredOutput.nonEmpty =>
                    resultOutput(structuredOutput.get).map(transcript.concat)
                case None =>
                    parsed.reverse.flatMap(_.message.toList).headOption match
                        case Some(message) =>
                            val text = message.content.collect { case MessageContent("text", _, Present(text), _, _, _, _, _) =>
                                text
                            }.mkString
                            val calls = message.content.collect {
                                case MessageContent("tool_use", Present(id), _, Present(name), Present(input), _, _, _)
                                    if name != "StructuredOutput" =>
                                    val toolName = kyoToolName(name)
                                    val arguments =
                                        if toolName == Completion.resultToolName then resultEnvelope(input)
                                        else input
                                    Call(CallId(id), toolName, Json.encode(arguments))
                            }
                            if calls.nonEmpty then
                                transcriptMessages(Chunk(OutputEvent("assistant", message = Present(message))), executedTools)
                            else if text.nonEmpty then resultOutput(text)
                            else Abort.fail(AIDecodeException(s"Claude Code emitted no result message: $output"))
                            end if
                        case None =>
                            Abort.fail(AIDecodeException(s"Claude Code emitted no result message: $output"))
            end match
        }
    end readMessages

    private def transcriptMessages(events: Chunk[OutputEvent], executedTools: Chunk[ExecutedTool])(using Frame): Chunk[Message] =
        val (unmatched, messages) = events.foldLeft((executedTools, Chunk.empty[Message])) { case ((executed, out), event) =>
            event.message match
                case Present(message) if message.role == Role.Assistant.name =>
                    val text = message.content.collect { case MessageContent("text", _, Present(text), _, _, _, _, _) => text }.mkString
                    val (remaining, calls, toolMessages) =
                        message.content.foldLeft((executed, Chunk.empty[Call], Chunk.empty[ToolMessage])) {
                            case (
                                    (remaining, calls, tools),
                                    MessageContent("tool_use", Present(id), _, Present(name), Present(input), _, _, _)
                                )
                                if name != "StructuredOutput" =>
                                val toolName = kyoToolName(name)
                                val arguments =
                                    if toolName == Completion.resultToolName then resultEnvelope(input)
                                    else input
                                val call = Call(CallId(id), toolName, Json.encode(arguments))
                                if toolName == Completion.resultToolName then
                                    (remaining, calls.append(call), tools)
                                else
                                    matchExecutedTool(toolName, input, remaining) match
                                        case Present((executedTool, nextRemaining)) =>
                                            (nextRemaining, calls.append(call), tools.append(ToolMessage(call.id, executedTool.output)))
                                        case Absent =>
                                            (remaining, calls.append(call), tools)
                                    end match
                                end if
                            case (state, _) =>
                                state
                        }
                    val next =
                        if calls.nonEmpty then out.append(AssistantMessage(text, calls)).concat(toolMessages)
                        else out
                    (remaining, next)
                case Present(message) if message.role == Role.User.name =>
                    val toolMessages = Chunk.from(message.content.collect {
                        case MessageContent("tool_result", _, _, _, _, Present(callId), Present(content), _) =>
                            ToolMessage(CallId(callId), content)
                    })
                    (executed, out.concat(toolMessages))
                case _ =>
                    (executed, out)
        }
        messages.concat(syntheticToolMessages(unmatched))
    end transcriptMessages

    private def syntheticToolMessages(executedTools: Chunk[ExecutedTool])(using Frame): Chunk[Message] =
        executedTools.zipWithIndex.flatMap { case (tool, index) =>
            val call = Call(CallId(s"mcp-${tool.name}-$index"), tool.name, Json.encode(tool.arguments))
            Chunk(
                AssistantMessage("", Chunk(call)),
                ToolMessage(call.id, tool.output)
            )
        }
    end syntheticToolMessages

    private def matchExecutedTool(
        name: String,
        arguments: Structure.Value,
        executedTools: Chunk[ExecutedTool]
    ): Maybe[(ExecutedTool, Chunk[ExecutedTool])] =
        Maybe.fromOption(executedTools.zipWithIndex.collectFirst {
            case (tool, index) if tool.name == name && tool.arguments == arguments =>
                tool -> executedTools.take(index).concat(executedTools.drop(index + 1))
        })
    end matchExecutedTool

    private def kyoToolName(name: String): String =
        if name.startsWith(mcpToolPrefix) then name.drop(mcpToolPrefix.length) else name

    private def resultEnvelope(value: Structure.Value)(using Frame): Structure.Value =
        value match
            case Structure.Value.Record(fields) if fields.exists(_._1 == "resultValue") =>
                Structure.Value.Record(fields.map {
                    case ("resultValue", Structure.Value.Str(raw)) =>
                        Json.decode[Structure.Value](raw) match
                            case Result.Success(decoded) => "resultValue" -> decoded
                            case _                       => "resultValue" -> Structure.Value.Str(raw)
                    case field => field
                })
            case Structure.Value.Record(Chunk(("Valueresult", value))) =>
                Structure.Value.Record(Chunk("resultValue" -> value))
            case _ => Structure.Value.Record(Chunk("resultValue" -> value))
    end resultEnvelope

    private def readEvents(output: String)(using Frame): Chunk[OutputEvent] < Abort[AIGenException] =
        val lines = Chunk.from(output.linesIterator.toList.filter(_.trim.nonEmpty))
        Kyo.foreach(lines) { line =>
            Json.decode[OutputEvent](line) match
                case Result.Success(event) => event
                case Result.Failure(err)   => Abort.fail(AIDecodeException(s"Claude Code emitted malformed stream-json: $err\n$line"))
                case Result.Panic(ex)      => Abort.panic(ex)
        }
    end readEvents

end ClaudeCodeCompletion
