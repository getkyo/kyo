package kyo.internal

import kyo.*

private[kyo] object UIServer:

    private def normalizePath(basePath: String): String =
        if basePath.endsWith("/") then basePath.dropRight(1) else basePath

    def handlers(basePath: String)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync =
        val base = normalizePath(basePath)
        for store <- SessionStore.init
        yield Seq(
            getPage(base, basePath, Sync.defer(ui), store),
            postEvent(base, store),
            getSse(base, store)
        )
    end handlers

    private val sidCookie = "kyo-sid"

    private def getPage(base: String, pagePath: String, ui: => UI < Async, store: SessionStore)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getText(pagePath).handler { _ =>
            for
                uiTree  <- ui
                session <- UISession.create(uiTree)
                _       <- store.put(session)
                html = HtmlRenderer.renderPage("kyo-ui", session.initialHtml, "", session.id, base)
            yield HttpResponse.ok(html)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .addHeader("Set-Cookie", s"$sidCookie=${session.id}; Path=$pagePath; SameSite=Strict")
        }

    private def extractSid(req: HttpRequest[?]): String =
        req.headers.get("Cookie").getOrElse("").split(';').map(_.trim).collectFirst {
            case s if s.startsWith(s"$sidCookie=") => s.substring(sidCookie.length + 1)
        }.getOrElse("")

    private def postEvent(basePath: String, store: SessionStore)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postText(s"$basePath/_kyo/event").handler { req =>
            val sid  = extractSid(req)
            val body = req.fields.body
            Json.decode[UIEvent](body) match
                case Result.Success(event) =>
                    for
                        maybeSession <- store.get(sid)
                        _ <- (maybeSession match
                            case Present(session) => UISession.handleEvent(session, event)
                            case Absent           => ()
                        ): Unit < Async
                    yield HttpResponse.ok("ok")
                case _ =>
                    HttpResponse.ok("error")
            end match
        }

    private def getSse(basePath: String, store: SessionStore)(using Frame): HttpHandler[?, ?, ?] =
        HttpHandler.getSseJson[HtmlOp](s"$basePath/_kyo/sse") { req =>
            val sid = extractSid(req)
            for maybeSession <- store.get(sid)
            yield maybeSession match
                case Present(session) =>
                    Stream[HttpSseEvent[HtmlOp], Async](
                        Loop.foreach {
                            Abort.run[Closed](session.channel.take).map {
                                case Result.Success(op) =>
                                    Emit.valueWith(Chunk(HttpSseEvent(op)))(Loop.continue)
                                case _ =>
                                    Loop.done
                            }
                        }
                    )
                case Absent =>
                    Stream.empty[HttpSseEvent[HtmlOp]]
        }

end UIServer
