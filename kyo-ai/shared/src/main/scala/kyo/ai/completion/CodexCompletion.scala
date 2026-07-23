package kyo.ai.completion

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** Codex app-server completion adapter.
  *
  * Codex is driven through the app-server protocol so the CLI account transport is used while Kyo supplies
  * model-visible history with native Responses API items. The adapter
  * starts an ephemeral thread in an isolated temp cwd, injects prior non-system messages with
  * `thread/inject_items`, starts a turn with `turn/start`, then consumes the `item/completed` turn events
  * until `turn/completed`.
  */
private[completion] object CodexCompletion extends HarnessCompletion("Codex"):

    private case class RpcEvent(method: String, params: Structure.Value)
    private enum OutputText:
        case Delta(text: String)
        case Completed(text: String)

    private enum TurnResult:
        case Output(text: String)
        case ToolCall(call: Call)

    private val stderrTailMax = 8192

    private case class OutputMode(
        schema: Structure.Value,
        read: String => Chunk[Message] < Abort[AIGenException]
    )

    private case class ClientInfo(name: String, version: String) derives Schema
    private case class ClientCapabilities(experimentalApi: Boolean) derives Schema
    private case class InitializeParams(clientInfo: ClientInfo, capabilities: ClientCapabilities) derives Schema

    private case class CodexFeatures(plugins: Boolean, apps: Boolean, shell_tool: Boolean) derives Schema
    private case class CodexServerConfig(
        features: CodexFeatures,
        plugins: Map[String, Boolean],
        skills: Map[String, Boolean],
        marketplaces: Map[String, Boolean],
        web_search: String
    ) derives Schema
    private case class ThreadStartParams(
        model: Maybe[String],
        cwd: Maybe[String],
        ephemeral: Boolean,
        experimentalRawEvents: Boolean,
        baseInstructions: String,
        developerInstructions: String,
        approvalPolicy: String,
        sandbox: String,
        config: CodexServerConfig
    ) derives Schema
    private case class ThreadId(id: String) derives Schema
    private case class ThreadStartResponse(thread: ThreadId) derives Schema
    private case class ThreadInjectItemsParams(threadId: String, items: List[Structure.Value]) derives Schema
    private case class TurnInput(`type`: String, text: Maybe[String] = Absent, url: Maybe[String] = Absent) derives Schema
    private case class TurnStartParams(
        threadId: String,
        input: List[TurnInput],
        outputSchema: Structure.Value,
        model: Maybe[String],
        cwd: Maybe[String],
        approvalPolicy: String
    ) derives Schema
    private case class TurnId(id: String) derives Schema
    private case class TurnStartResponse(turn: TurnId) derives Schema
    private case class ThreadStatus(`type`: String) derives Schema
    private case class ThreadStatusChangedNotification(threadId: String, status: ThreadStatus) derives Schema
    private case class AgentMessageDeltaNotification(threadId: String, turnId: String, delta: String) derives Schema
    private case class HarnessTool(name: String, description: String, inputSchema: Structure.Value) derives Schema
    private case class HarnessCall(id: String, function: String, arguments: Structure.Value) derives Schema
    private case class HarnessAssistantMessage(content: String, calls: List[HarnessCall]) derives Schema
    private case class HarnessOutput(messages: List[HarnessAssistantMessage]) derives Schema

    private case class ContentItem(
        `type`: String,
        text: Maybe[String] = Absent,
        image_url: Maybe[String] = Absent
    ) derives Schema
    private case class ResponseHistoryItem(
        `type`: String,
        role: Maybe[String] = Absent,
        content: Maybe[List[ContentItem]] = Absent,
        call_id: Maybe[String] = Absent,
        name: Maybe[String] = Absent,
        arguments: Maybe[String] = Absent,
        output: Maybe[String] = Absent
    ) derives Schema
    private case class ThreadItem(
        `type`: String,
        text: Maybe[String] = Absent,
        id: Maybe[String] = Absent,
        namespace: Maybe[String] = Absent,
        tool: Maybe[String] = Absent,
        arguments: Maybe[Structure.Value] = Absent
    ) derives Schema
    private case class ItemCompletedNotification(threadId: String, turnId: String, item: ThreadItem) derives Schema
    private case class ResponseItem(`type`: String, role: Maybe[String] = Absent, content: Maybe[List[ContentItem]] = Absent)
        derives Schema
    private case class RawResponseItemCompletedNotification(threadId: String, turnId: String, item: ResponseItem) derives Schema

    private val disabledFeatures =
        Chunk(
            "plugins",
            "apps",
            "shell_tool",
            "browser_use",
            "computer_use",
            "unified_exec",
            "workspace_dependencies",
            "tool_suggest",
            "multi_agent",
            "hooks"
        )

    // The usageSink is accepted for the trait contract but never written: a CLI harness reports no
    // stream usage, so its anchor degrades exactly as its gen path does (§5a:372).
    override def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]],
        usageSink: AtomicRef[Maybe[Completion.Usage]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        Kyo.lift(Stream[String, Async & Scope & Abort[AIStreamException]] {
            Abort.run[
                CommandException | FileFsException | FileReadException | FileWriteException | JsonRpcError | AIGenException | Closed
            ] {
                withSession { (workDir, handler, events, stderrTail) =>
                    for
                        thread <- requestAs[ThreadStartResponse, ThreadStartParams](
                            handler,
                            "thread/start",
                            threadStartParams(config, context, resultTool, workDir, appServerSchema(resultSchema))
                        )
                        items <- historyItems(historyMessages(context))
                        _ <-
                            if items.isEmpty then Kyo.unit
                            else
                                requestAs[Structure.Value, ThreadInjectItemsParams](
                                    handler,
                                    "thread/inject_items",
                                    ThreadInjectItemsParams(thread.thread.id, items.toList)
                                ).unit
                        turn <- requestAs[TurnStartResponse, TurnStartParams](
                            handler,
                            "turn/start",
                            turnStartParams(config, thread.thread.id, workDir, appServerSchema(resultSchema), turnInput(context))
                        )
                        _ <- emitTurnFragments(events, thread.thread.id, turn.turn.id, stderrTail)
                    yield ()
                }
            }.map {
                case Result.Success(_)                     => Kyo.unit
                case Result.Failure(ex: AIStreamException) => Abort.fail(ex)
                case Result.Failure(ex: AIGenException)    => Abort.fail(streamFailure(ex.getMessage))
                case Result.Failure(_: Closed) => Abort.fail(AIStreamDeltaException("Codex app-server closed before completing the turn"))
                case Result.Failure(ex: JsonRpcError) => Abort.fail(streamFailure(s"${ex.code}: ${ex.message}"))
                case Result.Failure(ex)               => Abort.fail(streamFailure(ex.getMessage))
                case Result.Panic(ex)                 => Abort.panic(ex)
            }
        })
    end streamFragments

    protected def run(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(using
        Frame
    ): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
        Scope.run(Abort.run[
            CommandException | FileFsException | FileReadException | FileWriteException | JsonRpcError | AIGenException | Closed
        ] {
            withSession { (workDir, handler, events, stderrTail) =>
                for
                    outputMode = outputModeFor(tools, resultSchema)
                    thread <- requestAs[ThreadStartResponse, ThreadStartParams](
                        handler,
                        "thread/start",
                        threadStartParams(config, context, tools, workDir, outputMode.schema)
                    )
                    items <- historyItems(historyMessages(context))
                    _ <-
                        if items.isEmpty then Kyo.unit
                        else
                            requestAs[Structure.Value, ThreadInjectItemsParams](
                                handler,
                                "thread/inject_items",
                                ThreadInjectItemsParams(thread.thread.id, items.toList)
                            ).unit
                    turn <- requestAs[TurnStartResponse, TurnStartParams](
                        handler,
                        "turn/start",
                        turnStartParams(
                            config,
                            thread.thread.id,
                            workDir,
                            outputMode.schema,
                            turnInput(context)
                        )
                    )
                    result <- Async.timeoutWithError[AIGenException | Closed, TurnResult, Any](
                        config.timeout,
                        Result.Failure(AIProviderUnavailableException("Codex", "turn timed out waiting for app-server completion"))
                    )(collectTurnResult(events, thread.thread.id, turn.turn.id, stderrTail))
                    out <- result match
                        case TurnResult.Output(raw)    => outputMode.read(raw)
                        case TurnResult.ToolCall(call) => Kyo.lift(Chunk(AssistantMessage("", Chunk(call))))
                yield out
            }
        }).map {
            case Result.Success(messages)           => messages
            case Result.Failure(ex: AIGenException) => Abort.fail(ex)
            case Result.Failure(_: Closed)          => Abort.fail(AIDecodeException("Codex app-server closed before completing the turn"))
            case Result.Failure(ex: JsonRpcError)   => Abort.fail(commandFailure(s"${ex.code}: ${ex.message}"))
            case Result.Failure(ex)                 => Abort.fail(commandFailure(ex.getMessage))
            case Result.Panic(ex)                   => Abort.panic(ex)
        }
    end run

    private def withSession[A, S](
        f: (Path, JsonRpcHandler, Channel[RpcEvent], AtomicRef[String]) => A < S
    )(using
        Frame
    ): A < (S & Async & Scope & Abort[
        CommandException | FileFsException | FileReadException | FileWriteException | JsonRpcError | Closed
    ]) =
        for
            rawWorkDir <- Path.tempDir("kyo-ai-codex-cwd")
            workDir    <- Scope.acquireRelease(rawWorkDir)(path => Abort.run[FileFsException](path.removeAll).unit)
            codexHome  <- isolatedCodexHome
            proc       <- appServerCommand(workDir, codexHome).spawn
            stderrTail <- AtomicRef.init("")
            _          <- Fiber.init(Scope.run(captureStderr(proc, stderrTail)))
            events     <- Channel.init[RpcEvent](1024)
            transport  <- JsonRpcTransport.fromWire(processWire(proc), JsonRpcFramer.lineDelimited)
            handler    <- JsonRpcHandler.init(transport, eventRoutes(events))
            _ <- requestAs[Structure.Value, InitializeParams](
                handler,
                "initialize",
                InitializeParams(ClientInfo("kyo-ai", "0"), ClientCapabilities(experimentalApi = true))
            )
            _      <- handler.notify("initialized", Structure.Value.Record(Chunk.empty))
            result <- f(workDir, handler, events, stderrTail)
        yield result
    end withSession

    private def captureStderr(proc: Process, tail: AtomicRef[String])(using Frame): Unit < (Sync & Scope) =
        proc.stderr.foreachChunk { bytes =>
            val text = new String(bytes.toArray, StandardCharsets.UTF_8)
            tail.updateAndGet(previous => truncateStderr(previous + text)).unit
        }
    end captureStderr

    private def truncateStderr(text: String): String =
        if text.length <= stderrTailMax then text
        else text.takeRight(stderrTailMax)
    end truncateStderr

    private def appServerCommand(workDir: Path, codexHome: Path): Command =
        Command((Chunk("codex", "app-server", "--stdio", "--strict-config") ++ disabledFeatures.flatMap(f => Chunk("--disable", f)))*)
            .cwd(workDir)
            .envAppend(Map("CODEX_HOME" -> codexHome.toString))
            .pipeStdin
    end appServerCommand

    private def isolatedCodexHome(using
        Frame
    ): Path < (Sync & Scope & Abort[FileFsException | FileReadException | FileWriteException]) =
        for
            rawIsolated <- Path.tempDir("kyo-ai-codex-home")
            isolated    <- Scope.acquireRelease(rawIsolated)(path => Abort.run[FileFsException](path.removeAll).unit)
            real        <- realCodexHome
            auth   = real / "auth.json"
            config = real / "config.toml"
            hasAuth   <- auth.exists
            hasConfig <- config.exists
            _         <- if hasAuth then auth.copy(isolated / "auth.json") else Kyo.unit
            _         <- if hasConfig then config.copy(isolated / "config.toml") else Kyo.unit
        yield isolated
    end isolatedCodexHome

    private def realCodexHome(using Frame): Path < Sync =
        for
            env         <- System.env[String]("CODEX_HOME")
            property    <- System.property[String]("user.home")
            home        <- System.env[String]("HOME")
            userProfile <- System.env[String]("USERPROFILE")
        yield env match
            case Present(path) => Path(path)
            case Absent =>
                val path = property.orElse(home).orElse(userProfile).getOrElse("")
                if path.isEmpty then Path(".codex")
                else Path(path) / ".codex"
    end realCodexHome

    private def threadStartParams(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        workDir: Path,
        schema: Structure.Value
    )(using Frame): ThreadStartParams =
        ThreadStartParams(
            model = modelOverride(config),
            cwd = Present(workDir.toString),
            ephemeral = true,
            experimentalRawEvents = true,
            baseInstructions = outputInstructions(context, tools, schema),
            developerInstructions = "Use only the injected conversation history and return only the requested structured output.",
            approvalPolicy = "never",
            sandbox = "read-only",
            config = CodexServerConfig(
                features = CodexFeatures(plugins = false, apps = false, shell_tool = false),
                plugins = Map.empty,
                skills = Map.empty,
                marketplaces = Map.empty,
                web_search = "disabled"
            )
        )
    end threadStartParams

    private def turnStartParams(
        config: Config,
        threadId: String,
        workDir: Path,
        schema: Structure.Value,
        input: Chunk[TurnInput]
    )(using Frame): TurnStartParams =
        TurnStartParams(
            threadId = threadId,
            input = input.toList,
            outputSchema = schema,
            model = modelOverride(config),
            cwd = Present(workDir.toString),
            approvalPolicy = "never"
        )

    private[kyo] def closedSchema(schema: JsonSchema)(using Frame): Structure.Value =
        closeObjects(Structure.encode(schema))

    private[kyo] def appServerSchema(schema: JsonSchema)(using Frame): Structure.Value =
        appServerCompatible(closedSchema(schema))

    private def appServerCompatible(value: Structure.Value): Structure.Value =
        value match
            case Structure.Value.Record(fields) =>
                val nullable = fields.collectFirst {
                    case ("oneOf", Structure.Value.Sequence(values)) =>
                        val nonNull = values.filterNot(isNullSchema)
                        if nonNull.size == 1 then Present(appServerCompatible(nonNull.head))
                        else Absent
                }
                nullable match
                    case Some(Present(value)) => value
                    case _ =>
                        val mapped = fields.map((name, value) => name -> appServerCompatible(value))
                        if isOpenMapObject(mapped) then
                            Structure.Value.Record(mapped.map {
                                case ("additionalProperties", _) => "additionalProperties" -> Structure.Value.Bool(true)
                                case field                       => field
                            })
                        else Structure.Value.Record(mapped)
                        end if
                end match
            case Structure.Value.Sequence(values) =>
                Structure.Value.Sequence(values.map(appServerCompatible))
            case Structure.Value.MapEntries(entries) =>
                Structure.Value.MapEntries(entries.map((k, v) => appServerCompatible(k) -> appServerCompatible(v)))
            case other =>
                other
        end match
    end appServerCompatible

    private def isNullSchema(value: Structure.Value): Boolean =
        value match
            case Structure.Value.Record(fields) =>
                fields.exists {
                    case ("type", Structure.Value.Str("null")) => true
                    case _                                     => false
                }
            case _ => false
        end match
    end isNullSchema

    private def isOpenMapObject(fields: Chunk[(String, Structure.Value)]): Boolean =
        val byName = fields.iterator.toMap
        byName.get("type").contains(Structure.Value.Str("object")) &&
        byName.get("properties").contains(Structure.Value.Record(Chunk.empty)) &&
        byName.get("additionalProperties").exists(_ != Structure.Value.Bool(false))
    end isOpenMapObject

    private def closeObjects(value: Structure.Value): Structure.Value =
        value match
            case Structure.Value.Record(fields) =>
                val closedFields =
                    if fields.exists(_._1 == "type") && fields.exists {
                            case ("type", Structure.Value.Str("object")) => true
                            case _                                       => false
                        }
                    then
                        if fields.exists(_._1 == "additionalProperties") then fields
                        else fields.append("additionalProperties" -> Structure.Value.Bool(false))
                    else fields
                Structure.Value.Record(closedFields.map((name, value) => name -> closeObjects(value)))
            case Structure.Value.Sequence(values) =>
                Structure.Value.Sequence(values.map(closeObjects))
            case Structure.Value.MapEntries(entries) =>
                Structure.Value.MapEntries(entries.map((k, v) => closeObjects(k) -> closeObjects(v)))
            case other =>
                other
        end match
    end closeObjects

    private def outputModeFor(
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(using Frame): OutputMode =
        val userTools = tools.filterNot(_.name == Completion.resultToolName)
        if userTools.nonEmpty then
            OutputMode(toolCallOutputSchema(tools, resultSchema), raw => readStructuredOutput(raw))
        else
            OutputMode(stringifiedResultSchema(closedSchema(resultSchema)), raw => resultOutput(raw))
        end if
    end outputModeFor

    private def toolCallOutputSchema(tools: Chunk[Tool.internal.Info[?, ?, LLM]], resultSchema: JsonSchema)(using
        Frame
    ): Structure.Value =
        val callSchema = anyOfSchema(tools.map { tool =>
            callObjectSchema(
                enumSchema(tool.name),
                if tool.name == Completion.resultToolName then
                    val schema = closedSchema(resultSchema)
                    if hasDynamicMap(schema) then stringifiedResultSchema(schema)
                    else appServerCompatible(schema)
                else
                    val schema = appServerSchema(tool.inputJsonSchema)
                    if hasDynamicMap(schema) then stringifiedJsonSchema(schema)
                    else schema
            )
        })
        objectSchema(
            Chunk(
                "messages" -> arraySchema(objectSchema(
                    Chunk(
                        "content" -> stringSchema,
                        "calls"   -> arraySchema(callSchema)
                    ),
                    Chunk("content", "calls")
                ))
            ),
            Chunk("messages")
        )
    end toolCallOutputSchema

    private def callObjectSchema(functionSchema: Structure.Value, argumentsSchema: Structure.Value): Structure.Value =
        objectSchema(
            Chunk(
                "id"        -> stringSchema,
                "function"  -> functionSchema,
                "arguments" -> argumentsSchema
            ),
            Chunk("id", "function", "arguments")
        )

    private def stringSchema: Structure.Value =
        Structure.Value.Record(Chunk("type" -> Structure.Value.Str("string")))

    private def enumSchema(value: String): Structure.Value =
        Structure.Value.Record(Chunk(
            "type" -> Structure.Value.Str("string"),
            "enum" -> Structure.Value.Sequence(Chunk(Structure.Value.Str(value)))
        ))

    private def anyOfSchema(values: Chunk[Structure.Value]): Structure.Value =
        Structure.Value.Record(Chunk("anyOf" -> Structure.Value.Sequence(values)))

    private def arraySchema(items: Structure.Value): Structure.Value =
        Structure.Value.Record(Chunk(
            "type"  -> Structure.Value.Str("array"),
            "items" -> items
        ))

    private def objectSchema(properties: Chunk[(String, Structure.Value)], required: Chunk[String]): Structure.Value =
        Structure.Value.Record(Chunk(
            "type"                 -> Structure.Value.Str("object"),
            "properties"           -> Structure.Value.Record(properties),
            "required"             -> Structure.Value.Sequence(required.map(Structure.Value.Str(_))),
            "additionalProperties" -> Structure.Value.Bool(false)
        ))

    private def stringifiedResultSchema(realSchema: Structure.Value)(using Frame): Structure.Value =
        objectSchema(
            Chunk(
                "resultValue" -> stringifiedJsonSchema(realSchema)
            ),
            Chunk("resultValue")
        )
    end stringifiedResultSchema

    private def stringifiedJsonSchema(realSchema: Structure.Value)(using Frame): Structure.Value =
        Structure.Value.Record(Chunk(
            "type" -> Structure.Value.Str("string"),
            "description" -> Structure.Value.Str(
                s"JSON string whose parsed value matches this schema: ${Json.encode(realSchema)}"
            )
        ))
    end stringifiedJsonSchema

    private def hasDynamicMap(value: Structure.Value): Boolean =
        value match
            case Structure.Value.Record(fields) =>
                isOpenMapObject(fields) || fields.exists((_, value) => hasDynamicMap(value))
            case Structure.Value.Sequence(values) =>
                values.exists(hasDynamicMap)
            case Structure.Value.MapEntries(entries) =>
                entries.exists((k, v) => hasDynamicMap(k) || hasDynamicMap(v))
            case _ =>
                false
        end match
    end hasDynamicMap

    private def outputInstructions(
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        schema: Structure.Value
    )(using Frame): String =
        val userTools = tools.filterNot(_.name == Completion.resultToolName)
        val finishingToolResult = conversationMessages(context).lastMaybe.exists {
            case ToolMessage(_, content, _, _) => isSuccessfulToolResult(content)
            case _                             => false
        }
        val toolSpecs = userTools.map { tool =>
            HarnessTool(
                tool.name,
                tool.description,
                appServerSchema(tool.inputJsonSchema)
            )
        }
        val instruction =
            if userTools.nonEmpty && finishingToolResult then
                p"""
                    Return only JSON matching this output schema:
                    ${Json.encode(schema)}
                    The JSON contains assistant messages in order.
                    The latest conversation item is a successful Kyo tool result for the current request.
                    Use that tool result to answer. Do not call any Kyo tool except '${Completion.resultToolName}'.
                    Call '${Completion.resultToolName}' with the final result arguments.
                """
            else if userTools.nonEmpty then
                p"""
                    Return only JSON matching this output schema:
                    ${Json.encode(schema)}
                    The JSON contains assistant messages in order.
                    Each assistant message may contain text content and tool calls.
                    If an available Kyo tool is needed, return that tool call and wait for Kyo to execute it.
                    Kyo executes the tool and sends the result back in a later turn.
                    If conversation history already contains a function_call_output for the current request, use that output and do not call the same tool again.
                    If no Kyo tool is needed, call '${Completion.resultToolName}' with the final result arguments.
                    Available Kyo tools:
                    ${Json.encode(toolSpecs.toList)}
                """
            else
                p"""
                    Return only JSON matching this output schema:
                    ${Json.encode(schema)}
                    Include every required property using the exact property name from the schema.
                    When the schema contains a top-level property named 'resultValue', the response object must contain that exact property name.
                    Put a valid JSON string inside 'resultValue'. The parsed string must match the schema described by the 'resultValue' property.
                    Never create property names from words in the user's request or answer.
                    Do not rename, abbreviate, split, merge, or omit schema properties.
                    Use the native injected conversation history and current turn input.
                """
        (context.compacted.collect { case SystemMessage(content, _, _) => content } :+ instruction).mkString("\n\n")
    end outputInstructions

    private def isSuccessfulToolResult(content: String): Boolean =
        !content.contains("Tool '") || !content.contains("failed:")
    end isSuccessfulToolResult

    private def historyMessages(context: Context): Chunk[Message] =
        val messages = conversationMessages(context)
        messages.lastMaybe match
            case Present(_: UserMessage) => messages.dropRight(1)
            case _                       => messages
    end historyMessages

    private def turnInput(context: Context): Chunk[TurnInput] =
        conversationMessages(context).lastMaybe match
            case Present(UserMessage(content, image, _, _)) =>
                Chunk(TurnInput("text", text = Present(content))).concat(
                    image.map(img => Chunk(TurnInput("image", url = Present(s"data:image/jpeg;base64,${img.base64}")))).getOrElse(
                        Chunk.empty
                    )
                )
            case _ =>
                Chunk(TurnInput("text", text = Present("Continue.")))
        end match
    end turnInput

    private def conversationMessages(context: Context): Chunk[Message] =
        context.compacted.filterNot(_.role == Role.System)

    private def modelOverride(config: Config): Maybe[String] =
        if config.modelName.isEmpty then Absent else Present(config.modelName)

    private[kyo] def historyItems(messages: Chunk[Message])(using Frame): Chunk[Structure.Value] < Abort[AIGenException] =
        Kyo.foreach(messages.filterNot(_.role == Role.System)) {
            case UserMessage(content, image, _, _) =>
                val blocks =
                    List(ContentItem("input_text", text = Present(content))) ++
                        image.toList.map(img => ContentItem("input_image", image_url = Present(s"data:image/jpeg;base64,${img.base64}")))
                Chunk(Structure.encode(ResponseHistoryItem("message", role = Present("user"), content = Present(blocks))))
            case AssistantMessage(content, calls, _, _) =>
                Kyo.foreach(calls) { call =>
                    Json.decode[Structure.Value](call.arguments) match
                        case Result.Success(_) =>
                            Structure.encode(ResponseHistoryItem(
                                "function_call",
                                call_id = Present(call.id.id),
                                name = Present(call.function),
                                arguments = Present(call.arguments)
                            ))
                        case Result.Failure(err) =>
                            Abort.fail(AIDecodeException(s"Codex received invalid tool-call arguments: $err\n${call.arguments}"))
                        case Result.Panic(ex) =>
                            Abort.panic(ex)
                }.map { callItems =>
                    val textItem =
                        if content.nonEmpty then
                            Chunk(Structure.encode(ResponseHistoryItem(
                                "message",
                                role = Present("assistant"),
                                content = Present(List(ContentItem("output_text", text = Present(content))))
                            )))
                        else Chunk.empty
                    textItem.concat(callItems)
                }
            case ToolMessage(callId, content, _, _) =>
                Chunk(Structure.encode(ResponseHistoryItem(
                    "function_call_output",
                    call_id = Present(callId.id),
                    output = Present(content)
                )))
            case SystemMessage(_, _, _) =>
                Chunk.empty[Structure.Value]
        }.map(_.flattenChunk)
    end historyItems

    private def requestAs[A: Schema, P: Schema](handler: JsonRpcHandler, method: String, params: P)(using
        Frame
    ): A < (Async & Abort[JsonRpcError | Closed]) =
        handler.call[P, A](method, params)
    end requestAs

    private def eventRoutes(events: Channel[RpcEvent])(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
        Seq(
            eventRoute(events, "item/completed"),
            eventRoute(events, "raw_response_item/completed"),
            eventRoute(events, "rawResponseItem/completed"),
            eventRoute(events, "item/agentMessage/delta"),
            eventRoute(events, "turn/completed"),
            eventRoute(events, "thread/status/changed"),
            eventRoute(events, "error")
        )
    end eventRoutes

    private def eventRoute(events: Channel[RpcEvent], method: String)(using
        Frame
    ): JsonRpcRoute[Structure.Value, Unit, Nothing] =
        JsonRpcRoute.notification[Structure.Value](method) { (params, _) =>
            Abort.run[Closed](events.put(RpcEvent(method, params))).unit
        }
    end eventRoute

    private def processWire(proc: Process)(using Frame): JsonRpcWireTransport =
        new JsonRpcWireTransport:
            def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
                Abort.run[Throwable] {
                    Sync.Unsafe.defer {
                        // Unsafe: JsonRpcWireTransport needs direct access to the child process stdin.
                        proc.unsafe.stdinJava.write(bytes.toArray)
                        proc.unsafe.stdinJava.flush()
                    }
                }.map {
                    case Result.Success(_)  => ()
                    case Result.Failure(ex) => Abort.fail(Closed("Codex app-server", summon[Frame], ex.getMessage))
                    case Result.Panic(ex)   => Abort.panic(ex)
                }
            end send

            def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
                proc.stdout.map(Chunk(_)).handle(Scope.run)

            def close(using Frame): Unit < Async =
                Sync.Unsafe.defer {
                    // Unsafe: closing the process stdin is the transport shutdown boundary.
                    proc.unsafe.stdinJava.close()
                }.unit
            end close
        end new
    end processWire

    private def collectTurnResult(
        events: Channel[RpcEvent],
        threadId: String,
        turnId: String,
        stderrTail: AtomicRef[String]
    )(using Frame): TurnResult < (Async & Abort[AIGenException | Closed]) =
        Loop((Absent: Maybe[String], "", false, Chunk.empty[String])) { case (completed, delta, turnCompleted, recent) =>
            if turnCompleted && completed.isDefined then
                Loop.done(TurnResult.Output(completed.get))
            else if turnCompleted && delta.nonEmpty then
                Loop.done(TurnResult.Output(delta))
            else
                val nextEvent =
                    if turnCompleted then
                        Async.timeoutWithError[AIGenException | Closed, RpcEvent, Any](
                            1.second,
                            Result.Failure(AIDecodeException(
                                s"Codex app-server completed without an assistant message. Recent events: ${recent.mkString(", ")}"
                            ))
                        )(events.take)
                    else events.take
                nextEvent.map { event =>
                    val nextRecent = recent.append(eventSummary(event)).takeRight(12)
                    if isTurnCompleted(event, threadId, turnId) then
                        completed match
                            case Present(text) => Loop.done(TurnResult.Output(text))
                            case Absent if delta.nonEmpty =>
                                Loop.done(TurnResult.Output(delta))
                            case Absent =>
                                Loop.continue((completed, delta, true, nextRecent))
                    else
                        eventResult(event, threadId, turnId).map {
                            case Present(TurnResult.ToolCall(call)) =>
                                Loop.done(TurnResult.ToolCall(call))
                            case Absent =>
                                eventText(event, threadId, turnId, stderrTail).map {
                                    case Present(OutputText.Completed(text)) =>
                                        Loop.continue((Present(text), delta, turnCompleted, nextRecent))
                                    case Present(OutputText.Delta(text)) =>
                                        Loop.continue((completed, delta + text, turnCompleted, nextRecent))
                                    case _ =>
                                        Loop.continue((completed, delta, turnCompleted, nextRecent))
                                }
                        }
                    end if
                }
            end if
        }
    end collectTurnResult

    private def emitTurnFragments(
        events: Channel[RpcEvent],
        threadId: String,
        turnId: String,
        stderrTail: AtomicRef[String]
    )(using Frame): Unit < (Emit[Chunk[String]] & Async & Abort[AIStreamException | Closed]) =
        Abort.recover[AIGenException](ex => Abort.fail(streamFailure(ex.getMessage))) {
            collectTurnResult(events, threadId, turnId, stderrTail).map {
                case TurnResult.Output(text) if text.nonEmpty =>
                    Emit.value(Chunk(text))
                case TurnResult.Output(_) =>
                    Abort.fail(AIStreamIncompleteException("Codex app-server completed without a stream fragment"))
                case TurnResult.ToolCall(call) =>
                    Abort.fail(
                        AIStreamDeltaException(s"Codex app-server emitted an unexpected tool call while streaming: ${call.function}")
                    )
            }
        }
    end emitTurnFragments

    private def isTurnCompleted(event: RpcEvent, threadId: String, turnId: String)(using Frame): Boolean =
        if event.method != "turn/completed" then false
        else
            event.params match
                case Structure.Value.Record(fields) =>
                    fields.iterator.toMap.get("threadId").contains(Structure.Value.Str(threadId)) &&
                    fields.iterator.toMap.get("turn").exists {
                        case Structure.Value.Record(turnFields) =>
                            turnFields.iterator.toMap.get("id").contains(Structure.Value.Str(turnId))
                        case _ => false
                    }
                case _ => false
        end if
    end isTurnCompleted

    private def eventSummary(event: RpcEvent)(using Frame): String =
        val params = Json.encode(event.params)
        val preview =
            if params.length <= 500 then params
            else params.take(500) + "..."
        s"${event.method}: $preview"
    end eventSummary

    private def eventText(
        event: RpcEvent,
        threadId: String,
        turnId: String,
        stderrTail: AtomicRef[String]
    )(using Frame): Maybe[OutputText] < (Sync & Abort[AIGenException]) =
        event.method match
            case "item/completed" =>
                decodeEvent[ItemCompletedNotification](event).map { notification =>
                    if notification.threadId == threadId && notification.turnId == turnId && notification.item.`type` == "agentMessage" then
                        notification.item.text.map(OutputText.Completed(_))
                    else Absent
                }
            case "raw_response_item/completed" | "rawResponseItem/completed" =>
                decodeEvent[RawResponseItemCompletedNotification](event).map { notification =>
                    if notification.threadId == threadId && notification.turnId == turnId then
                        val text = notification.item.content
                            .getOrElse(Nil)
                            .collect { case ContentItem("output_text", Present(text), _) => text }
                            .mkString
                        if text.nonEmpty then Present(OutputText.Completed(text)) else Absent
                    else Absent
                }
            case "item/agentMessage/delta" =>
                decodeEvent[AgentMessageDeltaNotification](event).map { notification =>
                    if notification.threadId == threadId && notification.turnId == turnId && notification.delta.nonEmpty then
                        Present(OutputText.Delta(notification.delta))
                    else Absent
                }
            case "error" =>
                if isRetryingError(event.params) then Absent
                else failWithStderr(Json.encode(event.params), stderrTail)
            case "thread/status/changed" =>
                decodeEvent[ThreadStatusChangedNotification](event).map { notification =>
                    if notification.threadId == threadId && notification.status.`type` == "systemError" then
                        failWithStderr(Json.encode(event.params), stderrTail)
                    else Absent
                }
            case _ =>
                Absent
    end eventText

    private def failWithStderr(detail: String, stderrTail: AtomicRef[String])(using Frame): Nothing < (Sync & Abort[AIGenException]) =
        stderrTail.get.map { stderr =>
            val suffix =
                if stderr.trim.isEmpty then ""
                else s"\nCodex app-server stderr tail:\n${stderr.trim}"
            Abort.fail(commandFailure(detail + suffix))
        }
    end failWithStderr

    private def isRetryingError(params: Structure.Value): Boolean =
        params match
            case Structure.Value.Record(fields) =>
                fields.iterator.toMap.get("willRetry").contains(Structure.Value.Bool(true))
            case _ =>
                false
        end match
    end isRetryingError

    private def eventResult(event: RpcEvent, threadId: String, turnId: String)(using Frame): Maybe[TurnResult] < Abort[AIGenException] =
        event.method match
            case "item/completed" =>
                decodeEvent[ItemCompletedNotification](event).map { notification =>
                    val item = notification.item
                    if notification.threadId == threadId && notification.turnId == turnId && item.`type` == "dynamicToolCall" then
                        for
                            id        <- item.id
                            tool      <- item.tool
                            arguments <- item.arguments
                        yield TurnResult.ToolCall(Call(CallId(id), toolName(item.namespace, tool), Json.encode(arguments)))
                    else Absent
                    end if
                }
            case _ =>
                Absent
    end eventResult

    private def toolName(namespace: Maybe[String], tool: String): String =
        namespace match
            case Present(ns) => s"$ns.$tool"
            case Absent      => tool
    end toolName

    private[kyo] def readStructuredOutput(raw: String)(using Frame): Chunk[Message] < Abort[AIGenException] =
        decodeHarnessMessages(raw) match
            case Result.Success(messages) =>
                if messages.isEmpty then
                    Abort.fail(AIDecodeException(s"Codex harness output contained no messages: $raw"))
                else
                    Chunk.from(messages.map { message =>
                        val calls = Chunk.from(message.calls.map { call =>
                            Call(CallId(call.id), call.function, Json.encode(callArguments(call)))
                        })
                        AssistantMessage(message.content, calls)
                    })
            case Result.Failure(_) =>
                resultOutput(raw)
            case Result.Panic(ex) =>
                Abort.panic(ex)
    end readStructuredOutput

    private def decodeHarnessMessages(raw: String)(using Frame): Result[String, List[HarnessAssistantMessage]] =
        decodeHarnessOutput(raw) match
            case Result.Success(output) =>
                Result.Success(output.messages)
            case Result.Failure(err) =>
                val trimmed = raw.trim
                if trimmed.startsWith("[") then
                    decodeHarnessOutput(s"""{"messages":$trimmed}""") match
                        case Result.Success(output) => Result.Success(output.messages)
                        case Result.Failure(_)      => Result.Failure(err)
                        case Result.Panic(ex)       => Result.Panic(ex)
                else Result.Failure(err)
                end if
            case Result.Panic(ex) =>
                Result.Panic(ex)
    end decodeHarnessMessages

    private def decodeHarnessOutput(raw: String)(using Frame): Result[String, HarnessOutput] =
        Json.decode[HarnessOutput](raw) match
            case Result.Success(output) => Result.Success(output)
            case Result.Failure(err)    => Result.Failure(err.getMessage)
            case Result.Panic(ex)       => Result.Panic(ex)
    end decodeHarnessOutput

    private def callArguments(call: HarnessCall)(using Frame): Structure.Value =
        if call.function == Completion.resultToolName then resultEnvelope(call.arguments)
        else
            call.arguments match
                case Structure.Value.Str(raw) =>
                    Json.decode[Structure.Value](raw) match
                        case Result.Success(value) => value
                        case _                     => call.arguments
                case _ => call.arguments
    end callArguments

    private def resultEnvelope(value: Structure.Value)(using Frame): Structure.Value =
        value match
            case Structure.Value.Record(fields) if fields.exists(_._1 == "resultValue") =>
                fields.collectFirst { case ("resultValue", Structure.Value.Str(raw)) => raw } match
                    case Some(raw) =>
                        decodeStringifiedResult(raw) match
                            case Result.Success(decoded @ Structure.Value.Record(decodedFields))
                                if decodedFields.exists(_._1 == "resultValue") =>
                                decoded
                            case Result.Success(decoded) =>
                                Structure.Value.Record(fields.map {
                                    case ("resultValue", _) => "resultValue" -> decoded
                                    case field              => field
                                })
                            case _ =>
                                value
                    case None =>
                        value
            case Structure.Value.Record(Chunk(("Valueresult", value))) =>
                Structure.Value.Record(Chunk("resultValue" -> value))
            case _ => Structure.Value.Record(Chunk("resultValue" -> value))
    end resultEnvelope

    private def decodeStringifiedResult(raw: String)(using Frame): Result[Throwable, Structure.Value] =
        Json.decode[Structure.Value](raw) match
            case success @ Result.Success(_) => success
            case Result.Failure(_)           => Json.decode[Structure.Value](LLM.completePartialJson(raw))
            case Result.Panic(ex)            => Result.Panic(ex)
    end decodeStringifiedResult

    private def decodeEvent[A: Schema](event: RpcEvent)(using Frame): A < Abort[AIGenException] =
        Structure.decode[A](event.params) match
            case Result.Success(value) => value
            case Result.Failure(err)   => Abort.fail(AIDecodeException(s"Codex event '${event.method}' is invalid: $err"))
            case Result.Panic(ex)      => Abort.panic(ex)

end CodexCompletion
