package demo

import kyo.*

/** Typed client that exercises BookmarkStore.
  *
  * Demonstrates: baseUrl config, retry with Schedule, HttpClient convenience methods.
  *
  * Starts its own BookmarkStore server and exercises all CRUD operations against it.
  *
  * NOTE: Currently blocked on library gap — HttpClient convenience methods (postJson, putJson, etc.) don't support client-side filters or
  * custom headers. Once HttpClient.Config supports headers/filters, this demo should add HttpFilter.client.bearerAuth for auth endpoints.
  */
object BookmarkClient extends KyoApp:

    import BookmarkStore.*

    run {
        for
            storeRef <- AtomicRef.init(Store(Map.empty, 1))
            (list, get, create, update, delete) = handlers(storeRef)
            server <- HttpServer.init(
                HttpServer.Config().port(0)
            )(list, get, create, update, delete)
            _ <- Console.printLine(s"BookmarkClient started server on http://localhost:${server.port}")
            _ <- HttpClient.withConfig(
                _.baseUrl(s"http://localhost:${server.port}")
                    .timeout(5.seconds)
                    .retry(Schedule.exponentialBackoff(100.millis, 2.0, 2.seconds).repeat(3))
            ) {
                for
                    // Create bookmarks
                    _ <- Console.printLine("=== Creating bookmarks ===")

                    b1 <- HttpClient.postJson[Bookmark, CreateBookmark](
                        "/bookmarks",
                        CreateBookmark("https://scala-lang.org", "Scala", List("lang", "jvm"))
                    )
                    _ <- Console.printLine(s"Created: $b1")

                    b2 <- HttpClient.postJson[Bookmark, CreateBookmark](
                        "/bookmarks",
                        CreateBookmark("https://kyo.dev", "Kyo", List("effects", "scala"))
                    )
                    _ <- Console.printLine(s"Created: $b2")

                    b3 <- HttpClient.postJson[Bookmark, CreateBookmark](
                        "/bookmarks",
                        CreateBookmark("https://github.com", "GitHub", List("git", "hosting"))
                    )
                    _ <- Console.printLine(s"Created: $b3")

                    // List all
                    _   <- Console.printLine("\n=== Listing bookmarks ===")
                    all <- HttpClient.getJson[List[Bookmark]]("/bookmarks")
                    _ <- Kyo.foreach(all) { b =>
                        Console.printLine(s"  [${b.id}] ${b.title} - ${b.url} (${b.tags.mkString(", ")})")
                    }

                    // Get one
                    _   <- Console.printLine("\n=== Get bookmark 1 ===")
                    got <- HttpClient.getJson[Bookmark]("/bookmarks/1")
                    _   <- Console.printLine(s"Got: $got")

                    // Update one
                    _ <- Console.printLine("\n=== Update bookmark 1 ===")
                    updated <- HttpClient.putJson[Bookmark, UpdateBookmark](
                        "/bookmarks/1",
                        UpdateBookmark(None, Some("Scala Language"), None)
                    )
                    _ <- Console.printLine(s"Updated: $updated")

                    // Delete one
                    _ <- Console.printLine("\n=== Delete bookmark 3 ===")
                    _ <- HttpClient.deleteText("/bookmarks/3")
                    _ <- Console.printLine("Deleted.")

                    // List again
                    _        <- Console.printLine("\n=== Final list ===")
                    finalAll <- HttpClient.getJson[List[Bookmark]]("/bookmarks")
                    _ <- Kyo.foreach(finalAll) { b =>
                        Console.printLine(s"  [${b.id}] ${b.title} - ${b.url}")
                    }
                yield ()
            }
        yield ()
        end for
    }
end BookmarkClient
