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
                uiTree        <- ui
                (html, rules) <- HtmlRenderer.renderWithCss(uiTree, Seq.empty)
                // Pseudo-state Style (hover/focus/active/disabled) has no inline-style channel (an
                // inline `style="..."` attribute cannot express `:hover`), so its rules are carried
                // here in a real stylesheet instead, after the base reset (renderPage already orders
                // baseCss before css).
                css  = rules.map(_._2).mkString
                page = HtmlRenderer.renderPage("kyo-ui", html, css, base)
            yield HttpResponse.ok(page)
                .addHeader("Content-Type", "text/html; charset=utf-8")
        }

    private[kyo] def serveSession(ws: HttpWebSocket, ui: => UI < Async)(using Frame): Unit < (Async & Abort[Closed]) =
        Scope.run {
            for
                uiTree <- ui
                root   <- ReactiveUI.normalize(uiTree, Seq.empty)
                // Pre-seed the connection's sent-class tracking with every pseudo-state class the
                // initial SSR page already carries (rendered once more here, discarding the HTML), so
                // the first reactive update touching an unchanged pseudo-styled element does not
                // redundantly re-inject a rule the page's initial <style> block already has.
                (_, initialRules) <- HtmlRenderer.renderWithCss(uiTree, Seq.empty)
                exchange = wsExchange(ws, initialRules.map(_._1).toSet)
                sub <- ReactiveUI.subscribe(root, exchange)
                // Session command sink: an event handler calling UI.scrollIntoView sends the op over this
                // connection's socket, riding the same channel as the reactive updates. runPartial drops
                // only a Closed (the socket closed, so the command is moot); a Panic propagates.
                scrollSink = (id: String) =>
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](HtmlOp.ScrollIntoView(id))))).unit
                resolveSink = (sessionId: String, decision: Drag.Decision) =>
                    Abort.runPartial[Closed](
                        ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](HtmlOp.ResolveDrag(sessionId, decision))))
                    ).unit
                _ <- UICommands.scrollSink.let(Present(scrollSink)) {
                    DragCommands.resolveSink.let(Present(resolveSink)) {
                        Async.race(
                            ws.stream.foreach(payload => dispatchEvent(sub.handleValidated, payload)),
                            ws.onPeerClose
                        )
                    }
                }
            yield ()
        }

    private def wsRoute(base: String, ui: => UI < Async)(using Frame): HttpHandler[?, ?, ?] =
        HttpHandler.webSocket(s"$base/_kyo/ws") { (_, ws) =>
            serveSession(ws, ui)
        }

    private def wsExchange(ws: HttpWebSocket, seenClasses: Set[String])(using Frame): UIExchange =
        new UIExchange:
            // Pseudo-state CSS classes already carried by this connection's <style> (seeded from the
            // initial SSR page, then grown by every InjectCss this exchange sends), so a later
            // re-render reusing one of these classes never re-sends its rule. Connection-scoped: each
            // WS session gets its own set, matching the session-scoped subscription tree this exchange
            // already belongs to.
            private val sentClasses = scala.collection.mutable.Set.from(seenClasses)

            def onChange(
                region: ReactiveRegion,
                path: Seq[String],
                contentContext: ReactiveRegion.RegionIdentity,
                parentContext: ReactiveRegion.ParentContext,
                ui: UI
            )(using Frame): Unit < Async =
                val boundaryMode =
                    if ReactiveRegion.owns(region, contentContext) then ReactiveRegion.BoundaryMode.Suppress
                    else ReactiveRegion.BoundaryMode.Emit
                HtmlRenderer.renderRegionWithCss(ui, path, contentContext, region, parentContext, boundaryMode).map {
                    (html, rules) =>
                        val newRules = rules.filterNot(r => sentClasses.contains(r._1))
                        val replaceOp = region match
                            case ReactiveRegion.HtmlRange(id) => HtmlOp.ReplaceRange(id, html)
                            case _: ReactiveRegion.SvgElement => HtmlOp.Replace(path, HtmlRenderer.wrapReactiveRegion(region, html))
                        // runPartial drops only a Closed (the socket closed mid-render -> the op is moot); a Panic
                        // propagates to the region fiber rather than being swallowed by the discard.
                        val sendReplace =
                            Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](replaceOp)))).unit
                        if newRules.isEmpty then sendReplace
                        else
                            newRules.foreach(r => sentClasses += r._1)
                            val injectOp = HtmlOp.InjectCss(newRules.map(_._2).mkString)
                            // Send the new pseudo-state rule(s) before the replace that introduces the class
                            // referencing them, so the element never paints unstyled between the two frames.
                            Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](injectOp)))).unit
                                .andThen(sendReplace)
                        end if
                }
            end onChange

    private def dispatchEvent(
        handle: (Seq[String], DragProtocol.ValidatedEvent) => Boolean < Async,
        payload: HttpWebSocket.Payload
    )(using
        Frame
    ): Unit < Async =
        def dispatch(event: UIEvent): Unit < Async =
            DragProtocol.validateEventAndDomain(event, DragProtocol.Limits.default) match
                case Result.Success(validated) => handle(event.path, validated).unit
                case _                         => ()
        payload match
            case HttpWebSocket.Payload.Text(data) =>
                Json.decode[UIEvent](data) match
                    case Result.Success(event) => dispatch(event)
                    // Not a bare event: the drag runtime posts ClientMessage envelopes; unwrap Event values and
                    // leave file transfer messages to the file service. A malformed inbound frame is dropped: a
                    // buggy client must not be able to tear down the session. A Panic is a decoder defect, not
                    // bad input, and must propagate.
                    case Result.Failure(_) =>
                        Json.decode[DragProtocol.ClientMessage](data) match
                            case Result.Success(DragProtocol.ClientMessage.Event(event)) => dispatch(event)
                            case Result.Success(_)                                       => ()
                            case Result.Failure(_)                                       => ()
                            case Result.Panic(ex)                                        => Abort.panic(ex)
                    case Result.Panic(ex) => Abort.panic(ex)
            case HttpWebSocket.Payload.Binary(_) => ()
        end match
    end dispatchEvent

end UIServer
