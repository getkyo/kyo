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
                exchange = wsExchange(root, ws, initialRules.map(_._1).toSet)
                // Session command sink: an event handler calling UI.scrollIntoView sends the op over this
                // connection's socket, riding the same channel as the reactive updates. runPartial drops
                // only a Closed (the socket closed, so the command is moot); a Panic propagates.
                scrollSink = (id: String) =>
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](HtmlOp.ScrollIntoView(id))))).unit
                // The session's imperative op channel; `emit` serializes an HtmlOp over the socket (a Closed mid-send
                // drops the op). Env.run must wrap subscribe + dispatch so the forked region/mount fibers inherit
                // Env[Commands] and resolve `UI.commands` at run time.
                commands <- UI.Commands.init(op =>
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit
                )
                _ <- Env.run(commands) {
                    UICommands.scrollSink.let(Present(scrollSink)) {
                        for
                            sub <- ReactiveUI.subscribe(root, exchange)
                            _ <- Async.race(
                                ws.stream.foreach(payload => dispatchEvent(sub.handle, commands, payload)),
                                ws.onPeerClose
                            )
                        yield ()
                    }
                }
            yield ()
        }

    private def wsRoute(base: String, ui: => UI < Async)(using Frame): HttpHandler[?, ?, ?] =
        HttpHandler.webSocket(s"$base/_kyo/ws") { (_, ws) =>
            serveSession(ws, ui)
        }

    private def wsExchange(root: ReactiveUI, ws: HttpWebSocket, seenClasses: Set[String])(using Frame): UIExchange =
        new UIExchange:
            // Pseudo-state CSS classes already carried by this connection's <style> (seeded from the
            // initial SSR page, then grown by every InjectCss this exchange sends), so a later
            // re-render reusing one of these classes never re-sends its rule. Connection-scoped: each
            // WS session gets its own set, matching the session-scoped subscription tree this exchange
            // already belongs to.
            private val sentClasses = scala.collection.mutable.Set.from(seenClasses)

            def onChange(path: Seq[String], ui: UI, mount: Boolean)(using Frame): Unit < Async =
                // Content at its nested-reactive sub-path (matches SSR/walkStatic). The payload is the region's
                // bare content fragment: the client patches between the region's live comment markers, which are
                // never re-sent. `mountSlot = true` stamps the `s` flag on Mounted placeholders so the client
                // morph tells the same mount from colliding content (twin of DomBackend.onChange).
                HtmlRenderer.renderWithCss(ui, HtmlRenderer.contentPath(path, ui), mountSlot = true).map { (html, rules) =>
                    val newRules  = rules.filterNot(r => sentClasses.contains(r._1))
                    val replaceOp = HtmlOp.Replace(path, html, mount)
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

            override def onAttrPatch(path: Seq[String], name: String, value: String)(using Frame): Unit < Async =
                val op = HtmlOp.SetAttrByPath(path, name, value)
                Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit
            override def onBoolAttrPatch(path: Seq[String], name: String, value: Boolean)(using Frame): Unit < Async =
                val op = HtmlOp.SetBoolAttrByPath(path, name, value)
                Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit
            override def onClassPatch(path: Seq[String], name: String, on: Boolean)(using Frame): Unit < Async =
                val op = HtmlOp.SetClassByPath(path, name, on)
                Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit

    private def dispatchEvent(
        handle: (Seq[String], UIEvent) => Boolean < Async,
        commands: UI.Commands,
        payload: HttpWebSocket.Payload
    )(using Frame): Unit < Async =
        payload match
            case HttpWebSocket.Payload.Text(data) =>
                Json.decode[UIEvent](data) match
                    case Result.Success(event) =>
                        event match
                            // Measure replies are not element events: route to the session channel to resolve the
                            // pending requestMeasure callback (not the ReactiveUI handler tree).
                            case m: UIEvent.Measure =>
                                commands.deliverMeasure(
                                    m.path,
                                    UI.Rect(m.rectX, m.rectY, m.rectW, m.rectH, m.viewportW, m.viewportH)
                                )
                            // Self-addressing: the id-addressed measure reply routes by `id` to the id-keyed pending map.
                            case m: UIEvent.MeasureById =>
                                commands.deliverMeasureById(
                                    m.id,
                                    UI.Rect(m.rectX, m.rectY, m.rectW, m.rectH, m.viewportW, m.viewportH)
                                )
                            case _ => handle(event.path, event).unit
                    // A malformed inbound frame (DecodeException) is dropped: a buggy client must not be able to tear
                    // down the session. A Panic is a decoder defect, not bad input, and must propagate.
                    case Result.Failure(_) => ()
                    case Result.Panic(ex)  => Abort.panic(ex)
            case HttpWebSocket.Payload.Binary(_) => ()

end UIServer
