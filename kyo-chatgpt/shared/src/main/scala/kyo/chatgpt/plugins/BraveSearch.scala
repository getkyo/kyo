package kyo.chatgpt.plugins

import kyo._
import kyo.locals._
import kyo.options._
import kyo.tries._
import kyo.chatgpt.ais._
import kyo.requests._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import scala.util.Success
import scala.util.Failure

object BraveSearch {

  import model._

  val plugin = Plugins.init[String, SearchResponse](
      "brave_search",
      "performs a web search using brave.com's API given a search query"
  ) { query =>
    ApiKey.get.map { key =>
      Requests(
          _.contentType("application/json")
            .header("X-Subscription-Token", key)
            .get(uri"https://api.search.brave.com/res/v1/web/search?q=$query")
            .response(asJson[SearchResponse])
      ).map(_.body).map {
        case Left(error) =>
          AIs.fail("BraveSearch plugin failed: " + error)
        case Right(value) =>
          value
      }
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
        local.let(Some(k)) {
          Tries.run[SearchResponse, AIs](plugin.call("test")).map {
            case Failure(error) =>
              AIs.fail(s"Invalid Brave API key: $k $error")
            case Success(_) =>
              f
          }
        }
      }
  }

  object model {
    case class SearchResponse(
        discussions: Option[Discussions],
        faq: Option[FAQ],
        infobox: Option[GraphInfobox],
        news: Option[News],
        query: Option[Query],
        web: Option[Search]
    )
    case class Discussions(results: Option[List[DiscussionResult]])
    case class DiscussionResult(data: ForumData)
    case class ForumData(title: String, question: String, top_comment: String)
    case class FAQ(results: Option[List[QA]])
    case class QA(question: String, answer: String, title: String)
    case class GraphInfobox(long_desc: Option[String])
    case class News(results: Option[List[NewsResult]])
    case class NewsResult(description: Option[String])
    case class Query(original: String, altered: Option[String])
    case class Search(results: Option[List[SearchResult]])
    case class SearchResult(description: Option[String], title: String)

    implicit val forumDataDecoder: JsonDecoder[ForumData] =
      DeriveJsonDecoder.gen[ForumData]
    implicit val discussionResultDecoder: JsonDecoder[DiscussionResult] =
      DeriveJsonDecoder.gen[DiscussionResult]
    implicit val discussionsDecoder: JsonDecoder[Discussions] =
      DeriveJsonDecoder.gen[Discussions]
    implicit val qaDecoder: JsonDecoder[QA] =
      DeriveJsonDecoder.gen[QA]
    implicit val faqDecoder: JsonDecoder[FAQ] =
      DeriveJsonDecoder.gen[FAQ]
    implicit val graphInfoboxDecoder: JsonDecoder[GraphInfobox] =
      DeriveJsonDecoder.gen[GraphInfobox]
    implicit val newsResultDecoder: JsonDecoder[NewsResult] =
      DeriveJsonDecoder.gen[NewsResult]
    implicit val newsDecoder: JsonDecoder[News] =
      DeriveJsonDecoder.gen[News]
    implicit val queryDecoder: JsonDecoder[Query] =
      DeriveJsonDecoder.gen[Query]
    implicit val searchResultDecoder: JsonDecoder[SearchResult] =
      DeriveJsonDecoder.gen[SearchResult]
    implicit val searchDecoder: JsonDecoder[Search] =
      DeriveJsonDecoder.gen[Search]
    implicit val responseDecoder: JsonDecoder[SearchResponse] =
      DeriveJsonDecoder.gen[SearchResponse]
  }
}
