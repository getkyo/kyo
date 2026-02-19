package demo

import kyo.*

/** Wikipedia search proxy.
  *
  * Proxies the Wikipedia API to demonstrate query param forwarding, response transformation, and error handling with typed routes.
  *
  * Endpoints: GET /search?q=scala&limit=5 - search Wikipedia articles GET /summary/:title - get article summary
  *
  * Test: curl http://localhost:3004/search?q=scala curl http://localhost:3004/summary/Scala_(programming_language)
  */
object WikiSearch extends KyoApp:

    // Wikipedia API response models
    case class WikiSearchResponse(query: WikiQuery) derives Schema
    case class WikiQuery(search: List[WikiSearchResult]) derives Schema
    case class WikiSearchResult(title: String, snippet: String, wordcount: Int, pageid: Int) derives Schema

    case class WikiSummaryResponse(title: String, extract: String, description: Option[String], thumbnail: Option[WikiThumbnail])
        derives Schema
    case class WikiThumbnail(source: String, width: Int, height: Int) derives Schema

    // Our simplified response models
    case class SearchResult(title: String, snippet: String, wordCount: Int, url: String) derives Schema
    case class SearchResponse(query: String, results: List[SearchResult]) derives Schema
    case class Summary(title: String, extract: String, description: String, thumbnailUrl: String) derives Schema
    case class ApiError(error: String) derives Schema

    def searchWikipedia(query: String, limit: Int): SearchResponse < (Async & Abort[HttpError]) =
        val url =
            s"https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${java.net.URLEncoder.encode(query, "UTF-8")}&srlimit=$limit&format=json"
        HttpFilter.client.addHeader("User-Agent", "kyo-http-demo/1.0").enable {
            HttpClient.get[WikiSearchResponse](url)
        }.map { resp =>
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

    def fetchSummary(title: String): Summary < (Async & Abort[HttpError]) =
        val encoded = java.net.URLEncoder.encode(title, "UTF-8")
        val url     = s"https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
        HttpFilter.client.addHeader("User-Agent", "kyo-http-demo/1.0").enable {
            HttpClient.get[WikiSummaryResponse](url).map { r =>
                Summary(
                    r.title,
                    r.extract,
                    r.description.getOrElse(""),
                    r.thumbnail.map(_.source).getOrElse("")
                )
            }
        }
    end fetchSummary

    val searchRoute = HttpRoute
        .get("search")
        .request(
            _.query[String]("q")
                .query[Int]("limit", default = Some(5))
        )
        .response(_.bodyJson[SearchResponse].error[ApiError](HttpStatus.BadRequest))
        .metadata(_.summary("Search Wikipedia articles").tag("search"))
        .handle { in =>
            searchWikipedia(in.q, in.limit)
        }

    val summaryRoute = HttpRoute
        .get("summary" / HttpPath.Capture[String]("title"))
        .response(_.bodyJson[Summary].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Get article summary").tag("articles"))
        .handle { in =>
            fetchSummary(in.title)
        }

    val health = HttpHandler.health()

    run {
        HttpFilter.server.logging.enable {
            HttpServer.init(
                HttpServer.Config.default.port(3004).openApi("/openapi.json", "Wikipedia Search Proxy")
            )(searchRoute, summaryRoute, health).map { server =>
                for
                    _ <- Console.printLine(s"WikiSearch running on http://localhost:${server.port}")
                    _ <- Console.printLine("  curl http://localhost:3004/search?q=scala")
                    _ <- Console.printLine("  curl http://localhost:3004/summary/Scala_(programming_language)")
                    _ <- server.await
                yield ()
            }
        }
    }
end WikiSearch
