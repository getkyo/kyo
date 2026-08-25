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
                sub <- ReactiveUI.subscribe(root, exchange)
                // Session command sink: an event handler calling UI.scrollIntoView sends the op over this
                // connection's socket, riding the same channel as the reactive updates. runPartial drops
                // only a Closed (the socket closed, so the command is moot); a Panic propagates.
                scrollSink = (id: String) =>
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](HtmlOp.ScrollIntoView(id))))).unit
                _ <- UICommands.scrollSink.let(Present(scrollSink)) {
                    Async.race(
                        ws.stream.foreach(payload => dispatchEvent(sub.handle, payload)),
                        ws.onPeerClose
                    )
                }
            yield ()
        }

    private def wsRoute(base: String, ui: => UI < Async)(using Frame): HttpHandler[?, ?, ?] =
        HttpHandler.webSocket(s"$base/_kyo/ws") { (_, ws) =>
            serveSession(ws, ui)
        }

    private def wsExchange(root: ReactiveUI, ws: HttpWebSocket, seenClasses: Set[String])(using Frame): UIExchange =
        new UIExchange:
            private def svgContextAt(path: Seq[String]): Boolean =
                ReactiveUI.findNode(root, path).map(_.svgContext).getOrElse(false)

            // Both collections below are written CONCURRENTLY: subscribeScoped forks one fiber per reactive
            // region and every one of them calls into this same exchange, so a plain mutable Map or Set here
            // is a data race that can corrupt the table under a rehash, not merely lose an entry.

            // Pseudo-state CSS classes already carried by this connection's <style> (seeded from the
            // initial SSR page, then grown by every InjectCss this exchange sends), so a later
            // re-render reusing one of these classes never re-sends its rule. Connection-scoped: each
            // WS session gets its own set, matching the session-scoped subscription tree this exchange
            // already belongs to.
            private val sentClasses = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]
            seenClasses.foreach(c => discard(sentClasses.put(c, java.lang.Boolean.TRUE)))

            /** Per reactive region, the HTML last sent for each node BELOW it, so an update re-sends only the
              * nodes whose rendered bytes actually changed.
              *
              * Rendered bytes, not the AST: a node's rendering is not a function of its AST alone. An element
              * bound to a `SignalRef` re-renders from the ref read at render time and its AST is the same
              * object on every edit, so an AST comparison would drop real updates. Connection-scoped, like
              * `sentClasses`. Each region writes only its own key and replaces that key's value wholesale, so
              * a structural change cannot leave stale paths behind.
              */
            private val sentBelowRegion =
                new java.util.concurrent.ConcurrentHashMap[Seq[String], Map[Seq[String], String]]

            def onChange(path: Seq[String], previous: Maybe[UI], ui: UI)(using Frame): Unit < Async =
                val plan = UIDiff.plan(path, previous, ui)
                if plan.size == 1 && plan.head._1 == path then
                    // The whole region: sent unconditionally. The client compares against the LIVE DOM before
                    // applying, which is what repairs a field the user has typed into; suppressing this here
                    // would take that repair away.
                    discard(sentBelowRegion.remove(path))
                    sendReplacement(path, path, ui)
                else
                    // Only the nodes that moved. For a chart on a 1 Hz signal that is the marks group: the
                    // background, axes, gridlines and legend render byte-identically to the tick before and
                    // are not sent at all. An unchanged re-render of the whole region sends nothing.
                    val previouslySent = Option(sentBelowRegion.get(path)).getOrElse(Map.empty)
                    Kyo.foreach(plan) { (opPath, subtree) =>
                        HtmlRenderer.renderWithCss(subtree, opPath).map((html, rules) => (opPath, html, rules))
                    }.map { rendered =>
                        discard(sentBelowRegion.put(path, rendered.map((opPath, html, _) => (opPath, html)).toMap))
                        val changed = rendered.filterNot((opPath, html, _) => previouslySent.get(opPath).contains(html))
                        Kyo.foreachDiscard(changed)((opPath, html, rules) => send(opPath, html, rules))
                    }
                end if
            end onChange

            /** Render and send one replacement whole. `regionPath` is the reactive boundary; `opPath` is the
              * node being replaced. Only the boundary carries the region wrapper: a descendant renders its own
              * tag, with its own `data-kyo-path`, which is what the client resolves the op against.
              */
            private def sendReplacement(regionPath: Seq[String], opPath: Seq[String], ui: UI)(using Frame): Unit < Async =
                HtmlRenderer.renderWithCss(ui, opPath).map { (html, rules) =>
                    val finalHtml =
                        if opPath == regionPath then HtmlRenderer.wrapReactiveRegion(opPath, svgContextAt(opPath), html)
                        else html
                    send(opPath, finalHtml, rules)
                }

            /** Emit one `Replace`, preceded by any pseudo-state rule it introduces. */
            private def send(opPath: Seq[String], html: String, rules: Seq[(String, String)])(using Frame): Unit < Async =
                val newRules  = rules.filterNot(r => sentClasses.containsKey(r._1))
                val replaceOp = HtmlOp.Replace(opPath, html)
                // runPartial drops only a Closed (the socket closed mid-render -> the op is moot); a Panic
                // propagates to the region fiber rather than being swallowed by the discard.
                val sendReplace =
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](replaceOp)))).unit
                if newRules.isEmpty then sendReplace
                else
                    newRules.foreach(r => discard(sentClasses.put(r._1, java.lang.Boolean.TRUE)))
                    val injectOp = HtmlOp.InjectCss(newRules.map(_._2).mkString)
                    // Send the new pseudo-state rule(s) before the replace that introduces the class
                    // referencing them, so the element never paints unstyled between the two frames.
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](injectOp)))).unit
                        .andThen(sendReplace)
                end if
            end send

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
