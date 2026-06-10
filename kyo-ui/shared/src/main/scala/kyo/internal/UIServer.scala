package kyo.internal

import kyo.*

private[kyo] object UIServer:

    private def normalizePath(basePath: String): String =
        if basePath.endsWith("/") then basePath.dropRight(1) else basePath

    def handlers(basePath: String)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync =
        val base = normalizePath(basePath)
        Sync.defer(Seq(
            getPage(base, basePath, Sync.defer(ui)),
            wsRoute(base, Sync.defer(ui))
        ))
    end handlers

    private def getPage(base: String, pagePath: String, ui: => UI < Async)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getText(pagePath).handler { _ =>
            for
                uiTree <- ui
                html   <- HtmlRenderer.render(uiTree, Seq.empty)
                page = HtmlRenderer.renderPage("kyo-ui", html, "", base)
            yield HttpResponse.ok(page)
                .addHeader("Content-Type", "text/html; charset=utf-8")
        }

    private[kyo] def serveSession(ws: HttpWebSocket, ui: => UI < Async)(using Frame): Unit < (Async & Abort[Closed]) =
        Scope.run {
            for
                uiTree <- ui
                root   <- ReactiveUI.normalize(uiTree, Seq.empty)
                exchange = wsExchange(root, ws)
                sub <- ReactiveUI.subscribe(root, exchange)
                _ <- Async.race(
                    ws.stream.foreach(payload => dispatchEvent(sub.handle, payload)),
                    ws.onPeerClose
                )
            yield ()
        }

    private def wsRoute(base: String, ui: => UI < Async)(using Frame): HttpHandler[?, ?, ?] =
        HttpHandler.webSocket(s"$base/_kyo/ws") { (_, ws) =>
            serveSession(ws, ui)
        }

    private def wsExchange(root: ReactiveUI, ws: HttpWebSocket)(using Frame): UIExchange =
        new UIExchange:
            private def svgContextAt(path: Seq[String]): Boolean =
                ReactiveUI.findNode(root, path).map(_.svgContext).getOrElse(false)

            def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async =
                HtmlRenderer.render(ui, path).map { html =>
                    val finalHtml = HtmlRenderer.wrapReactiveRegion(path, svgContextAt(path), html)
                    val op        = HtmlOp.Replace(path, finalHtml)
                    // runPartial drops only a Closed (the socket closed mid-render -> the op is moot); a Panic
                    // propagates to the region fiber rather than being swallowed by the discard.
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit
                }
            end onChange

    private def dispatchEvent(handle: (Seq[String], UIEvent) => Boolean < Async, payload: HttpWebSocket.Payload)(using
        Frame
    ): Unit < Async =
        payload match
            case HttpWebSocket.Payload.Text(data) =>
                Json.decode[UIEvent](data) match
                    case Result.Success(event) => handle(event.path, event).unit
                    // A malformed inbound frame (DecodeException) is dropped: a buggy client must not be able to tear
                    // down the session. A Panic is a decoder defect, not bad input, and must propagate.
                    case Result.Failure(_) => ()
                    case Result.Panic(ex)  => Abort.panic(ex)
            case HttpWebSocket.Payload.Binary(_) => ()

end UIServer
