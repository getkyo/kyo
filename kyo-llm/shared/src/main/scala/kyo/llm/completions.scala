package kyo.llm

import kyo._
import kyo.llm.configs._
import kyo.llm.contexts._
import kyo.llm.agents._
import kyo.llm.ais._
import kyo.llm.util.JsonSchema
import kyo.ios._
import kyo.requests._
import kyo.tries._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._

import scala.concurrent.duration.Duration
import kyo.llm.contexts.Message.UserMessage
import zio.json.internal.Write
import kyo.logs._

object completions {

  import internal.{Request, Response}

  object Completions {

    case class Result(content: String, calls: List[Call])

    def apply(
        ctx: Context,
        agents: Set[Agent] = Set.empty,
        constrain: Option[Agent] = None
    ): Result > (IOs with Requests) =
      for {
        config <- Configs.get
        req = Request(ctx, config, agents, constrain)
        _                <- Logs.debug(req.toJsonPretty)
        response         <- config.completionsMeter.run(fetch(config, req))
        _                <- Logs.debug(response.toJsonPretty)
        (content, calls) <- read(response)
      } yield new Result(content, calls)

    private def read(response: Response): (String, List[Call]) > (IOs with Requests) =
      response.choices.headOption match {
        case None =>
          IOs.fail("no choices")
        case Some(v) =>
          (
              v.message.content.getOrElse(""),
              v.message.tool_calls.getOrElse(Nil).map(c =>
                Call(CallId(c.id), c.function.name, c.function.arguments)
              )
          )
      }

    private def fetch(config: Config, req: Request): Response > Requests =
      Configs.apiKey.map { key =>
        Configs.get.map { cfg =>
          Requests[Response](
              _.contentType("application/json")
                .headers(
                    Map(
                        "Authorization" -> s"Bearer $key"
                    ) ++ cfg.apiOrg.map("OpenAI-Organization" -> _)
                )
                .post(uri"${cfg.apiUrl}/v1/chat/completions")
                .body(req)
                .readTimeout(Duration.Inf)
                .response(asJson[Response])
          )
        }
      }
  }

  private object internal {

    case class Name(name: String)
    case class ToolChoice(function: Name, `type`: String = "function")

    case class FunctionCall(arguments: String, name: String)
    case class ToolCall(id: String, function: FunctionCall, `type`: String = "function")

    case class FunctionDef(description: String, name: String, parameters: JsonSchema)
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

    object VisionEntry {
      sealed trait Content
      object Content {
        case class Text(text: String, `type`: String = "text")            extends Content
        case class Image(image_url: String, `type`: String = "image_url") extends Content
      }
    }

    case class Request(
        model: String,
        temperature: Double,
        max_tokens: Option[Int],
        seed: Option[Int],
        messages: List[Entry],
        tools: Option[List[ToolDef]],
        tool_choice: Option[ToolChoice]
    )

    private def toEntry(msg: Message) = {
      msg match {
        case msg: UserMessage if (msg.imageUrls.nonEmpty) =>
          VisionEntry(
              msg.imageUrls.map(VisionEntry.Content.Image(_)) :+
                VisionEntry.Content.Text(msg.content)
          )
        case _ =>
          val agentCalls =
            msg match {
              case msg: Message.AssistantMessage =>
                Some(
                    msg.calls.map(c =>
                      ToolCall(c.id.id, FunctionCall(c.arguments, c.function))
                    )
                ).filter(_.nonEmpty)
              case _ =>
                None
            }
          val callId =
            msg match {
              case msg: Message.AgentMessage =>
                Some(msg.callId.id)
              case _ =>
                None
            }
          MessageEntry(msg.role.name, Some(msg.content), agentCalls, callId)
      }
    }

