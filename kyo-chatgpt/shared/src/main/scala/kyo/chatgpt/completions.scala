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

    case class Result(content: String, call: Option[Call])

    def apply(
        ctx: Context,
        plugins: Set[Plugin[_, _]] = Set.empty,
        constrain: Option[Plugin[_, _]] = None
    ): Result > (IOs with Requests) =
      for {
        config <- Configs.get
        req = Request(ctx, config.model, plugins, constrain)
        _ <- log(req)
        response <- Requests(
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
        (content, call) <-
          response.choices.headOption match {
            case None =>
              IOs.fail("no choices")
            case Some(v) =>
              (
                  v.message.content.getOrElse(""),
                  v.message.function_call.map(c => Call(c.name, c.arguments))
              )
          }
      } yield Result(content, call)
  }

  private object internal {

    private val logger = Loggers.init("Completions")

    def log(r: Request) = {
      import r._
      val indent       = "  "
      val doubleIndent = indent + indent

      val modelLog = s"Model: $model"

      val functionCallLog =
        function_call.map(fc => s"${indent}Function Call: ${fc.name}").getOrElse("")

      val functionsLog = functions.map { funcs =>
        s"${indent}Functions:\n" + funcs.map { func =>
          s"$doubleIndent${func.name}: ${func.description} [Parameters: ${func.parameters}]"
        }.mkString("\n")
      }.getOrElse("")

      val messagesLog = if (messages.nonEmpty) {
        s"Messages:\n" + messages.map { message =>
          val base = s"$doubleIndent${message.role}: ${message.content.getOrElse("")}"
          val funcCall = message.function_call.map(fc =>
            s" [Function Call: ${fc.name}(${fc.arguments})]"
          ).getOrElse("")
          base + funcCall
        }.mkString("\n")
      } else {
        ""
      }

      def log(r: Result) = {
        import r._
        val indent = "  "

        val contentLog = s"Content: $content"

        val callLog = call.map { c =>
          s"${indent}Function Call: ${c.function}(${c.arguments})"
        }.getOrElse("")

        logger.debug(
            List(contentLog, callLog)
              .filter(_.nonEmpty)
              .mkString("\n")
        )
      }

      logger.debug(
          List(modelLog, functionCallLog, functionsLog, messagesLog)
            .filter(_.nonEmpty)
            .mkString("\n")
      )
    }

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
      ): Request =
        val entries =
          ctx.messages.reverse.map(msg =>
            Entry(
                msg.role.name,
                msg.name,
                Some(msg.content),
                msg.call.map(c => FunctionCall(c.arguments, c.function))
            )
          )
        val functions =
          if (plugins.isEmpty) None
          else Some(plugins.map(p => Function(p.description, p.name, p.schema)))
        Request(
            model.name,
            entries,
            constrain.map(p => Name(p.name)),
            functions
        )
    }

    case class Choice(message: Entry)
    case class Response(choices: List[Choice])

    implicit val nameEncoder: JsonEncoder[Name]         = DeriveJsonEncoder.gen[Name]
    implicit val functionEncoder: JsonEncoder[Function] = DeriveJsonEncoder.gen[Function]
    implicit val callEncoder: JsonEncoder[FunctionCall] = DeriveJsonEncoder.gen[FunctionCall]
    implicit val entryEncoder: JsonEncoder[Entry]       = DeriveJsonEncoder.gen[Entry]
    implicit val requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
    implicit val callDecoder: JsonDecoder[FunctionCall] = DeriveJsonDecoder.gen[FunctionCall]
    implicit val entryDecoder: JsonDecoder[Entry]       = DeriveJsonDecoder.gen[Entry]
    implicit val choiceDecoder: JsonDecoder[Choice]     = DeriveJsonDecoder.gen[Choice]
    implicit val responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
  }
}
