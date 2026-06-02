package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*

/** Live Wikipedia search served as a server-push app.
  *
  * Each keystroke writes the query `SignalRef` (two-way binding) and the `onInput` handler calls the Wikipedia API through kyo-http's
  * `HttpClient`, entirely on the server. The results, the loading indicator, and the error state are all reactive, so the server pushes the
  * matching DOM diffs over SSE. The event handler is a normal Kyo computation, so it can suspend on `Async`, perform the HTTP call, and
  * recover typed errors with `Abort.run`.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.Search'` (optional port as the first argument), then type in the box. (No debounce: every
  * keystroke issues a request, which keeps the demo simple.)
  *
  * Demonstrates: an `Async` event handler calling `HttpClient.getJson`, typed error recovery with `Abort.run`, two-way `value` binding, a
  * reactive results list via `signal.foreach`, and a tri-state (loading / error / results) driven by `signal.render`.
  */
object Search extends KyoApp:

    // Wikipedia API response shape (only the fields we read).
    case class WikiResponse(query: WikiQuery) derives Schema
    case class WikiQuery(search: List[WikiHit]) derives Schema
    case class WikiHit(title: String, snippet: String) derives Schema

    // Display model held in a Signal.
    case class Hit(title: String, snippet: String) derives CanEqual
    enum Status derives CanEqual:
        case Idle, Loading, Error, Done

    // Wikipedia's API rejects requests without a User-Agent (HTTP 403), so identify the client per their policy.
    private val userAgent = Seq("User-Agent" -> "kyo-ui-demo/1.0 (https://github.com/getkyo/kyo)")

    private def searchWikipedia(query: String): Chunk[Hit] < (Async & Abort[HttpException]) =
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url     = s"https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=8&format=json"
        HttpClient.getJson[WikiResponse](url, headers = userAgent).map { resp =>
            Chunk.from(resp.query.search.map(h => Hit(h.title, cleanSnippet(h.snippet))))
        }
    end searchWikipedia

    /** Strip the highlight markup Wikipedia returns and decode the few HTML entities that appear in snippets. */
    private def cleanSnippet(s: String): String =
        s.replaceAll("<[^>]*>", "")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

    private val pageStyle    = Style.padding(24.px).fontFamily(FontFamily.SansSerif).gap(12.px).maxWidth(640.px)
    private val inputStyle   = Style.padding(10.px).fontSize(16.px).rounded(8.px).border(1.px, Color.slate)
    private val mutedStyle   = Style.color(Color.gray)
    private val errorStyle   = Style.color(Color.red)
    private val listStyle    = Style.column.gap(8.px)
    private val cardStyle    = Style.column.gap(4.px).padding(12.px).bg(Color.slate).rounded(8.px)
    private val titleStyle   = Style.color(Color.white).bold.fontSize(15.px)
    private val snippetStyle = Style.color(Color.white).fontSize(13.px)

    private def hitRow(h: Hit): UI =
        li.style(cardStyle)(
            span(h.title).style(titleStyle),
            span(h.snippet).style(snippetStyle)
        )

    private def searchUI: UI < Async =
        for
            query   <- Signal.initRef("")
            results <- Signal.initRef(Chunk.empty[Hit])
            status  <- Signal.initRef(Status.Idle)
            onInput = (v: String) =>
                val q = v.trim
                if q.isEmpty then results.set(Chunk.empty).andThen(status.set(Status.Idle))
                else
                    status.set(Status.Loading).andThen {
                        Abort.run[HttpException](searchWikipedia(q)).map {
                            case Result.Success(hits) =>
                                results.set(hits).andThen(status.set(Status.Done))
                            case Result.Failure(e) =>
                                Log.error(s"Wikipedia search failed for '$q': $e").andThen(status.set(Status.Error))
                            case panic: Result.Panic =>
                                Log.error(s"Wikipedia search panic for '$q'", panic.exception).andThen(status.set(Status.Error))
                        }
                    }
                end if
        yield UI.main.style(pageStyle)(
            h1("Wikipedia Search"),
            input.id("q").placeholder("Search Wikipedia...").value(query).onInput(onInput).style(inputStyle),
            status.render {
                case Status.Loading => p("Searching...").style(mutedStyle)
                case Status.Error   => p("Search failed. Check your connection and try again.").style(errorStyle)
                case _              => UI.empty
            },
            ul.style(listStyle)(results.foreach(hitRow))
        )

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(searchUI)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Search running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end Search
