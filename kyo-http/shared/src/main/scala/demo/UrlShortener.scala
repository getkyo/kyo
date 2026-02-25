package demo

import kyo.*

/** URL shortener with rate limiting, redirects, and visit tracking.
  *
  * Demonstrates: HttpResponse.movedPermanently (301 redirects), Meter.initRateLimiter + HttpFilter.server.rateLimit, response cookies
  * (visit-count), request cookies (returning visitor detection), HEAD method for link checking, OpenAPI.
  */
object UrlShortener extends KyoApp:

    case class ShortenRequest(url: String) derives Schema
    case class ShortenResponse(code: String, originalUrl: String) derives Schema
    case class LinkStats(code: String, url: String, visits: Int) derives Schema
    case class ApiError(error: String) derives Schema

    case class LinkEntry(url: String, visits: Int)
    case class Store(links: Map[String, LinkEntry], nextId: Int)

    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"

    def toCode(id: Int): String =
        var n   = id
        val buf = new StringBuilder
        while n > 0 do
            buf.append(chars(n % chars.length))
            n = n / chars.length
        if buf.isEmpty then "a" else buf.toString
    end toCode

    val serverFilter = HttpFilter.server.requestId
        .andThen(HttpFilter.server.logging)
        .andThen(HttpFilter.server.securityHeaders)

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            storeRef    <- AtomicRef.init(Store(Map.empty, 1))
            rateLimiter <- Meter.initRateLimiter(10, 1.second)

            rateLimitFilter = HttpFilter.server.rateLimit(rateLimiter, 1)

            // POST /shorten — rate-limited
            shorten = HttpRoute
                .postRaw("shorten")
                .filter(serverFilter.andThen(rateLimitFilter))
                .request(_.bodyJson[ShortenRequest])
                .response(_.bodyJson[ShortenResponse].status(HttpStatus.Created).error[ApiError](HttpStatus.BadRequest))
                .metadata(_.summary("Shorten a URL").tag("shortener"))
                .handler { req =>
                    val url = req.fields.body.url
                    if !url.startsWith("http://") && !url.startsWith("https://") then
                        Abort.fail(ApiError("URL must start with http:// or https://"))
                    else
                        for store <- storeRef.updateAndGet { s =>
                                val code = toCode(s.nextId)
                                Store(s.links + (code -> LinkEntry(url, 0)), s.nextId + 1)
                            }
                        yield
                            val code = toCode(store.nextId - 1)
                            HttpResponse.okJson(ShortenResponse(code, url))
                    end if
                }

            // GET /:code — 301 redirect
            redirect = HttpRoute
                .getRaw(HttpPath.Capture[String]("code"))
                .filter(serverFilter)
                .request(_.cookieOpt[String]("visits"))
                .response(_.cookie[String]("visits"))
                .metadata(_.summary("Follow short link").tag("shortener"))
                .handler { req =>
                    for store <- storeRef.updateAndGet { s =>
                            s.links.get(req.fields.code) match
                                case Some(e) => Store(s.links + (req.fields.code -> e.copy(visits = e.visits + 1)), s.nextId)
                                case None    => s
                        }
                    yield store.links.get(req.fields.code) match
                        case Some(entry) =>
                            val newCount = scala.util.Try(req.fields.visits.getOrElse("0").toInt + 1).getOrElse(1)
                            HttpResponse.movedPermanently(entry.url)
                                .addField("visits", HttpCookie(newCount.toString))
                        case None =>
                            HttpResponse.halt(HttpResponse.notFound)
                }

            // HEAD /:code — check without following
            head = HttpRoute
                .headRaw(HttpPath.Capture[String]("code"))
                .filter(serverFilter)
                .metadata(_.summary("Check short link").tag("shortener"))
                .handler { req =>
                    for store <- storeRef.get
                    yield store.links.get(req.fields.code) match
                        case Some(entry) =>
                            HttpResponse.ok
                                .setHeader("X-Original-Url", entry.url)
                                .setHeader("X-Visit-Count", entry.visits.toString)
                        case None => HttpResponse.notFound
                }

            // GET /stats/:code
            stats = HttpRoute
                .getRaw("stats" / HttpPath.Capture[String]("code"))
                .filter(serverFilter)
                .response(_.bodyJson[LinkStats].error[ApiError](HttpStatus.NotFound))
                .metadata(_.summary("Link statistics").tag("stats"))
                .handler { req =>
                    for store <- storeRef.get
                    yield store.links.get(req.fields.code) match
                        case Some(entry) => HttpResponse.okJson(LinkStats(req.fields.code, entry.url, entry.visits))
                        case None        => Abort.fail(ApiError(s"Link not found: ${req.fields.code}"))
                }

            health = HttpHandler.health()
            server <- HttpServer.init(
                HttpServer.Config().port(port).openApi("/openapi.json", "URL Shortener")
            )(shorten, redirect, head, stats, health)
            _ <- Console.printLine(s"UrlShortener running on http://localhost:${server.port}")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/shorten -H "Content-Type: application/json" -d '{"url":"https://scala-lang.org"}'"""
            )
            _ <- Console.printLine(s"  curl -v -L http://localhost:${server.port}/a")
            _ <- Console.printLine(s"  curl -I http://localhost:${server.port}/a")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/stats/a")
            _ <- server.await
        yield ()
        end for
    }
end UrlShortener
