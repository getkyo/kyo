package kyo

import kyo.ais._
import kyo.core._
import kyo.requests._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._

object vectors {

  private case class Request(input: String, model: String)
  private case class Data(embedding: List[Float])
  private case class Response(data: List[Data])

  private given JsonEncoder[Request]  = DeriveJsonEncoder.gen[Request]
  private given JsonDecoder[Data]     = DeriveJsonDecoder.gen[Data]
  private given JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]

  case class Vector(values: List[Float])

  object Vectors {

    def embed(text: String, model: String = "text-embedding-ada-002"): Vector > AIs =
      AIs.iso {
        Requests(
            _.contentType("application/json")
              .header("Authorization", s"Bearer ${ais.apiKey}")
              .post(uri"https://api.openai.com/v1/embeddings")
              .body(Request(text, model))
              .response(asJson[Response])
        )(_.body match {
          case Left(error) =>
            AIs.fail(error)
          case Right(value) =>
            Vector(value.data.head.embedding)
        })
      }
  }
}
