package kyo.llm

import kyo.*
import kyo.llm.contexts.*
import kyo.llm.json.Schema
import kyo.llm.tools.*
import scala.concurrent.duration.Duration
import sttp.client3.{Request as _, Response as _, *}
import sttp.client3.ziojson.*
import zio.json.*
import zio.json.internal.Write

object completions:

    case class Completion(content: String, calls: List[Call])

    object Completions:
        import internal.*

        def apply(
            ctx: Context,
            tools: List[Tool] = List.empty,
            constrain: Option[Tool] = None
        ): Completion < Fibers =
            for
                config <- Configs.get
                req = Request(ctx, config, tools, constrain)
                _                <- Logs.debug(req.toJsonPretty)
                response         <- config.completionMeter.run(fetch(config, req))
                _                <- Logs.debug(response.toJsonPretty)
                (content, calls) <- read(response)
            yield new Completion(content, calls)

        private def read(response: Response): (String, List[Call]) < Fibers =
            response.choices.headOption match
                case None =>
                    IOs.fail("no choices")
                case Some(v) =>
                    (
                        v.message.content.getOrElse(""),
                        v.message.tool_calls.getOrElse(Nil).map(c =>
                            Call(CallId(c.id), c.function.name, c.function.arguments)
                        )
                    )

        private def fetch(config: Config, req: Request): Response < Fibers =
            Configs.apiKey.map { key =>
                Requests[Response](
                    _.contentType("application/json")
                        .headers(
                            Map(
                                "Authorization" -> s"Bearer $key"
                            ) ++ config.apiOrg.map("OpenAI-Organization" -> _)
                        )
                        .post(uri"${config.apiUrl}/chat/completions")
                        .body(req)
                        .readTimeout(config.completionTimeout)
                        .response(asJson[Response])
                )
            }
    end Completions

    private object internal:

        case class Name(name: String)
        case class ToolChoice(function: Name, `type`: String = "function")

        case class FunctionCall(arguments: String, name: String)
        case class ToolCall(id: String, function: FunctionCall, `type`: String = "function")

        case class FunctionDef(description: String, name: String, parameters: Schema)
        case class ToolDef(function: FunctionDef, `type`: String = "function")

        sealed trait Entry

        case class MessageEntry(
            role: String,
            content: Option[String],
            tool_calls: Option[List[ToolCall]],
            tool_call_id: Option[String]
        ) extends Entry

        case class VisionEntry(
            content: List[VisionEntry.Content],
            role: String = "user"
        ) extends Entry

        object VisionEntry:
            sealed trait Content
            object Content:
                case class Text(text: String, `type`: String = "text")            extends Content
                case class Image(image_url: String, `type`: String = "image_url") extends Content
        end VisionEntry

        case class Request(
            model: String,
            temperature: Double,
            max_tokens: Option[Int],
            seed: Option[Int],
            messages: List[Entry],
            tools: Option[List[ToolDef]],
            tool_choice: Option[ToolChoice]
        )

        private def toEntry(msg: Message) =
            msg match
                case msg: Message.UserMessage if (msg.imageUrls.nonEmpty) =>
                    VisionEntry(
                        msg.imageUrls.map(VisionEntry.Content.Image(_)) :+
                            VisionEntry.Content.Text(msg.content)
                    )
                case _ =>
                    val toolCalls =
                        msg match
                            case msg: Message.AssistantMessage =>
                                Some(
                                    msg.calls.map(c =>
                                        ToolCall(c.id.id, FunctionCall(c.arguments, c.function))
                                    )
                                ).filter(_.nonEmpty)
                            case _ =>
                                None
                    val callId =
                        msg match
                            case msg: Message.ToolMessage =>
                                Some(msg.callId.id)
                            case _ =>
                                None
                    MessageEntry(msg.role.name, Some(msg.content), toolCalls, callId)

        object Request:
            def apply(
                ctx: Context,
                config: Config,
                tools: List[Tool],
                constrain: Option[Tool]
            ): Request =
                val reminder =
                    ctx.reminder.map(r => Message.SystemMessage(r)).toList
                val entries =
                    (reminder ++ ctx.messages ++ ctx.prompt.map(s => Message.SystemMessage(s)))
                        .map(toEntry).reverse
                val toolDefs =
                    if tools.isEmpty then
                        None
                    else
                        Some(tools.map(p =>
                            ToolDef(FunctionDef(
                                p.info.description,
                                p.info.name,
                                p.json.schema
                            ))
                        ).toList)
                Request(
                    config.model.name,
                    config.temperature,
                    config.maxTokens,
                    config.seed,
                    entries,
                    toolDefs,
                    constrain.map(p => ToolChoice(Name(p.info.name)))
                )
            end apply
        end Request

        case class Choice(message: MessageEntry)
        case class Response(choices: List[Choice])

        given nameEncoder: JsonEncoder[Name]               = DeriveJsonEncoder.gen[Name]
        given functionDefEncoder: JsonEncoder[FunctionDef] = DeriveJsonEncoder.gen[FunctionDef]
        given callEncoder: JsonEncoder[FunctionCall]       = DeriveJsonEncoder.gen[FunctionCall]
        given toolCallEncoder: JsonEncoder[ToolCall]       = DeriveJsonEncoder.gen[ToolCall]
        given toolDefEncoder: JsonEncoder[ToolDef]         = DeriveJsonEncoder.gen[ToolDef]
        given toolChoiceEncoder: JsonEncoder[ToolChoice]   = DeriveJsonEncoder.gen[ToolChoice]
        given msgEntryEncoder: JsonEncoder[MessageEntry]   = DeriveJsonEncoder.gen[MessageEntry]

        given visionEntryContentTextEncoder: JsonEncoder[VisionEntry.Content.Text] =
            DeriveJsonEncoder.gen[VisionEntry.Content.Text]

        given visionEntryContentImageEncoder: JsonEncoder[VisionEntry.Content.Image] =
            DeriveJsonEncoder.gen[VisionEntry.Content.Image]

        given visionContentEncoder: JsonEncoder[VisionEntry.Content] =
            new JsonEncoder[VisionEntry.Content]:
                def unsafeEncode(a: VisionEntry.Content, indent: Option[Int], out: Write): Unit =
                    a match
                        case a: VisionEntry.Content.Text =>
                            visionEntryContentTextEncoder.unsafeEncode(a, indent, out)
                        case a: VisionEntry.Content.Image =>
                            visionEntryContentImageEncoder.unsafeEncode(a, indent, out)

        given visionEntryEncoder: JsonEncoder[VisionEntry] = DeriveJsonEncoder.gen[VisionEntry]

        given entryEncoder: JsonEncoder[Entry] = new JsonEncoder[Entry]:
            def unsafeEncode(a: Entry, indent: Option[Int], out: Write): Unit = a match
                case a: MessageEntry => msgEntryEncoder.unsafeEncode(a, indent, out)
                case a: VisionEntry  => visionEntryEncoder.unsafeEncode(a, indent, out)

        given requestEncoder: JsonEncoder[Request]    = DeriveJsonEncoder.gen[Request]
        given choiceEncoder: JsonEncoder[Choice]      = DeriveJsonEncoder.gen[Choice]
        given responseEncoder: JsonEncoder[Response]  = DeriveJsonEncoder.gen[Response]
        given callDecoder: JsonDecoder[FunctionCall]  = DeriveJsonDecoder.gen[FunctionCall]
        given toolCallDecoder: JsonDecoder[ToolCall]  = DeriveJsonDecoder.gen[ToolCall]
        given entryDecoder: JsonDecoder[MessageEntry] = DeriveJsonDecoder.gen[MessageEntry]
        given choiceDecoder: JsonDecoder[Choice]      = DeriveJsonDecoder.gen[Choice]
        given responseDecoder: JsonDecoder[Response]  = DeriveJsonDecoder.gen[Response]
    end internal
end completions
