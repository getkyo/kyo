package kyo.chatgpt

import kyo._
import kyo.chatgpt.ais
import kyo.chatgpt.ais._
import kyo.chatgpt.configs._
import kyo.concurrent.fibers._
import kyo.ios._
import kyo.requests._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import kyo.loggers._

object embeddings {

  case class Embedding(tokens: Int, vector: List[Float])

  object Embeddings {
    import internal._

    private val logger = Loggers.init(getClass)

    def init(text: String, model: String = "text-embedding-ada-002"): Embedding > AIs =
      for {
        apiKey <- Configs.apiKey
        config <- Configs.get
        req = Request(text, model)
        _ <- logger.debug(req.toJsonPretty)
        res <- Requests[Response](
            _.contentType("application/json")
              .header("Authorization", s"Bearer $apiKey")
              .post(uri"${config.apiUrl}/v1/embeddings")
              .body(req)
              .response(asJson[Response])
        )
        _ <- logger.debug(res.toJsonPretty)
      } yield {
        Embedding(res.usage.prompt_tokens, res.data.head.embedding)
      }

    private object internal {
      case class Request(input: String, model: String)
      case class Data(embedding: List[Float])
      case class Usage(prompt_tokens: Int)
      case class Response(data: List[Data], usage: Usage)

      implicit val requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
      implicit val dataEncoder: JsonEncoder[Data]         = DeriveJsonEncoder.gen[Data]
      implicit val usageEncoder: JsonEncoder[Usage]       = DeriveJsonEncoder.gen[Usage]
      implicit val responseEncoder: JsonEncoder[Response] = DeriveJsonEncoder.gen[Response]
      implicit val dataDecoder: JsonDecoder[Data]         = DeriveJsonDecoder.gen[Data]
      implicit val usageDecoder: JsonDecoder[Usage]       = DeriveJsonDecoder.gen[Usage]
      implicit val responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
    }
  }
}
