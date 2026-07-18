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
                _      <- warnUnhydratableClientRegions(uiTree, head)
                html   <- HtmlRenderer.render(uiTree, Seq.empty)
                page = HtmlRenderer.renderPage(head.title, html, head.css, base, head.moduleScript, head.importMap)
            yield HttpResponse.ok(page)
                .addHeader("Content-Type", "text/html; charset=utf-8")
        }

    /** Warns when the page declares client-owned regions but nothing will ever hydrate them.
      *
      * A client-owned region is deliberately skipped by the server session, so the browser is the only
      * thing that can drive it, and only `UI.runHydrate` gives it to the browser. A page with no module
      * script never loads the code that calls `runHydrate`, so every marked region renders its SSR
      * snapshot once and then sits there, inert, forever. That failure is invisible: the page looks right,
      * it just never updates. Saying so at render time costs one log line and turns a silent dead region
      * into a message naming the paths that will not move.
      */
    private[kyo] def warnUnhydratableClientRegions(uiTree: UI, head: UI.PageHead)(using Frame): Unit < Sync =
        if head.moduleScript.nonEmpty then Kyo.unit
        else
            val roots = ReactiveUI.clientOwnedRoots(uiTree)
            if roots.isEmpty then Kyo.unit
            else
                Log.warn(
                    s"UI page declares ${roots.size} clientOwned region(s) at path(s) " +
                        s"${roots.map(_.mkString(".")).mkString(", ")} but its PageHead carries no moduleScript, " +
                        "so nothing calls UI.runHydrate and the browser never takes ownership. Those regions will " +
                        "render their SSR snapshot and then stay inert. Give the page a moduleScript that hydrates it."
                )
            end if
        end if
    end warnUnhydratableClientRegions

    private[kyo] def serveSession(ws: HttpWebSocket, ui: => UI < Async)(using Frame): Unit < (Async & Abort[Closed]) =
        Scope.run {
            for
                uiTree <- ui
                root   <- ReactiveUI.normalize(uiTree, Seq.empty)
                exchange = wsExchange(root, ws)
                // The session drives everything OUTSIDE a client-owned boundary. Inside one the browser is
                // the owner (it holds state this side cannot have, such as a live mount handle), so this
                // session neither pushes those regions nor runs their handlers; both would be a second
                // writer of state the browser already drives.
                sub <- ReactiveUI.subscribe(root, exchange, Ownership.Server)
                clientRoots = ReactiveUI.clientOwnedRoots(uiTree)
                _ <- Async.race(
                    ws.stream.foreach(payload => dispatchEvent(sub.handle, payload, clientRoots)),
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

    private[kyo] def dispatchEvent(
        handle: (Seq[String], UIEvent) => Boolean < Async,
        payload: HttpWebSocket.Payload,
        clientRoots: Seq[Seq[String]]
    )(using Frame): Unit < Async =
        payload match
            case HttpWebSocket.Payload.Text(data) =>
                Json.decode[UIEvent](data) match
                    // An event inside a client-owned boundary belongs to the browser, which already ran the
                    // handler against its own tree. The inline WS client does not post these, so reaching here
                    // means a stale or hand-made client; running this side's copy of the handler would fire it
                    // twice, so drop it.
                    case Result.Success(event) if clientRoots.exists(root => event.path.startsWith(root)) => ()
                    case Result.Success(event)                                                            => handle(event.path, event).unit
                    // A malformed inbound frame (DecodeException) is dropped: a buggy client must not be able to tear
                    // down the session. A Panic is a decoder defect, not bad input, and must propagate.
                    case Result.Failure(_) => ()
                    case Result.Panic(ex)  => Abort.panic(ex)
            case HttpWebSocket.Payload.Binary(_) => ()

end UIServer
