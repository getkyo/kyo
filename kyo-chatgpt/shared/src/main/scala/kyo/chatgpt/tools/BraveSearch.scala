package kyo.chatgpt.tools

import kyo._
import kyo.chatgpt.ais._
import kyo.locals._
import kyo.options._
import kyo.requests._
import kyo.tries._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._

import scala.util.Failure
import scala.util.Success
import kyo.chatgpt.tools.Tools

object BraveSearch {

  import model._

  val tool = Tools.init[String, SearchResponse](
      "brave_search",
      "performs a web search using brave.com's API given a search query"
  ) { (ai, query) =>
    ApiKey.get.map { key =>
      Requests[SearchResponse](
          _.contentType("application/json")
            .header("X-Subscription-Token", key)
            .get(uri"https://api.search.brave.com/res/v1/web/search?q=$query")
            .response(asJson[SearchResponse])
      )
    }
  }

  object ApiKey {
    private val local = Locals.init[Option[String]] {
      val apiKeyProp = "BRAVE_SEARCH_API_KEY"
      Option(System.getenv(apiKeyProp))
        .orElse(Option(System.getProperty(apiKeyProp)))
    }

    val get: String > AIs =
      Options.getOrElse(local.get, AIs.fail("No Brave API key found"))

    def let[T, S1, S2](key: String > S1)(f: => T > S2): T > (S1 with S2 with AIs) =
      key.map { k =>
        local.let(Some(k))(f)
      }
  }

  object model {
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

    implicit val qaDecoder: JsonDecoder[QA] =
      DeriveJsonDecoder.gen[QA]
    implicit val faqDecoder: JsonDecoder[FAQ] =
      DeriveJsonDecoder.gen[FAQ]
    implicit val newsResultDecoder: JsonDecoder[NewsResult] =
      DeriveJsonDecoder.gen[NewsResult]
    implicit val newsDecoder: JsonDecoder[News] =
      DeriveJsonDecoder.gen[News]
    implicit val searchResultDecoder: JsonDecoder[SearchResult] =
      DeriveJsonDecoder.gen[SearchResult]
    implicit val searchDecoder: JsonDecoder[Search] =
      DeriveJsonDecoder.gen[Search]
    implicit val responseDecoder: JsonDecoder[SearchResponse] =
      DeriveJsonDecoder.gen[SearchResponse]
  }
}
