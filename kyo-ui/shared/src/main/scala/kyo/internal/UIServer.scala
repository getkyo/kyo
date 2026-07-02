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

    // The SSR page GET handler in isolation, for a serve path that supplies its own WebSocket route
    // (the kyo-threejs feed runner forks per-signal feed observers rather than host-bridge
    // subscriptions, so it reuses this page handler but composes its own /_kyo/ws session). The page is
    // identical to the handlers() page: it links head.moduleScript and carries the inline clientJs that
    // routes SetProp/ReplaceSubtree to the owning backend.
    private[kyo] def pageHandler(basePath: String, head: UI.PageHead)(ui: => UI < Async)(using
        Frame
    ): HttpHandler[?, ?, ?] =
        val base = normalizePath(basePath)
        getPage(base, basePath, head, Sync.defer(ui))
    end pageHandler

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
        serveSession(ws, ui)(_ => Kyo.unit)((_, _) => Kyo.unit)

    // The reactive session, parameterized by an `afterTree` hook the caller forks under the SAME session
    // Scope once the UI tree is evaluated and subscribed. The default hook is a no-op (the plain
    // server-push path). The kyo-threejs feed runner passes a hook that forks one observer per fed signal
    // id registered DURING this `ui` evaluation, so the feed fibers share the session Scope and tear down
    // on disconnect. The `ui` builder runs exactly once; the hook reads what that single run registered.
    private[kyo] def serveSession(ws: HttpWebSocket, ui: => UI < Async)(
        afterTree: UI => Unit < (Async & Scope)
    )(using Frame): Unit < (Async & Abort[Closed]) =
        serveSession(ws, ui)(afterTree)((_, _) => Kyo.unit)

    // The reactive session, additionally parameterized by an `appEvent` router the caller supplies to
    // route an inbound `UIEvent.AppEvent(path, eventId, encoded)` to its registered server-side
    // handler by eventId (the kyo-threejs feed runner reads the per-session app-event registry). The
    // default router is a no-op (the plain server-push path has no app-event handlers). The router runs
    // under the session, so a handler that reflects into a fed signal feeds back over the same WS.
    //
    // The builder's row is `(Async & Scope)`: it is evaluated inside this session `Scope.run`, so a
    // server-timer driver the builder forks with `Fiber.init` (e.g. a feed cycler) binds to the session
    // Scope and is interrupted on disconnect alongside the reactive subscription. A plain `UI < Async`
    // builder still conforms (Async is a subset of Async & Scope).
    private[kyo] def serveSession(ws: HttpWebSocket, ui: => UI < (Async & Scope))(
        afterTree: UI => Unit < (Async & Scope)
    )(
        appEvent: (String, String) => Unit < Async
    )(using Frame): Unit < (Async & Abort[Closed]) =
        Scope.run {
            for
                uiTree <- ui
                root   <- ReactiveUI.normalize(uiTree, Seq.empty)
                exchange = wsExchange(root, ws)
                sub <- ReactiveUI.subscribe(root, exchange)
                // Caller-supplied per-session setup (the feed runner's per-id observers), forked under
                // this Scope so it is interrupted on disconnect alongside the reactive subscription.
                _ <- afterTree(uiTree)
                _ <- Async.race(
                    ws.stream.foreach(payload => dispatchEvent(sub.handle, payload, appEvent)),
                    ws.onPeerClose
                )
            yield ()
        }

    // Emits one op for a host subtree's delta over the existing single WS, reusing the
    // Json.encode[HtmlOp] sink. runPartial drops only a Closed (the socket closed mid-push -> the
    // op is moot); a Panic propagates rather than being swallowed.
    private[kyo] def emitHostUpdate(ws: HttpWebSocket, path: Seq[String], payload: HostPayload)(using
        Frame
    ): Unit < Async =
        val op: HtmlOp = payload match
            case HostPayload.SignalUpdate(signalId, encoded) => HtmlOp.SetProp(path, signalId, encoded)
            case HostPayload.SignalChunk(_, encoded)         => HtmlOp.ReplaceSubtree(path, encoded)
        Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit
    end emitHostUpdate

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
                        // value is the rendered UI (the DOM region path, unchanged behavior).
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
        payload: HttpWebSocket.Payload,
        appEvent: (String, String) => Unit < Async
    )(using Frame): Unit < Async =
        payload match
            case HttpWebSocket.Payload.Text(data) =>
                Json.decode[UIEvent](data) match
                    // An AppEvent routes by eventId to its registered server-side handler; every other
                    // event routes to the DOM handler. The closure runs server-side; only this typed
                    // event crossed the wire.
                    case Result.Success(UIEvent.AppEvent(_, eventId, encoded)) =>
                        appEvent(eventId, encoded)
                    case Result.Success(event) => handle(event.path, event).unit
                    // A malformed inbound frame (DecodeException) is dropped: a buggy client must not be able to tear
                    // down the session. A Panic is a decoder defect, not bad input, and must propagate.
                    case Result.Failure(_) => ()
                    case Result.Panic(ex)  => Abort.panic(ex)
            case HttpWebSocket.Payload.Binary(_) => ()

end UIServer
