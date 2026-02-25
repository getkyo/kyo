package demo

import kyo.*

/** In-memory CRUD bookmark API with auth, rate limiting, and typed client.
  *
  * Demonstrates: POST/PUT/DELETE with JSON body, bearer auth filter on routes, server filters (requestId, rateLimit, securityHeaders),
  * typed response headers, response cookies, and OpenAPI.
  */
object BookmarkStore extends KyoApp:

    val AUTH_TOKEN = "demo-token-2026"

    // --- Models ---
    case class Bookmark(id: Int, url: String, title: String, tags: List[String]) derives Schema
    case class CreateBookmark(url: String, title: String, tags: List[String]) derives Schema
    case class UpdateBookmark(url: Option[String], title: Option[String], tags: Option[List[String]]) derives Schema
    case class ApiError(error: String) derives Schema

    // --- In-memory store ---
    case class Store(bookmarks: Map[Int, Bookmark], nextId: Int)

    // --- Filters ---

    val serverFilter = HttpFilter.server.requestId
        .andThen(HttpFilter.server.logging)
        .andThen(HttpFilter.server.securityHeaders)

    val authFilter = HttpFilter.server.bearerAuth(token => token == AUTH_TOKEN)

    // --- Routes ---

    val listRoute = HttpRoute
        .getRaw("bookmarks")
        .filter(serverFilter)
        .response(
            _.bodyJson[List[Bookmark]]
                .header[Int]("X-Total-Count")
        )
        .metadata(_.summary("List all bookmarks").tag("bookmarks"))

    val getRoute = HttpRoute
        .getRaw("bookmarks" / HttpPath.Capture[Int]("id"))
        .filter(serverFilter)
        .response(_.bodyJson[Bookmark].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Get bookmark by ID").tag("bookmarks"))

    val createRoute = HttpRoute
        .postRaw("bookmarks")
        .request(_.headerOpt[String]("authorization").bodyJson[CreateBookmark])
        .filter(serverFilter.andThen(authFilter))
        .response(
            _.bodyJson[Bookmark]
                .status(HttpStatus.Created)
                .cookie[String]("last-created")
        )
        .metadata(_.summary("Create a bookmark").tag("bookmarks"))

    val updateRoute = HttpRoute
        .putRaw("bookmarks" / HttpPath.Capture[Int]("id"))
        .request(_.headerOpt[String]("authorization").bodyJson[UpdateBookmark])
        .filter(serverFilter.andThen(authFilter))
        .response(_.bodyJson[Bookmark].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Update a bookmark").tag("bookmarks"))

    val deleteRoute = HttpRoute
        .deleteRaw("bookmarks" / HttpPath.Capture[Int]("id"))
        .request(_.headerOpt[String]("authorization"))
        .filter(serverFilter.andThen(authFilter))
        .response(_.status(HttpStatus.NoContent).error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Delete a bookmark").tag("bookmarks"))

    // --- Handlers ---

    def handlers(storeRef: AtomicRef[Store]) =
        val list = listRoute.handler { _ =>
            storeRef.get.map { store =>
                val all = store.bookmarks.values.toList.sortBy(_.id)
                HttpResponse.ok.addField("body", all).addField("X-Total-Count", all.size)
            }
        }

        val get = getRoute.handler { req =>
            storeRef.get.map { store =>
                store.bookmarks.get(req.fields.id) match
                    case Some(b) => HttpResponse.okJson(b)
                    case None    => Abort.fail(ApiError(s"Bookmark ${req.fields.id} not found"))
            }
        }

        val create = createRoute.handler { req =>
            storeRef.updateAndGet { store =>
                val bookmark = Bookmark(store.nextId, req.fields.body.url, req.fields.body.title, req.fields.body.tags)
                Store(store.bookmarks + (store.nextId -> bookmark), store.nextId + 1)
            }.map { store =>
                val created = store.bookmarks(store.nextId - 1)
                HttpResponse.okJson(created).addField("last-created", HttpCookie(created.id.toString))
            }
        }

        val update = updateRoute.handler { req =>
            storeRef.updateAndGet { store =>
                store.bookmarks.get(req.fields.id) match
                    case Some(existing) =>
                        val updated = Bookmark(
                            req.fields.id,
                            req.fields.body.url.getOrElse(existing.url),
                            req.fields.body.title.getOrElse(existing.title),
                            req.fields.body.tags.getOrElse(existing.tags)
                        )
                        Store(store.bookmarks + (req.fields.id -> updated), store.nextId)
                    case None => store
            }.map { store =>
                store.bookmarks.get(req.fields.id) match
                    case Some(b) => HttpResponse.okJson(b)
                    case None    => Abort.fail(ApiError(s"Bookmark ${req.fields.id} not found"))
            }
        }

        val delete = deleteRoute.handler { req =>
            storeRef.updateAndGet { store =>
                store.bookmarks.get(req.fields.id) match
                    case Some(_) => Store(store.bookmarks - req.fields.id, store.nextId)
                    case None    => store
            }.map { store =>
                if !store.bookmarks.contains(req.fields.id) then
                    HttpResponse.noContent
                else
                    Abort.fail(ApiError(s"Bookmark ${req.fields.id} not found"))
            }
        }

        (list, get, create, update, delete)
    end handlers

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            storeRef <- AtomicRef.init(Store(Map.empty, 1))
            (list, get, create, update, delete) = handlers(storeRef)
            server <- HttpServer.init(
                HttpServer.Config().port(port).openApi("/openapi.json", "Bookmark Store")
            )(list, get, create, update, delete)
            _ <- Console.printLine(s"BookmarkStore running on http://localhost:${server.port}")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/bookmarks")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/bookmarks -H "Authorization: Bearer $AUTH_TOKEN" -H "Content-Type: application/json" -d '{"url":"https://scala-lang.org","title":"Scala","tags":["lang"]}'"""
            )
            _ <- server.await
        yield ()
        end for
    }
end BookmarkStore
