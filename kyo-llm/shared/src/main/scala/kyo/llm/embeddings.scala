package kyo.llm

import kyo._
import kyo.llm.ais
import kyo.llm.ais._
import kyo.llm.configs._
import kyo.concurrent.fibers._
import kyo.ios._
import kyo.requests._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import kyo.loggers.Loggers

object embeddings {

  case class Embedding(vector: List[Float]) {
    override def toString() = s"Embedding(${vector.take(3).mkString(", ")}...)"
  }

  object Embeddings {
    import internal._

    private val logger = Loggers.init("kyo.llm.embeddings")

    def init(text: String, model: String = "text-embedding-ada-002"): Embedding > AIs =
      for {
        apiKey <- Configs.apiKey
        config <- Configs.get
        req = Request(text, model)
        _ <- logger.debug(req.toJsonPretty)
        res <-
          config.embeddingsMeter.run {
            Requests[Response](
                _.contentType("application/json")
                  .header("Authorization", s"Bearer $apiKey")
                  .post(uri"${config.apiUrl}/v1/embeddings")
                  .body(req)
                  .response(asJson[Response])
            )
          }
        _ <- logger.debug(res.copy(data =
          res.data.map(d => d.copy(embedding = d.embedding.take(3)))
        ).toJsonPretty)
      } yield {
        Embedding(res.data.head.embedding)
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
