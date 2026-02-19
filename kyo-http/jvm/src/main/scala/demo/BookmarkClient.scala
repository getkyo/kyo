package demo

import kyo.*

/** Typed client that exercises BookmarkStore.
  *
  * Demonstrates: client-side bearerAuth filter, baseUrl config, retry with Schedule, HttpClient.post/put/delete convenience methods.
  *
  * Requires BookmarkStore to be running on port 3007.
  *
  * Usage: sbt 'kyo-http/runMain demo.BookmarkClient'
  */
object BookmarkClient extends KyoApp:

    import BookmarkStore.*

    run {
        HttpFilter.client.bearerAuth(AUTH_TOKEN)
            .andThen(HttpFilter.client.addHeader("Content-Type", "application/json"))
            .enable {
                HttpClient.withConfig(
                    _.baseUrl("http://localhost:3007")
                        .timeout(5.seconds)
                        .retry(Schedule.exponentialBackoff(100.millis, 2.0, 2.seconds).repeat(3))
                ) {
                    for
                        // Create bookmarks
                        _ <- Console.printLine("=== Creating bookmarks ===")

                        b1 <- HttpClient.post[Bookmark, CreateBookmark](
                            "/bookmarks",
                            CreateBookmark("https://scala-lang.org", "Scala", List("lang", "jvm"))
                        )
                        _ <- Console.printLine(s"Created: $b1")

                        b2 <- HttpClient.post[Bookmark, CreateBookmark](
                            "/bookmarks",
                            CreateBookmark("https://kyo.dev", "Kyo", List("effects", "scala"))
                        )
                        _ <- Console.printLine(s"Created: $b2")

                        b3 <- HttpClient.post[Bookmark, CreateBookmark](
                            "/bookmarks",
                            CreateBookmark("https://github.com", "GitHub", List("git", "hosting"))
                        )
                        _ <- Console.printLine(s"Created: $b3")

                        // List all
                        _   <- Console.printLine("\n=== Listing bookmarks ===")
                        all <- HttpClient.get[List[Bookmark]]("/bookmarks")
                        _ <- Kyo.foreach(all) { b =>
                            Console.printLine(s"  [${b.id}] ${b.title} - ${b.url} (${b.tags.mkString(", ")})")
                        }

                        // Get one
                        _   <- Console.printLine("\n=== Get bookmark 1 ===")
                        got <- HttpClient.get[Bookmark]("/bookmarks/1")
                        _   <- Console.printLine(s"Got: $got")

                        // Update one
                        _ <- Console.printLine("\n=== Update bookmark 1 ===")
                        updated <- HttpClient.put[Bookmark, UpdateBookmark](
                            "/bookmarks/1",
                            UpdateBookmark(None, Some("Scala Language"), None)
                        )
                        _ <- Console.printLine(s"Updated: $updated")

                        // Delete one
                        _ <- Console.printLine("\n=== Delete bookmark 3 ===")
                        _ <- HttpClient.send(HttpRequest.delete("/bookmarks/3"))
                        _ <- Console.printLine("Deleted.")

                        // List again
                        _        <- Console.printLine("\n=== Final list ===")
                        finalAll <- HttpClient.get[List[Bookmark]]("/bookmarks")
                        _ <- Kyo.foreach(finalAll) { b =>
                            Console.printLine(s"  [${b.id}] ${b.title} - ${b.url}")
                        }
                    yield ()
                }
            }
    }
end BookmarkClient
