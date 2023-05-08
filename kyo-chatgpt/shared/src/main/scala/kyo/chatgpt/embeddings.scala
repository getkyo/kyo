package kyo.chatgpt

import kyo.chatgpt.ais._
import kyo.core._
import kyo.concurrent.fibers._
import kyo.requests._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._

import kyo.chatgpt.ais

object embeddings {

  private case class Request(input: String, model: String)
  private case class Data(embedding: List[Float])
  private case class Usage(prompt_tokens: Int)
  private case class Response(data: List[Data], usage: Usage)

  private given JsonEncoder[Request]  = DeriveJsonEncoder.gen[Request]
  private given JsonDecoder[Data]     = DeriveJsonDecoder.gen[Data]
  private given JsonDecoder[Usage]    = DeriveJsonDecoder.gen[Usage]
  private given JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]

  case class Embedding(tokens: Int, vector: List[Float])

  object Embeddings {

    def apply(text: String, model: String = "text-embedding-ada-002"): Embedding > AIs =
      AIs.iso(Requests.iso(fiber(text, model).flatMap(_.join)))

    def fiber(text: String, model: String = "text-embedding-ada-002"): Fiber[Embedding] > AIs =
      AIs.iso {
        Requests.iso {
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
        }
      }
  }
}
