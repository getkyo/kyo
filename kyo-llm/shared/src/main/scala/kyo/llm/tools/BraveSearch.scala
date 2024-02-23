package kyo.llm.tools

import kyo.*
import kyo.llm.*
import sttp.client3.*
import sttp.client3.ziojson.*
import zio.json.*

object BraveSearch extends Tool:

    import model.*

    type In  = String
    type Out = SearchResponse

    val info =
        Info(
            "brave_search",
            "performs a web search using brave.com's API given a search query"
        )

    def run(query: String) =
        for
            key <- BraveSearch.apiKey.get
            _   <- Logs.debug(query)
            res <- Requests[SearchResponse](
                _.contentType("application/json")
                    .header("X-Subscription-Token", key)
                    .get(uri"https://api.search.brave.com/res/v1/web/search?q=$query")
                    .response(asJson[SearchResponse])
            )
            _ <- Logs.debug(res.toJsonPretty)
        yield res

    object apiKey:
        private val local = Locals.init[Option[String]] {
            val apiKeyProp = "BRAVE_SEARCH_API_KEY"
            Option(System.getenv(apiKeyProp))
                .orElse(Option(System.getProperty(apiKeyProp)))
        }

        val get: String < AIs =
            Options.getOrElse(local.get, IOs.fail("No Brave API key found"))

        def let[T, S1, S2](key: String < S1)(f: => T < S2): T < (S1 & S2 & AIs) =
            key.map { k =>
                local.let(Some(k))(f)
            }
    end apiKey

    object model:
        case class SearchResponse(
            faq: Option[FAQ],
            news: Option[News],
            web: Option[Search]
        )
        case class FAQ(results: Option[List[QA]])
        case class QA(question: String, answer: String, title: String)
        case class News(results: Option[List[NewsResult]])
        case class NewsResult(description: Option[String])
        case class Search(results: Option[List[SearchResult]])
        case class SearchResult(description: Option[String], title: String)

        given qaDecoder: JsonDecoder[QA] =
            DeriveJsonDecoder.gen[QA]
        given faqDecoder: JsonDecoder[FAQ] =
            DeriveJsonDecoder.gen[FAQ]
        given newsResultDecoder: JsonDecoder[NewsResult] =
            DeriveJsonDecoder.gen[NewsResult]
        given newsDecoder: JsonDecoder[News] =
            DeriveJsonDecoder.gen[News]
        given searchResultDecoder: JsonDecoder[SearchResult] =
            DeriveJsonDecoder.gen[SearchResult]
        given searchDecoder: JsonDecoder[Search] =
            DeriveJsonDecoder.gen[Search]
        given responseDecoder: JsonDecoder[SearchResponse] =
            DeriveJsonDecoder.gen[SearchResponse]

        given qaEncoder: JsonEncoder[QA] =
            DeriveJsonEncoder.gen[QA]
        given faqEncoder: JsonEncoder[FAQ] =
            DeriveJsonEncoder.gen[FAQ]
        given newsResultEncoder: JsonEncoder[NewsResult] =
            DeriveJsonEncoder.gen[NewsResult]
        given newsEncoder: JsonEncoder[News] =
            DeriveJsonEncoder.gen[News]
        given searchResultEncoder: JsonEncoder[SearchResult] =
            DeriveJsonEncoder.gen[SearchResult]
        given searchEncoder: JsonEncoder[Search] =
            DeriveJsonEncoder.gen[Search]
        given responseEncoder: JsonEncoder[SearchResponse] =
            DeriveJsonEncoder.gen[SearchResponse]
    end model
end BraveSearch
