package demo

import kyo.*

/** Paste bin with content-addressed storage and HTTP caching.
  *
  * Demonstrates: HttpPath.Rest (catch-all path), contentDisposition, etag, cacheControl, HttpResponse.notModified (304), HEAD for existence
  * check, basicAuth on delete endpoint, OpenAPI metadata with operationId and description.
  */
object PasteBin extends KyoApp:

    case class PasteInfo(slug: String, size: Int) derives Schema
    case class PasteList(pastes: List[PasteInfo]) derives Schema
    case class CreatePaste(slug: String, content: String) derives Schema
    case class ApiError(error: String) derives Schema

    case class Paste(content: String, etag: String)

    def computeEtag(content: String): String =
        val hash = content.hashCode.toHexString
        s""""paste-$hash""""

    val serverFilter = HttpFilter.server.logging
        .andThen(HttpFilter.server.securityHeaders(
            hsts = Present(365.days),
            csp = Present("default-src 'self'")
        ))

    val adminAuth = HttpFilter.server.basicAuth((user, pass) => user == "admin" && pass == "paste2026")

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            storeRef <- AtomicRef.init(Map.empty[String, Paste])

            // POST /p — create paste
            create = HttpRoute
                .postRaw("p")
                .filter(serverFilter)
                .request(_.bodyJson[CreatePaste])
                .response(_.bodyJson[PasteInfo].status(HttpStatus.Created))
                .metadata(
                    _.summary("Create a paste")
                        .description("Store text content under a slug. Slug can contain slashes for hierarchical organization.")
                        .operationId("createPaste")
                        .tag("pastes")
                )
                .handler { req =>
                    val input = req.fields.body
                    val etag  = computeEtag(input.content)
                    for _ <- storeRef.updateAndGet(_ + (input.slug -> Paste(input.content, etag)))
                    yield HttpResponse.okJson(PasteInfo(input.slug, input.content.length))
                }

            // GET /p/... — retrieve paste with ETag support
            get = HttpRoute
                .getRaw("p" / HttpPath.Rest("slug"))
                .filter(serverFilter)
                .request(_.headerOpt[String]("if-none-match"))
                .response(_.bodyText.error[ApiError](HttpStatus.NotFound))
                .metadata(
                    _.summary("Retrieve a paste")
                        .description("Returns paste content. Supports ETag/If-None-Match for conditional requests.")
                        .operationId("getPaste")
                        .tag("pastes")
                )
                .handler { req =>
                    for store <- storeRef.get
                    yield store.get(req.fields.slug) match
                        case Some(paste) =>
                            req.fields.`if-none-match` match
                                case Present(clientEtag) if clientEtag == paste.etag =>
                                    HttpResponse.halt(HttpResponse.notModified.etag(paste.etag))
                                case _ =>
                                    HttpResponse.okText(paste.content)
                                        .etag(paste.etag)
                                        .cacheControl("public, max-age=3600")
                                        .contentDisposition(req.fields.slug.replace('/', '_') + ".txt")
                        case None =>
                            Abort.fail(ApiError(s"Paste not found: ${req.fields.slug}"))
                }

            // HEAD /p/... — check existence
            head = HttpRoute
                .headRaw("p" / HttpPath.Rest("slug"))
                .filter(serverFilter)
                .metadata(_.summary("Check paste existence").operationId("headPaste").tag("pastes"))
                .handler { req =>
                    for store <- storeRef.get
                    yield store.get(req.fields.slug) match
                        case Some(paste) =>
                            HttpResponse.ok
                                .etag(paste.etag)
                                .setHeader("Content-Length", paste.content.length.toString)
                        case None => HttpResponse.notFound
                }

            // DELETE /p/... — requires basic auth
            delete = HttpRoute
                .deleteRaw("p" / HttpPath.Rest("slug"))
                .request(_.headerOpt[String]("authorization"))
                .filter(serverFilter.andThen(adminAuth))
                .response(_.status(HttpStatus.NoContent))
                .metadata(_.summary("Delete a paste").operationId("deletePaste").tag("pastes"))
                .handler { req =>
                    storeRef.updateAndGet(_ - req.fields.slug).map(_ => HttpResponse.noContent)
                }

            // GET /pastes — list all
            list = HttpHandler.getJson[PasteList]("pastes") { _ =>
                for store <- storeRef.get
                yield PasteList(store.toList.map { case (slug, paste) =>
                    PasteInfo(slug, paste.content.length)
                })
            }

            health = HttpHandler.health()
            server <- HttpServer.init(
                HttpServer.Config().port(port).openApi("/openapi.json", "Paste Bin")
            )(create, get, head, delete, list, health)
            _ <- Console.printLine(s"PasteBin running on http://localhost:${server.port}")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/p -H "Content-Type: application/json" -d '{"slug":"hello/world","content":"Hello from PasteBin!"}'"""
            )
            _ <- Console.printLine(s"  curl -v http://localhost:${server.port}/p/hello/world")
            _ <- Console.printLine(s"  curl -I http://localhost:${server.port}/p/hello/world")
            _ <- Console.printLine(s"""  curl -u admin:paste2026 -X DELETE http://localhost:${server.port}/p/hello/world""")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/pastes")
            _ <- server.await
        yield ()
        end for
    }
end PasteBin
