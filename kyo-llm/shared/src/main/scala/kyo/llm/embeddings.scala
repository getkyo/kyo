package kyo.llm

import kyo.*
import sttp.client3.*
import sttp.client3.ziojson.*
import zio.json.*

case class Embedding(vector: List[Float]):
    override def toString() = s"Embedding(${vector.take(3).mkString(", ")}...)"

object Embeddings:
    import internal.*

    def init(weighted: (Int, String)*): Embedding < AIs =
        init {
            val n = weighted.map(_._1).min
            require(n > 0)
            (0 to n).flatMap { _ =>
                weighted.flatMap {
                    case (w, text) => List.fill(w / n)(text)
                }
            }.mkString("\n")
        }

    def init(text: String): Embedding < AIs =
        for
            apiKey <- Configs.apiKey
            config <- Configs.get
            req = Request(text, model)
            _ <- Logs.debug(req.toJsonPretty)
            res <-
                config.embeddingMeter.run {
                    Requests[Response](
                        _.contentType("application/json")
                            .header("Authorization", s"Bearer $apiKey")
                            .post(uri"${config.apiUrl}/v1/embeddings")
                            .readTimeout(config.embeddingTimeout)
                            .body(req)
                            .response(asJson[Response])
                    )
                }
            _ <- Logs.debug(res.copy(data =
                res.data.map(d => d.copy(embedding = d.embedding.take(3)))
            ).toJsonPretty)
        yield Embedding(res.data.head.embedding)

    private object internal:

        val model: String = "text-embedding-ada-002"

        case class Request(input: String, model: String)
        case class Data(embedding: List[Float])
        case class Usage(prompt_tokens: Int)
        case class Response(data: List[Data], usage: Usage)

        given requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
        given dataEncoder: JsonEncoder[Data]         = DeriveJsonEncoder.gen[Data]
        given usageEncoder: JsonEncoder[Usage]       = DeriveJsonEncoder.gen[Usage]
        given responseEncoder: JsonEncoder[Response] = DeriveJsonEncoder.gen[Response]
        given dataDecoder: JsonDecoder[Data]         = DeriveJsonDecoder.gen[Data]
        given usageDecoder: JsonDecoder[Usage]       = DeriveJsonDecoder.gen[Usage]
        given responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
    end internal
end Embeddings
