package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** The Codex wire codecs: the app-server RPC and Responses-API DTOs and every pure transform between
  * kyo's `Context` and the Codex app-server's request params, history items, and turn events. Everything
  * here is a pure function of its inputs (`Abort` carries typed decode failures); nothing touches a
  * process, socket, or channel. [[CodexCompletion]] holds the orchestration and calls in here, the same
  * split the HTTP backends keep with their `internal` codec objects and the Claude Code backend keeps
  * with [[ClaudeCodeWire]].
  */
private[completion] object CodexWire:

    case class RpcEvent(method: String, params: Structure.Value)
    enum OutputText:
        case Delta(text: String)
        case Completed(text: String)

    /** A user-tool call the app-server asked kyo to execute (`item/tool/call`), with the output kyo
      * answered. Recorded in call order; `resultMessages` replays each as a native call/result pair.
      */
    case class ExecutedCall(callId: String, name: String, arguments: Structure.Value, output: String)
    private val stderrTailMax = 8192

    case class ClientInfo(name: String, version: String) derives Schema
    case class ClientCapabilities(experimentalApi: Boolean) derives Schema
    case class InitializeParams(clientInfo: ClientInfo, capabilities: ClientCapabilities) derives Schema

    case class CodexFeatures(plugins: Boolean, apps: Boolean, shell_tool: Boolean) derives Schema
    case class CodexServerConfig(
        features: CodexFeatures,
        plugins: Map[String, Boolean],
        skills: Map[String, Boolean],
        marketplaces: Map[String, Boolean],
        web_search: String
    ) derives Schema
    // A REAL registered tool (thread/start.dynamicTools): the model sees it like any native tool and the
    // app-server calls kyo back (item/tool/call) to execute it. `type` is the spec discriminator ("function").
    // The Codex analog of the OpenAI tools array and the Claude Code MCP bridge; the result tool and every
    // kyo tool ride it, so no schema restatement or tool catalog rides the instructions.
    case class DynamicTool(`type`: String, name: String, description: String, inputSchema: Structure.Value) derives Schema
    case class ThreadStartParams(
        model: Maybe[String],
        cwd: Maybe[String],
        ephemeral: Boolean,
        experimentalRawEvents: Boolean,
        baseInstructions: String,
        approvalPolicy: String,
        sandbox: String,
        config: CodexServerConfig,
        dynamicTools: List[DynamicTool]
    ) derives Schema
    case class ThreadId(id: String) derives Schema
    case class ThreadStartResponse(thread: ThreadId) derives Schema
    case class ThreadInjectItemsParams(threadId: String, items: List[Structure.Value]) derives Schema
    case class TurnInput(`type`: String, text: Maybe[String] = Absent, url: Maybe[String] = Absent) derives Schema
    case class TurnStartParams(
        threadId: String,
        input: List[TurnInput],
        model: Maybe[String],
        cwd: Maybe[String],
        approvalPolicy: String
    ) derives Schema
    case class TurnId(id: String) derives Schema
    case class TurnStartResponse(turn: TurnId) derives Schema
    case class TurnInterruptParams(threadId: String, turnId: String) derives Schema
    case class ThreadStatus(`type`: String) derives Schema
    case class ThreadStatusChangedNotification(threadId: String, status: ThreadStatus) derives Schema
    case class AgentMessageDeltaNotification(threadId: String, turnId: String, delta: String) derives Schema
    // The server->client execution request for a registered dynamic tool, and its response shape
    // (verified against the app-server: the response requires `contentItems` + `success`).
    case class ToolCallRequestParams(
        threadId: String,
        turnId: String,
        callId: String,
        tool: String,
        arguments: Structure.Value,
        namespace: Maybe[String] = Absent
    ) derives Schema
    case class ToolCallContent(`type`: String, text: String) derives Schema
    case class ToolCallResponse(contentItems: List[ToolCallContent], success: Boolean) derives Schema

    case class ContentItem(
        `type`: String,
        text: Maybe[String] = Absent,
        image_url: Maybe[String] = Absent
    ) derives Schema
    case class ResponseHistoryItem(
        `type`: String,
        role: Maybe[String] = Absent,
        content: Maybe[List[ContentItem]] = Absent,
        call_id: Maybe[String] = Absent,
        name: Maybe[String] = Absent,
        arguments: Maybe[String] = Absent,
        output: Maybe[String] = Absent
    ) derives Schema
    case class ThreadItem(
        `type`: String,
        text: Maybe[String] = Absent,
        id: Maybe[String] = Absent,
        namespace: Maybe[String] = Absent,
        tool: Maybe[String] = Absent,
        arguments: Maybe[Structure.Value] = Absent
    ) derives Schema
    // Carried by both item/started and item/completed: {threadId, turnId, item}.
    case class ItemNotification(threadId: String, turnId: String, item: ThreadItem) derives Schema
    // The thread/tokenUsage/updated notification (verified live against codex app-server): `total`
    // aggregates the thread, `last` is the most recent provider request. The thread is ephemeral per
    // completion call, so its final `total` IS the kyo turn's usage, already summed across the
    // several provider requests one CLI turn can make. camelCase is the app-server's own vocabulary.
    case class TokenCounts(
        inputTokens: Maybe[Long] = Absent,
        cachedInputTokens: Maybe[Long] = Absent,
        outputTokens: Maybe[Long] = Absent,
        reasoningOutputTokens: Maybe[Long] = Absent
    ) derives Schema
    case class ThreadTokenUsage(total: Maybe[TokenCounts] = Absent, last: Maybe[TokenCounts] = Absent) derives Schema
    case class TokenUsageNotification(threadId: String, turnId: String, tokenUsage: ThreadTokenUsage) derives Schema
    case class ResponseItem(`type`: String, role: Maybe[String] = Absent, content: Maybe[List[ContentItem]] = Absent)
        derives Schema
    case class RawResponseItemCompletedNotification(threadId: String, turnId: String, item: ResponseItem) derives Schema

    def truncateStderr(text: String): String =
        if text.length <= stderrTailMax then text
        else text.takeRight(stderrTailMax)
    end truncateStderr

    def threadStartParams(
        config: Config,
        context: Context,
        workDir: Path,
        dynamicTools: Chunk[DynamicTool]
    )(using Frame): ThreadStartParams =
        ThreadStartParams(
            model = modelOverride(config),
            cwd = Present(workDir.toString),
            ephemeral = true,
            experimentalRawEvents = true,
            baseInstructions = leadingSystem(context),
            approvalPolicy = "never",
            sandbox = "read-only",
            config = CodexServerConfig(
                features = CodexFeatures(plugins = false, apps = false, shell_tool = false),
                plugins = Map.empty,
                skills = Map.empty,
                marketplaces = Map.empty,
                web_search = "disabled"
            ),
            dynamicTools = dynamicTools.toList
        )
    end threadStartParams

    def turnStartParams(
        config: Config,
        threadId: String,
        workDir: Path,
        input: Chunk[TurnInput]
    )(using Frame): TurnStartParams =
        TurnStartParams(
            threadId = threadId,
            input = input.toList,
            model = modelOverride(config),
            cwd = Present(workDir.toString),
            approvalPolicy = "never"
        )

    /** The registered tool specs: every kyo tool, the result tool included, as a real dynamic tool. The
      * result tool advertises the require-all envelope (the `StrictSchema.requireAll` shape every backend
      * shares on its ADVISORY result path; Anthropic's strict form under disableReasoning is the one
      * platform-forced superset) and a user tool its own input schema, so the pair presents one tool contract.
      */
    def dynamicToolSpecs(
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(using Frame): Chunk[DynamicTool] =
        tools.map { tool =>
            val schema =
                if tool.name == Completion.resultToolName then StrictSchema.requireAll(resultSchema)
                else Json.jsonSchema(using tool.inputSchema.asInstanceOf[Schema[Any]])
            DynamicTool("function", tool.name, tool.description, Structure.encode(schema))
        }
    end dynamicToolSpecs

    /** The base instructions: the context's leading system message alone, the same content the OpenAI request
      * carries. Later system messages ride the injected history in place, so no content is hoisted out of
      * order and no instruction tail is appended.
      */
    private def leadingSystem(context: Context): String =
        context.messages.headMaybe match
            case Present(SystemMessage(content)) => content
            case _                               => ""

    def historyMessages(context: Context): Chunk[Message] =
        val body = conversationBody(context)
        body.lastMaybe match
            case Present(_: UserMessage) => body.dropRight(1)
            case _                       => body
    end historyMessages

    /** The live turn input: the current request, then the trailing system directives (floating reminder,
      * forced-turn finalize, stream result directive) as `<system-reminder>` text inputs, the
      * request-then-directives shape the Claude Code wire sends in its single user event. Directives never ride
      * the injected history, so their end-of-context position survives. A body that does not end on a user
      * message gets the synthetic continuation both command wires share (see
      * [[HarnessCompletion.continuationRequest]]).
      */
    def turnInput(context: Context): Chunk[TurnInput] =
        val messages = conversationMessages(context)
        val body     = conversationBody(context)
        val request = body.lastMaybe match
            case Present(UserMessage(content, image)) =>
                Chunk(TurnInput("text", text = Present(content))).concat(
                    image.map(img => Chunk(TurnInput("image", url = Present(s"data:image/jpeg;base64,${img.base64}")))).getOrElse(
                        Chunk.empty
                    )
                )
            case _ =>
                Chunk(TurnInput("text", text = Present(HarnessCompletion.continuationRequest(body))))
        // The suffix is all SystemMessages by construction (HarnessCompletion.trailingSystemCount),
        // so collect is total here.
        request.concat(messages.drop(body.size).collect {
            case SystemMessage(content) => TurnInput("text", text = Present(reminderText(content)))
        })
    end turnInput

    // Everything after the leading system message: NON-trailing later system messages stay IN
    // PLACE (they ride the injected history as user-role reminder items), mirroring the OpenAI
    // request's in-place system messages; trailing directives ride the live turn (see turnInput).
    private def conversationMessages(context: Context): Chunk[Message] =
        context.messages.headMaybe match
            case Present(_: SystemMessage) => context.messages.drop(1)
            case _                         => context.messages

    // The conversation minus the trailing system directives, which ride the live turn input.
    private def conversationBody(context: Context): Chunk[Message] =
        val messages = conversationMessages(context)
        messages.dropRight(HarnessCompletion.trailingSystemCount(messages))

    private def reminderText(content: String): String =
        s"<system-reminder>\n$content\n</system-reminder>"

    private def modelOverride(config: Config): Maybe[String] =
        if config.modelName.isEmpty then Absent else Present(config.modelName)

    def historyItems(messages: Chunk[Message])(using Frame): Chunk[Structure.Value] < Abort[AIGenException] =
        Kyo.foreach(messages) {
            case SystemMessage(content) =>
                // In place, as a USER-role item wrapped in <system-reminder>, the same wrapper the Claude Code
                // harness uses. The app-server offers no working system carrier (verified live: a system-role
                // item is accepted by thread/inject_items but never delivered to the model at any position; a
                // developer-role item is delivered but treated as confidential and the model refuses to
                // reference it), so the user-role item is the realizable in-place carrier.
                Chunk(Structure.encode(ResponseHistoryItem(
                    "message",
                    role = Present("user"),
                    content = Present(List(ContentItem("input_text", text = Present(reminderText(content)))))
                )))
            case UserMessage(content, image) =>
                val blocks =
                    List(ContentItem("input_text", text = Present(content))) ++
                        image.toList.map(img => ContentItem("input_image", image_url = Present(s"data:image/jpeg;base64,${img.base64}")))
                Chunk(Structure.encode(ResponseHistoryItem("message", role = Present("user"), content = Present(blocks))))
            case AssistantMessage(content, calls) =>
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
            case ToolMessage(callId, content) =>
                Chunk(Structure.encode(ResponseHistoryItem(
                    "function_call_output",
                    call_id = Present(callId.id),
                    output = Present(content)
                )))
        }.map(_.flattenChunk)
    end historyItems

    /** Classifies an `item/started` event for the one-round bound: true when a reasoning or assistant-message
      * item starts on this thread and turn (the model resuming with the round's results), and true as the
      * FAIL-SAFE when the params do not decode (a decode miss must never silently disarm the bound). False
      * only when the event positively identifies as something else: another method, another thread or turn, or
      * an item type that keeps the round open (a parallel tool call).
      */
    def startsFollowUp(event: RpcEvent, threadId: String, turnId: String)(using Frame): Boolean =
        event.method == "item/started" && {
            Structure.decode[ItemNotification](event.params) match
                case Result.Success(notification) =>
                    notification.threadId == threadId && notification.turnId == turnId &&
                    (notification.item.`type` == "reasoning" || notification.item.`type` == "agentMessage")
                case _ => true
        }

    def isTurnCompleted(event: RpcEvent, threadId: String, turnId: String)(using Frame): Boolean =
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

    def eventSummary(event: RpcEvent)(using Frame): String =
        val params = Json.encode(event.params)
        val preview =
            if params.length <= 500 then params
            else params.take(500) + "..."
        s"${event.method}: $preview"
    end eventSummary

    def isRetryingError(params: Structure.Value): Boolean =
        params match
            case Structure.Value.Record(fields) =>
                fields.iterator.toMap.get("willRetry").contains(Structure.Value.Bool(true))
            case _ =>
                false
        end match
    end isRetryingError

    /** The turn's messages: each executed user-tool call replayed as its native call/result pair
      * (real wire call ids), then the captured result as a result_tool call carrying the raw
      * arguments verbatim for the eval loop to decode, with the turn's final assistant text riding
      * that message, matching the Claude Code assembly. Without a capture the turn is resultless:
      * the executed pairs plus any final text, and the eval loop iterates or repairs.
      */
    def resultMessages(
        executed: Chunk[ExecutedCall],
        captured: Maybe[(String, String)],
        finalText: Maybe[String]
    )(using Frame): Chunk[Message] =
        val pairs = executed.flatMap { call =>
            val kyoCall = Call(CallId(call.callId), call.name, Json.encode(call.arguments))
            Chunk(
                AssistantMessage("", Chunk(kyoCall)),
                ToolMessage(kyoCall.id, call.output)
            )
        }
        captured match
            case Present((callId, arguments)) =>
                pairs.append(AssistantMessage(
                    finalText.getOrElse(""),
                    Chunk(Call(CallId(callId), Completion.resultToolName, arguments))
                ))
            case Absent =>
                finalText.filter(_.nonEmpty).fold(pairs)(text => pairs.append(AssistantMessage(text)))
        end match
    end resultMessages

    /** This wire's counts already use the module's field vocabulary; the subsets stay their reported
      * values (`Present(0)` is a reported zero, not an absence).
      */
    def usageStats(counts: TokenCounts): AIStats =
        AIStats(
            inputTokens = counts.inputTokens.getOrElse(0L),
            cachedInputTokens = counts.cachedInputTokens,
            outputTokens = counts.outputTokens.getOrElse(0L),
            reasoningOutputTokens = counts.reasoningOutputTokens,
            turns = 1
        )

    def decodeEvent[A: Schema](event: RpcEvent)(using Frame): A < Abort[AIGenException] =
        Structure.decode[A](event.params) match
            case Result.Success(value) => value
            case Result.Failure(err)   => Abort.fail(AIDecodeException(s"Codex event '${event.method}' is invalid: $err"))
            case Result.Panic(ex)      => Abort.panic(ex)

end CodexWire
