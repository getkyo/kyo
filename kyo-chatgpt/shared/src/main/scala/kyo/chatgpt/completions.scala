package kyo.chatgpt

import kyo._
import sttp.client3._
import sttp.client3.ziojson._
import kyo.requests._
import kyo.chatgpt.util.JsonSchema
import kyo.chatgpt.contexts._
import kyo.chatgpt.configs._
import kyo.chatgpt.plugins._
import zio.json._
import scala.concurrent.duration.Duration
import kyo.tries.Tries
import kyo.ios._
import kyo.loggers._
import kyo.chatgpt.completions.Completions.Result

object completions {

  import internal._

  object Completions {

    private val logger = Loggers.init(getClass)

    case class Result(content: String, call: Option[Call])

    def apply(
        ctx: Context,
        plugins: Set[Plugin[_, _]] = Set.empty,
        constrain: Option[Plugin[_, _]] = None
    ): Result > (IOs with Requests) =
      for {
        config <- Configs.get
        req = Request(ctx, config.model, plugins, constrain)
        _               <- logger.debug(req.toJsonPretty)
        response        <- fetch(config, req)
        _               <- logger.debug(response.toJsonPretty)
        (content, call) <- read(response)
      } yield new Result(content, call)

    private def read(response: Response): (String, Option[Call]) > (IOs with Requests) =
      response.choices.headOption match {
        case None =>
          IOs.fail("no choices")
        case Some(v) =>
          (
              v.message.content.getOrElse(""),
              v.message.function_call.map(c => Call(c.name, c.arguments))
          )
      }

    private def fetch(config: Config, req: Request): Response > Requests =
      Requests(
          _.contentType("application/json")
            .header("Authorization", s"Bearer ${config.apiKey}")
            .post(uri"https://api.openai.com/v1/chat/completions")
            .body(req)
            .readTimeout(Duration.Inf)
            .response(asJson[Response])
      ).map(_.body match {
        case Left(error) =>
          Tries.fail(error)
        case Right(value) =>
          value
      })
  }

  private object internal {

    case class Name(name: String)
    case class Function(description: String, name: String, parameters: JsonSchema)
    case class FunctionCall(arguments: String, name: String)
    case class Entry(
        role: String,
        name: Option[String],
        content: Option[String],
        function_call: Option[FunctionCall]
    )
    case class Request(
        model: String,
        messages: List[Entry],
        function_call: Option[Name],
        functions: Option[Set[Function]]
    )

    object Request {
      def apply(
          ctx: Context,
          model: Model,
          plugins: Set[Plugin[_, _]],
          constrain: Option[Plugin[_, _]]
      ): Request = {
        val entries =
          ctx.messages.map(msg =>
            Entry(
                msg.role.name,
                msg.name,
                Some(msg.content),
                msg.call.map(c => FunctionCall(c.arguments, c.function))
            )
          ) ++ ctx.seed.map { seed =>
            Entry(
                Role.system.name,
                None,
                Some(seed),
                None
            )
          }
        val functions =
          if (plugins.isEmpty) None
          else Some(plugins.map(p => Function(p.description, p.name, p.schema)))
        Request(
            model.name,
            entries.reverse,
            constrain.map(p => Name(p.name)),
            functions
        )
      }
    }

    case class Choice(message: Entry)
    case class Response(choices: List[Choice])

    implicit val nameEncoder: JsonEncoder[Name]         = DeriveJsonEncoder.gen[Name]
    implicit val functionEncoder: JsonEncoder[Function] = DeriveJsonEncoder.gen[Function]
    implicit val callEncoder: JsonEncoder[FunctionCall] = DeriveJsonEncoder.gen[FunctionCall]
    implicit val entryEncoder: JsonEncoder[Entry]       = DeriveJsonEncoder.gen[Entry]
    implicit val requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
    implicit val choiceEncoder: JsonEncoder[Choice]     = DeriveJsonEncoder.gen[Choice]
    implicit val responseEncoder: JsonEncoder[Response] = DeriveJsonEncoder.gen[Response]
    implicit val callDecoder: JsonDecoder[FunctionCall] = DeriveJsonDecoder.gen[FunctionCall]
    implicit val entryDecoder: JsonDecoder[Entry]       = DeriveJsonDecoder.gen[Entry]
    implicit val choiceDecoder: JsonDecoder[Choice]     = DeriveJsonDecoder.gen[Choice]
    implicit val responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
  }
}
