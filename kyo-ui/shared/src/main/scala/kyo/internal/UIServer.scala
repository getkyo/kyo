package kyo.internal

import kyo.*

private[kyo] object UIServer:

    private[kyo] def normalizePath(basePath: String): String =
        if basePath.endsWith("/") then basePath.dropRight(1) else basePath

    def handlers(basePath: String, head: UI.PageHead)(ui: => UI < Async)(using Frame): Seq[HttpHandler[?, ?, ?]] < Sync =
        val base = normalizePath(basePath)
        Sync.defer(Seq(
            getPage(base, basePath, head, Sync.defer(ui)),
            wsRoute(base, Sync.defer(ui))
        ))
    end handlers

    private def getPage(base: String, pagePath: String, head: UI.PageHead, ui: => UI < Async)(using
        Frame
    ): HttpHandler[?, ?, ?] =
        HttpRoute.getText(pagePath).handler { _ =>
            for
                uiTree <- ui
                html   <- HtmlRenderer.render(uiTree, Seq.empty)
                page = HtmlRenderer.renderPage(head.title, html, head.css, base, head.moduleScript, head.importMap)
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

            private def push(op: HtmlOp)(using Frame): Unit < Async =
                // runPartial drops only a Closed (the socket closed mid-push -> the op is moot); a Panic
                // propagates to the region fiber rather than being swallowed by the discard.
                Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit

            def onChange(region: ReactiveUI.Region, value: Any)(using Frame): Unit < Async =
                region match
                    case dom: ReactiveUI.Region.DomRegion =>
                        // value is the rendered UI: render it and push a Replace for the region's subtree.
                        HtmlRenderer.render(value.asInstanceOf[UI], dom.path).map { html =>
                            push(HtmlOp.Replace(dom.path, HtmlRenderer.wrapReactiveRegion(dom.path, svgContextAt(dom.path), html)))
                        }
                    case prop: ReactiveUI.Region.PropRegion =>
                        // a boundProp region on a BackendNode: encode the raw value, emit one SetProp.
                        push(HtmlOp.SetProp(prop.path, prop.key, prop.encode(value)))
                    case struct: ReactiveUI.Region.StructuralRegion =>
                        // Encode the raw structural DATA emission (a Chunk[A] for foreach, an
                        // A for render) and address the node's own path; the client's clientJs
                        // dispatch routes ReplaceSubtree to the owning backend's replaceSubtree
                        // with no client-side edit needed.
                        push(HtmlOp.ReplaceSubtree(struct.path, struct.encode(value)))
            end onChange

    private def dispatchEvent(
        handle: (Seq[String], UIEvent) => Boolean < Async,
        payload: HttpWebSocket.Payload
    )(using Frame): Unit < Async =
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