    object Request {
      def apply(
          ctx: Context,
          config: Config,
          agents: Set[Agent],
          constrain: Option[Agent]
      ): Request = {
        val reminder =
          ctx.reminder.map(r =>
            Message.SystemMessage(
                p"""
                  IMPORTANT REMINDER
                  ==================
                  $r
                """
            )
          ).toList
        val entries =
          (reminder ++ ctx.messages ++ ctx.seed.map(s => Message.SystemMessage(s)))
            .map(toEntry).reverse
        val agentDefs =
          if (agents.isEmpty)
            None
          else
            Some(agents.map(p =>
              ToolDef(FunctionDef(
                  p.info.description,
                  p.info.name,
                  p.info.schema
              ))
            ).toList)
        Request(
            config.model.name,
            config.temperature,
            config.maxTokens,
            config.seed,
            entries,
            agentDefs,
            constrain.map(p => ToolChoice(Name(p.info.name)))
        )
      }
    }

    case class Choice(message: MessageEntry)
    case class Response(choices: List[Choice])

    implicit val nameEncoder: JsonEncoder[Name]               = DeriveJsonEncoder.gen[Name]
    implicit val functionDefEncoder: JsonEncoder[FunctionDef] = DeriveJsonEncoder.gen[FunctionDef]
    implicit val callEncoder: JsonEncoder[FunctionCall]       = DeriveJsonEncoder.gen[FunctionCall]
    implicit val toolCallEncoder: JsonEncoder[ToolCall]       = DeriveJsonEncoder.gen[ToolCall]
    implicit val toolDefEncoder: JsonEncoder[ToolDef]         = DeriveJsonEncoder.gen[ToolDef]
    implicit val toolChoiceEncoder: JsonEncoder[ToolChoice]   = DeriveJsonEncoder.gen[ToolChoice]
    implicit val msgEntryEncoder: JsonEncoder[MessageEntry]   = DeriveJsonEncoder.gen[MessageEntry]

    implicit val visionEntryContentTextEncoder: JsonEncoder[VisionEntry.Content.Text] =
      DeriveJsonEncoder.gen[VisionEntry.Content.Text]

    implicit val visionEntryContentImageEncoder: JsonEncoder[VisionEntry.Content.Image] =
      DeriveJsonEncoder.gen[VisionEntry.Content.Image]

    implicit val visionContentEncoder: JsonEncoder[VisionEntry.Content] =
      new JsonEncoder[VisionEntry.Content] {
        def unsafeEncode(a: VisionEntry.Content, indent: Option[Int], out: Write): Unit =
          a match {
            case a: VisionEntry.Content.Text =>
              visionEntryContentTextEncoder.unsafeEncode(a, indent, out)
            case a: VisionEntry.Content.Image =>
              visionEntryContentImageEncoder.unsafeEncode(a, indent, out)
          }
      }

    implicit val visionEntryEncoder: JsonEncoder[VisionEntry] = DeriveJsonEncoder.gen[VisionEntry]

    implicit val entryEncoder: JsonEncoder[Entry] = new JsonEncoder[Entry] {
      def unsafeEncode(a: Entry, indent: Option[Int], out: Write): Unit = a match {
        case a: MessageEntry => msgEntryEncoder.unsafeEncode(a, indent, out)
        case a: VisionEntry  => visionEntryEncoder.unsafeEncode(a, indent, out)
      }
    }

    implicit val requestEncoder: JsonEncoder[Request]    = DeriveJsonEncoder.gen[Request]
    implicit val choiceEncoder: JsonEncoder[Choice]      = DeriveJsonEncoder.gen[Choice]
    implicit val responseEncoder: JsonEncoder[Response]  = DeriveJsonEncoder.gen[Response]
    implicit val callDecoder: JsonDecoder[FunctionCall]  = DeriveJsonDecoder.gen[FunctionCall]
    implicit val toolCallDecoder: JsonDecoder[ToolCall]  = DeriveJsonDecoder.gen[ToolCall]
    implicit val entryDecoder: JsonDecoder[MessageEntry] = DeriveJsonDecoder.gen[MessageEntry]
    implicit val choiceDecoder: JsonDecoder[Choice]      = DeriveJsonDecoder.gen[Choice]
    implicit val responseDecoder: JsonDecoder[Response]  = DeriveJsonDecoder.gen[Response]
  }
}
