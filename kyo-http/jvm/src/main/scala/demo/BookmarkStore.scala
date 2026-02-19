package demo

import kyo.*

/** In-memory CRUD bookmark API with auth, rate limiting, and typed client.
  *
  * Demonstrates: POST/PUT/DELETE with JSON body, bearer auth on routes, server filters (requestId, rateLimit, securityHeaders,
  * etag/conditionalRequests), typed response headers, response cookies, and OpenAPI.
  *
  * Endpoints:
  *   - POST /bookmarks — create bookmark (auth required)
  *   - GET /bookmarks — list all bookmarks
  *   - GET /bookmarks/:id — get bookmark by ID
  *   - PUT /bookmarks/:id — update bookmark (auth required)
  *   - DELETE /bookmarks/:id — delete bookmark (auth required)
  *
  * Auth: Bearer token "demo-token-2026"
  *
  * Test: curl http://localhost:3007/bookmarks curl -X POST http://localhost:3007/bookmarks -H "Authorization: Bearer demo-token-2026" \ -H
  * "Content-Type: application/json" -d '{"url":"https://scala-lang.org","title":"Scala","tags":["lang"]}'
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

    // --- Routes ---

    val listRoute = HttpRoute
        .get("bookmarks")
        .response(
            _.bodyJson[List[Bookmark]]
                .header[Int]("X-Total-Count")
        )
        .metadata(_.summary("List all bookmarks").tag("bookmarks"))

    val getRoute = HttpRoute
        .get("bookmarks" / HttpPath.Capture[Int]("id"))
        .response(_.bodyJson[Bookmark].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Get bookmark by ID").tag("bookmarks"))

    val createRoute = HttpRoute
        .post("bookmarks")
        .request(_.authBearer.bodyJson[CreateBookmark])
        .response(
            _.bodyJson[Bookmark]
                .status(HttpStatus.Created)
                .cookie[String]("last-created")
        )
        .metadata(_.summary("Create a bookmark").tag("bookmarks"))

    val updateRoute = HttpRoute
        .put("bookmarks" / HttpPath.Capture[Int]("id"))
        .request(_.authBearer.bodyJson[UpdateBookmark])
        .response(_.bodyJson[Bookmark].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Update a bookmark").tag("bookmarks"))

    val deleteRoute = HttpRoute
        .delete("bookmarks" / HttpPath.Capture[Int]("id"))
        .request(_.authBearer)
        .response(_.status(HttpStatus.NoContent).error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Delete a bookmark").tag("bookmarks"))

    // --- Handlers ---

    def handlers(storeRef: AtomicRef[Store]) =
        val list = listRoute.handle { _ =>
            storeRef.get.map { store =>
                val all = store.bookmarks.values.toList.sortBy(_.id)
                (all, all.size)
            }
        }

        val get = getRoute.handle { in =>
            storeRef.get.map { store =>
                store.bookmarks.get(in.id) match
                    case Some(b) => b
                    case None    => Abort.fail(ApiError(s"Bookmark ${in.id} not found"))
            }
        }

        val create = createRoute.handle { in =>
            storeRef.updateAndGet { store =>
                val bookmark = Bookmark(store.nextId, in.body.url, in.body.title, in.body.tags)
                Store(store.bookmarks + (store.nextId -> bookmark), store.nextId + 1)
            }.map { store =>
                val created = store.bookmarks(store.nextId - 1)
                (created, created.id.toString)
            }
        }

        val update = updateRoute.handle { in =>
            storeRef.updateAndGet { store =>
                store.bookmarks.get(in.id) match
                    case Some(existing) =>
                        val updated = Bookmark(
                            in.id,
                            in.body.url.getOrElse(existing.url),
                            in.body.title.getOrElse(existing.title),
                            in.body.tags.getOrElse(existing.tags)
                        )
                        Store(store.bookmarks + (in.id -> updated), store.nextId)
                    case None => store
            }.map { store =>
                store.bookmarks.get(in.id) match
                    case Some(b) => b
                    case None    => Abort.fail(ApiError(s"Bookmark ${in.id} not found"))
            }
        }

        val delete = deleteRoute.handle { in =>
            storeRef.updateAndGet { store =>
                if store.bookmarks.contains(in.id) then Store(store.bookmarks - in.id, store.nextId)
                else store
            }.map { _ => () }
        }

        (list, get, create, update, delete)
    end handlers

    run {
        for
            storeRef <- AtomicRef.init(Store(Map.empty, 1))
            meter    <- Meter.initRateLimiter(100, 1.minutes)
            (list, get, create, update, delete) = handlers(storeRef)
            server <- HttpFilter.server.requestId
                .andThen(HttpFilter.server.logging)
                .andThen(HttpFilter.server.rateLimit(meter))
                .andThen(HttpFilter.server.securityHeaders())
                .andThen(HttpFilter.server.etag)
                .andThen(HttpFilter.server.conditionalRequests)
                .enable {
                    HttpServer.init(
                        HttpServer.Config.default.port(3007).openApi("/openapi.json", "Bookmark Store")
                    )(list, get, create, update, delete)
                }
            _ <- Console.printLine(s"BookmarkStore running on http://localhost:${server.port}")
            _ <- Console.printLine(s"  curl http://localhost:3007/bookmarks")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:3007/bookmarks -H "Authorization: Bearer $AUTH_TOKEN" -H "Content-Type: application/json" -d '{"url":"https://scala-lang.org","title":"Scala","tags":["lang"]}'"""
            )
            _ <- server.await
        yield ()
    }
end BookmarkStore
