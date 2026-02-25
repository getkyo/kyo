package demo

import kyo.*

/** Hacker News API proxy.
  *
  * Fetches stories from the official HN API and the Algolia HN Search API. Demonstrates baseUrl config, parallel fetching of individual
  * story details, and typed routes with query params.
  */
object HackerNews extends KyoApp:

    // HN API models
    case class HnItem(
        id: Int,
        title: Option[String],
        url: Option[String],
        by: Option[String],
        score: Option[Int],
        time: Option[Long],
        descendants: Option[Int]
    ) derives Schema

    // Algolia HN Search models
    case class AlgoliaResponse(hits: List[AlgoliaHit]) derives Schema
    case class AlgoliaHit(
        objectID: String,
        title: Option[String],
        url: Option[String],
        author: String,
        points: Option[Int],
        num_comments: Option[Int]
    ) derives Schema

    // Our response models
    case class Story(id: Int, title: String, url: String, author: String, score: Int, comments: Int) derives Schema
    case class SearchStory(id: String, title: String, url: String, author: String, points: Int, comments: Int) derives Schema
    case class ApiError(error: String) derives Schema

    def hnItemToStory(item: HnItem): Story =
        Story(
            item.id,
            item.title.getOrElse("(untitled)"),
            item.url.getOrElse(s"https://news.ycombinator.com/item?id=${item.id}"),
            item.by.getOrElse("unknown"),
            item.score.getOrElse(0),
            item.descendants.getOrElse(0)
        )

    def fetchTopStories(limit: Int): Seq[Story] < (Async & Abort[HttpError]) =
        HttpClient.withConfig(_.timeout(10.seconds)) {
            for
                ids <- HttpClient.getJson[Seq[Int]]("https://hacker-news.firebaseio.com/v0/topstories.json")
                top = ids.take(limit)
                stories <- Async.foreach(top, top.size) { id =>
                    HttpClient.getJson[HnItem](s"https://hacker-news.firebaseio.com/v0/item/$id.json")
                        .map(hnItemToStory)
                }
            yield stories
        }

    def searchStories(query: String, limit: Int): Seq[SearchStory] < (Async & Abort[HttpError]) =
        val url = s"https://hn.algolia.com/api/v1/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}&hitsPerPage=$limit"
        HttpClient.withConfig(_.timeout(10.seconds)) {
            HttpClient.getJson[AlgoliaResponse](url).map { resp =>
                resp.hits.map { hit =>
                    SearchStory(
                        hit.objectID,
                        hit.title.getOrElse("(untitled)"),
                        hit.url.getOrElse(s"https://news.ycombinator.com/item?id=${hit.objectID}"),
                        hit.author,
                        hit.points.getOrElse(0),
                        hit.num_comments.getOrElse(0)
                    )
                }
            }
        }
    end searchStories

    def fetchStory(id: Int): Story < (Async & Abort[HttpError]) =
        HttpClient.withConfig(_.timeout(10.seconds)) {
            HttpClient.getJson[HnItem](s"https://hacker-news.firebaseio.com/v0/item/$id.json")
                .map(hnItemToStory)
        }

    val loggingFilter = HttpFilter.server.logging

    val topRoute = HttpRoute
        .getRaw("top")
        .filter(loggingFilter)
        .request(_.query[Int]("limit", default = Present(10)))
        .response(_.bodyJson[Seq[Story]].error[ApiError](HttpStatus.BadRequest))
        .metadata(_.summary("Top HN stories").tag("stories"))
        .handler { req =>
            fetchTopStories(req.fields.limit).map(HttpResponse.okJson(_))
        }

    val searchRoute = HttpRoute
        .getRaw("search")
        .filter(loggingFilter)
        .request(
            _.query[String]("q")
                .query[Int]("limit", default = Present(10))
        )
        .response(_.bodyJson[Seq[SearchStory]].error[ApiError](HttpStatus.BadRequest))
        .metadata(_.summary("Search HN stories").tag("search"))
        .handler { req =>
            searchStories(req.fields.q, req.fields.limit).map(HttpResponse.okJson(_))
        }

    val storyRoute = HttpRoute
        .getRaw("story" / HttpPath.Capture[Int]("id"))
        .filter(loggingFilter)
        .response(_.bodyJson[Story].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Get story by ID").tag("stories"))
        .handler { req =>
            fetchStory(req.fields.id).map(HttpResponse.okJson(_))
        }

    val health = HttpHandler.health()

    run {
        HttpServer.init(
            HttpServer.Config().port(3005).openApi("/openapi.json", "Hacker News API")
        )(topRoute, searchRoute, storyRoute, health).map { server =>
            for
                _ <- Console.printLine(s"HackerNews API running on http://localhost:${server.port}")
                _ <- Console.printLine("  curl http://localhost:3005/top?limit=5")
                _ <- Console.printLine("  curl http://localhost:3005/search?q=scala")
                _ <- Console.printLine("  curl http://localhost:3005/story/1")
                _ <- server.await
            yield ()
        }
    }
end HackerNews
