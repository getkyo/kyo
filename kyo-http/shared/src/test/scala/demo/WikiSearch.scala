package demo

import kyo.*

/** Wikipedia search proxy.
  *
  * Proxies the Wikipedia API to demonstrate query param forwarding, response transformation, and error handling with typed routes.
  */
object WikiSearch extends KyoApp:

    // Wikipedia API response models
    case class WikiSearchResponse(query: WikiQuery) derives Json
    case class WikiQuery(search: List[WikiSearchResult]) derives Json
    case class WikiSearchResult(title: String, snippet: String, wordcount: Int, pageid: Int) derives Json

    case class WikiSummaryResponse(title: String, extract: String, description: Option[String], thumbnail: Option[WikiThumbnail])
        derives Json
    case class WikiThumbnail(source: String, width: Int, height: Int) derives Json

    // Our simplified response models
    case class SearchResult(title: String, snippet: String, wordCount: Int, url: String) derives Json
    case class SearchResponse(query: String, results: List[SearchResult]) derives Json
    case class Summary(title: String, extract: String, description: String, thumbnailUrl: String) derives Json
    case class ApiError(error: String) derives Json

    def searchWikipedia(query: String, limit: Int): SearchResponse < (Async & Abort[HttpException]) =
        val url =
            s"https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${java.net.URLEncoder.encode(query, "UTF-8")}&srlimit=$limit&format=json"
        HttpClient.getJson[WikiSearchResponse](url).map { resp =>
            SearchResponse(
                query,
                resp.query.search.map { r =>
                    SearchResult(
                        r.title,
                        r.snippet.replaceAll("<[^>]*>", ""), // strip HTML tags from snippets
                        r.wordcount,
                        s"https://en.wikipedia.org/wiki/${java.net.URLEncoder.encode(r.title.replace(' ', '_'), "UTF-8")}"
                    )
                }
            )
        }
    end searchWikipedia

    def fetchSummary(title: String): Summary < (Async & Abort[HttpException]) =
        val encoded = java.net.URLEncoder.encode(title, "UTF-8")
        val url     = s"https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
        HttpClient.getJson[WikiSummaryResponse](url).map { r =>
            Summary(
                r.title,
                r.extract,
                r.description.getOrElse(""),
                r.thumbnail.map(_.source).getOrElse("")
            )
        }
    end fetchSummary

    val loggingFilter = HttpFilter.server.logging

    val searchRoute = HttpRoute
        .getRaw("search")
        .filter(loggingFilter)
        .request(
            _.query[String]("q")
                .query[Int]("limit", default = Present(5))
        )
        .response(_.bodyJson[SearchResponse].error[ApiError](HttpStatus.BadRequest))
        .metadata(_.summary("Search Wikipedia articles").tag("search"))
        .handler { req =>
            searchWikipedia(req.fields.q, req.fields.limit).map(HttpResponse.ok(_))
        }

    val summaryRoute = HttpRoute
        .getRaw("summary" / HttpPath.Capture[String]("title"))
        .filter(loggingFilter)
        .response(_.bodyJson[Summary].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Get article summary").tag("articles"))
        .handler { req =>
            fetchSummary(req.fields.title).map(HttpResponse.ok(_))
        }

    val health = HttpHandler.health()

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        HttpServer.init(
            HttpServerConfig.default.port(port).openApi("/openapi.json", "Wikipedia Search Proxy")
        )(searchRoute, summaryRoute, health).map { server =>
            for
                _ <- Console.printLine(s"WikiSearch running on http://localhost:${server.port}")
                _ <- Console.printLine(s"  curl http://localhost:${server.port}/search?q=scala")
                _ <- Console.printLine(s"  curl http://localhost:${server.port}/summary/Scala_(programming_language)")
                _ <- server.await
            yield ()
        }
    }
end WikiSearch
