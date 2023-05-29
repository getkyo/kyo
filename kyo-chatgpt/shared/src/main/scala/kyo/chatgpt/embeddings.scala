package kyo.chatgpt

import kyo.chatgpt.ais._
import kyo._
import kyo.concurrent.fibers._
import kyo.requests._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._

import kyo.chatgpt.ais

object embeddings {

  case class Embedding(tokens: Int, vector: List[Float])

  object Embeddings {
    import Model._

    def apply(text: String, model: String = "text-embedding-ada-002"): Embedding > AIs =
      fiber(text, model).flatMap(_.join)

    def fiber(text: String, model: String = "text-embedding-ada-002"): Fiber[Embedding] > AIs =
      AIs.ApiKey.get.map { key =>
        Requests.fiber(
            _.contentType("application/json")
              .header("Authorization", s"Bearer $key")
              .post(uri"https://api.openai.com/v1/embeddings")
              .body(Request(text, model))
              .response(asJson[Response])
        ).map(f =>
          f.transform { r =>
            r.body match {
              case Left(error) =>
                Fibers.fail(error)
              case Right(value) =>
                Fibers.value(Embedding(value.usage.prompt_tokens, value.data.head.embedding))
            }
          }
        )
      }

    private object Model {
      case class Request(input: String, model: String)
      case class Data(embedding: List[Float])
      case class Usage(prompt_tokens: Int)
      case class Response(data: List[Data], usage: Usage)

      implicit val requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
      implicit val dataDecoder: JsonDecoder[Data]         = DeriveJsonDecoder.gen[Data]
      implicit val usageDecoder: JsonDecoder[Usage]       = DeriveJsonDecoder.gen[Usage]
      implicit val responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
    }
  }
}
