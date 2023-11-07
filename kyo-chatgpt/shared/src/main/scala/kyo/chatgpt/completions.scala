package kyo.chatgpt

import kyo._
import kyo.chatgpt.configs._
import kyo.chatgpt.contexts._
import kyo.chatgpt.tools._
import kyo.chatgpt.util.JsonSchema
import kyo.ios._
import kyo.loggers._
import kyo.requests._
import kyo.tries._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._

import scala.concurrent.duration.Duration

object completions {

  import internal.{Request, Response}

  object Completions {

    private val logger = Loggers.init(getClass)

    case class Result(content: String, toolCalls: List[ToolCall])

    def apply(
        ctx: Context,
        tools: Set[Tool[_, _]] = Set.empty,
        constrain: Option[Tool[_, _]] = None
    ): Result > (IOs with Requests) =
      for {
        config <- Configs.get
        req = Request(ctx, config.model, config.temperature, tools, constrain)
        _               <- logger.debug(req.toJsonPretty)
        response        <- fetch(config, req)
        _               <- logger.debug(response.toJsonPretty)
        (content, call) <- read(response)
      } yield new Result(content, call)

    private def read(response: Response): (String, List[ToolCall]) > (IOs with Requests) =
      response.choices.headOption match {
        case None =>
          IOs.fail("no choices")
        case Some(v) =>
          (
              v.message.content.getOrElse(""),
              v.message.tool_calls.getOrElse(Nil).map(c =>
                ToolCall(c.id, c.function.name, c.function.arguments)
              )
          )
      }

    private def fetch(config: Config, req: Request): Response > Requests =
      Requests[Response](
          _.contentType("application/json")
            .header("Authorization", s"Bearer ${config.apiKey}")
            .post(uri"https://api.openai.com/v1/chat/completions")
            .body(req)
            .readTimeout(Duration.Inf)
            .response(asJson[Response])
      )
  }

  private object internal {

    case class Name(name: String)
    case class ToolChoice(function: Name, `type`: String = "function")

    case class FunctionCall(arguments: String, name: String)
    case class ToolCall(id: String, function: FunctionCall, `type`: String = "function")

    case class FunctionDef(description: String, name: String, parameters: JsonSchema)
    case class ToolDef(function: FunctionDef, `type`: String = "function")

    case class Entry(
        role: String,
        content: Option[String],
        tool_calls: Option[List[ToolCall]],
        tool_call_id: Option[String]
    )
    case class Request(
        model: String,
        temperature: Double,
        messages: List[Entry],
        tools: Option[List[ToolDef]],
        tool_choice: Option[ToolChoice]
    )

    private def toEntry(msg: Message) = {
      val toolCalls =
        msg match {
          case msg: Message.AssistantMessage =>
            Some(
                msg.toolCalls.map(c => ToolCall(c.id, FunctionCall(c.arguments, c.function)))
            ).filter(_.nonEmpty)
          case _ =>
            None
        }
      val toolCallId =
        msg match {
          case msg: Message.ToolMessage =>
            Some(msg.toolCallId)
          case _ =>
            None
        }
      Entry(msg.role.name, Some(msg.content), toolCalls, toolCallId)
    }

    object Request {
      def apply(
          ctx: Context,
          model: Model,
          temperature: Double,
          tools: Set[Tool[_, _]],
          constrain: Option[Tool[_, _]]
      ): Request = {
        val entries: List[Entry] =
          (ctx.messages ++ ctx.seed.map(s => Message.SystemMessage(s)))
            .map(toEntry).reverse
        val toolDefs =
          if (tools.isEmpty) None
          else Some(tools.map(p => ToolDef(FunctionDef(p.description, p.name, p.schema))).toList)
        Request(
            model.name,
            temperature,
            entries,
            toolDefs,
            constrain.map(p => ToolChoice(Name(p.name)))
        )
      }
    }

    case class Choice(message: Entry)
    case class Response(choices: List[Choice])

    implicit val nameEncoder: JsonEncoder[Name]               = DeriveJsonEncoder.gen[Name]
    implicit val functionDefEncoder: JsonEncoder[FunctionDef] = DeriveJsonEncoder.gen[FunctionDef]
    implicit val callEncoder: JsonEncoder[FunctionCall]       = DeriveJsonEncoder.gen[FunctionCall]
    implicit val toolCallEncoder: JsonEncoder[ToolCall]       = DeriveJsonEncoder.gen[ToolCall]
    implicit val entryEncoder: JsonEncoder[Entry]             = DeriveJsonEncoder.gen[Entry]
    implicit val toolDefEncoder: JsonEncoder[ToolDef]         = DeriveJsonEncoder.gen[ToolDef]
    implicit val toolChoiceEncoder: JsonEncoder[ToolChoice]   = DeriveJsonEncoder.gen[ToolChoice]
    implicit val requestEncoder: JsonEncoder[Request]         = DeriveJsonEncoder.gen[Request]
    implicit val choiceEncoder: JsonEncoder[Choice]           = DeriveJsonEncoder.gen[Choice]
    implicit val responseEncoder: JsonEncoder[Response]       = DeriveJsonEncoder.gen[Response]
    implicit val callDecoder: JsonDecoder[FunctionCall]       = DeriveJsonDecoder.gen[FunctionCall]
    implicit val toolCallDecoder: JsonDecoder[ToolCall]       = DeriveJsonDecoder.gen[ToolCall]
    implicit val entryDecoder: JsonDecoder[Entry]             = DeriveJsonDecoder.gen[Entry]
    implicit val choiceDecoder: JsonDecoder[Choice]           = DeriveJsonDecoder.gen[Choice]
    implicit val responseDecoder: JsonDecoder[Response]       = DeriveJsonDecoder.gen[Response]
  }
}
