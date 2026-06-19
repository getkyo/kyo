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
                // Register each host's server-side observers: a host whose serverBridge is a
                // HostBridge gets its subscriptions forked, each emission emitting a HostUpdate
                // over this WS (the host-update sink). The pick-router closure (handlePick) routes
                // an inbound HostPick to the matching host's onPick.
                handlePick <- registerHosts(uiTree, ws)
                _ <- Async.race(
                    ws.stream.foreach(payload => dispatchEvent(sub.handle, payload, handlePick)),
                    ws.onPeerClose
                )
            yield ()
        }

    // Walks the resolved UI tree, finds every host node carrying a HostBridge, forks each host's
    // subscriptions (each emission -> emitHostUpdate over the WS), and returns a router that routes
    // an inbound (path, nodeId, pointer) HostPick to the right host's onPick. The router is a plain
    // closure over a pending-pick Channel, so dispatchEvent stays Async only; a consumer fiber under
    // the session Scope drains the Channel and forks each onPick on its own session-Scoped fiber, so
    // a parked pick never blocks the WS message loop and every fiber is interrupted on teardown.
    private def registerHosts(uiTree: UI, ws: HttpWebSocket)(using
        Frame
    ): ((Seq[String], String, PointerData) => Unit < Async) < (Async & Scope) =
        val hosts = ReactiveUI.hostBridges(uiTree)
        for
            _ <- Kyo.foreachDiscard(hosts) { case (path, bridge) =>
                bridge.subscriptions(path, payload => emitHostUpdate(ws, path, payload))
            }
            pending <- Channel.init[(Seq[String], String, PointerData)](Int.MaxValue)
            _ <- Fiber.init {
                Loop.forever {
                    pending.take.map { case (path, nodeId, pointer) =>
                        val routed = hosts.find(_._1 == path) match
                            case Some((p, bridge)) => bridge.onPick(p, nodeId, pointer)
                            case None              => Kyo.unit
                        // Fork each pick on its own session-Scoped fiber so a parked onPick does not
                        // block draining the next pick.
                        Fiber.init(routed)
                    }
                }
            }
        yield (path: Seq[String], nodeId: String, pointer: PointerData) =>
            Abort.runPartial[Closed](pending.put((path, nodeId, pointer))).unit
        end for
    end registerHosts

    // Emits one HostUpdate op for a host subtree over the existing single WS, reusing the
    // Json.encode[HtmlOp] sink. runPartial drops only a Closed (the socket closed mid-push -> the
    // op is moot); a Panic propagates rather than being swallowed.
    private[kyo] def emitHostUpdate(ws: HttpWebSocket, path: Seq[String], payload: HostPayload)(using
        Frame
    ): Unit < Async =
        val op = HtmlOp.HostUpdate(path, payload)
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

            def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async =
                HtmlRenderer.render(ui, path).map { html =>
                    val finalHtml = HtmlRenderer.wrapReactiveRegion(path, svgContextAt(path), html)
                    val op        = HtmlOp.Replace(path, finalHtml)
                    // runPartial drops only a Closed (the socket closed mid-render -> the op is moot); a Panic
                    // propagates to the region fiber rather than being swallowed by the discard.
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit
                }
            end onChange

    private def dispatchEvent(
        handle: (Seq[String], UIEvent) => Boolean < Async,
        payload: HttpWebSocket.Payload,
        handlePick: (Seq[String], String, PointerData) => Unit < Async
    )(using Frame): Unit < Async =
        payload match
            case HttpWebSocket.Payload.Text(data) =>
                Json.decode[UIEvent](data) match
                    // A HostPick routes to the host's server-side onPick via handlePick; every other
                    // event routes to the DOM handler. The closure runs server-side; only this typed
                    // event crossed the wire.
                    case Result.Success(UIEvent.HostPick(path, nodeId, pointer)) =>
                        handlePick(path, nodeId, pointer)
                    case Result.Success(event) => handle(event.path, event).unit
                    // A malformed inbound frame (DecodeException) is dropped: a buggy client must not be able to tear
                    // down the session. A Panic is a decoder defect, not bad input, and must propagate.
                    case Result.Failure(_) => ()
                    case Result.Panic(ex)  => Abort.panic(ex)
            case HttpWebSocket.Payload.Binary(_) => ()

end UIServer
