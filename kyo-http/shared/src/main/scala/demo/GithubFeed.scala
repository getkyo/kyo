package demo

import kyo.*

/** GitHub public event feed streamed as SSE.
  *
  * Polls GitHub's public events API and re-streams as typed SSE events. Demonstrates: SSE streaming, typed JSON parsing, withConfig for
  * timeout, stream transformation.
  */
object GithubFeed extends KyoApp:

    // GitHub event model (subset of fields)
    case class GithubEvent(id: String, `type`: String, actor: GithubActor, repo: GithubRepo, created_at: String) derives Schema
    case class GithubActor(login: String) derives Schema
    case class GithubRepo(name: String) derives Schema

    // Our simplified event for SSE output
    case class FeedEvent(id: String, kind: String, actor: String, repo: String, time: String) derives Schema

    def toFeed(e: GithubEvent): FeedEvent =
        FeedEvent(e.id, e.`type`, e.actor.login, e.repo.name, e.created_at)

    // Fetch events from GitHub
    def fetchEvents(url: String): Seq[GithubEvent] < (Async & Abort[HttpError]) =
        HttpClient.withConfig(_.timeout(10.seconds)) {
            HttpClient.getJson[Seq[GithubEvent]](url)
        }

    // Create a polling SSE stream that deduplicates events
    def pollStream(url: String): Stream[HttpEvent[FeedEvent], Async] < Async =
        for
            seenRef <- AtomicRef.init(Set.empty[String])
        yield Stream.repeatPresent[HttpEvent[FeedEvent], Async] {
            for
                _           <- Async.delay(10.seconds)(())
                fetchResult <- Abort.run[HttpError](fetchEvents(url))
                events = fetchResult.getOrElse(Seq.empty)
                seen <- seenRef.get
                newEvents = events.filterNot(e => seen.contains(e.id))
                _ <- seenRef.set(seen ++ newEvents.map(_.id))
            yield Maybe.Present(newEvents.map { e =>
                HttpEvent(
                    data = toFeed(e),
                    event = Present(e.`type`),
                    id = Present(e.id)
                )
            })
        }

    // SSE handler for public events
    val publicFeed = HttpHandler.getSseJson[FeedEvent]("events") { _ =>
        pollStream("https://api.github.com/events?per_page=10")
    }

    // Route-based handler for repo events (supports path captures)
    val repoFeedRoute = HttpRoute
        .getRaw("feed" / HttpPath.Capture[String]("owner") / HttpPath.Capture[String]("repo"))
        .response(_.bodySseJson[FeedEvent])
        .metadata(_.summary("SSE stream of repo events").tag("github"))
        .handler { req =>
            pollStream(s"https://api.github.com/repos/${req.fields.owner}/${req.fields.repo}/events?per_page=10")
                .map(stream => HttpResponse.ok.addField("body", stream))
        }

    val health = HttpHandler.health()

    run {
        HttpServer.init(HttpServer.Config().port(3003))(publicFeed, repoFeedRoute, health).map { server =>
            for
                _ <- Console.printLine(s"GitHub Feed running on http://localhost:${server.port}")
                _ <- Console.printLine("  curl -N http://localhost:3003/events")
                _ <- Console.printLine("  curl -N http://localhost:3003/feed/scala/scala")
                _ <- server.await
            yield ()
        }
    }
end GithubFeed
