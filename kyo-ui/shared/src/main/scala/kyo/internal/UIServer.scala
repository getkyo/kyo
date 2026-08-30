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
                files <- DragFiles.Service.init(op =>
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit
                )
                // Peer close (or any session end) fails every pending file read with Disconnected.
                _ <- Scope.ensure(files.close())
                _ <- UICommands.scrollSink.let(Present(scrollSink)) {
                    DragCommands.resolveSink.let(Present(resolveSink)) {
                        DragFiles.local.let(Present(files)) {
                            Async.race(
                                ws.stream.foreach(payload => dispatchEvent(sub.handleValidated, files, payload)),
                                ws.onPeerClose
                            )
                        }
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

            def onChange(
                region: ReactiveRegion,
                path: Seq[String],
                contentContext: ReactiveRegion.RegionIdentity,
                parentContext: ReactiveRegion.ParentContext,
                previous: Maybe[UI],
                ui: UI
            )(using Frame): Unit < Async =
                val plan = UIDiff.plan(path, previous, ui)
                if plan.size == 1 && plan.head._1 == path then
                    // The whole region: sent unconditionally. The client compares against the LIVE DOM before
                    // applying, which is what repairs a field the user has typed into; suppressing this here
                    // would take that repair away.
                    discard(sentBelowRegion.remove(path))
                    sendRegion(region, path, contentContext, parentContext, ui)
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
                        Kyo.foreachDiscard(changed)((opPath, html, rules) => send(HtmlOp.Replace(opPath, html), rules))
                    }
                end if
            end onChange

            /** Render and send the whole region. The region kind picks the op: an HTML region replaces the
              * content between its comment anchors (`ReplaceRange`), so the replacement parses in its actual
              * parent context; an SVG region replaces its own `<g>` boundary at its path. A descendant sent by
              * `onChange`'s diff branch renders its own tag, with its own `data-kyo-path`, which is what the
              * client resolves the op against.
              */
            private def sendRegion(
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
                        val replaceOp = region match
                            case ReactiveRegion.HtmlRange(id) => HtmlOp.ReplaceRange(id, html)
                            case _: ReactiveRegion.SvgElement => HtmlOp.Replace(path, HtmlRenderer.wrapReactiveRegion(region, html))
                        send(replaceOp, rules)
                }
            end sendRegion

            /** Emit one op, preceded by any pseudo-state rule it introduces. */
            private def send(op: HtmlOp, rules: Seq[(String, String)])(using Frame): Unit < Async =
                val newRules = rules.filterNot(r => sentClasses.containsKey(r._1))
                // runPartial drops only a Closed (the socket closed mid-render -> the op is moot); a Panic
                // propagates to the region fiber rather than being swallowed by the discard.
                val sendReplace =
                    Abort.runPartial[Closed](ws.put(HttpWebSocket.Payload.Text(Json.encode[HtmlOp](op)))).unit
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

    private def dispatchEvent(
        handle: (Seq[String], DragProtocol.ValidatedEvent) => Boolean < Async,
        files: DragFiles.Service,
        payload: HttpWebSocket.Payload
    )(using
        Frame
    ): Unit < Async =
        def dispatch(event: UIEvent): Unit < Async =
            DragProtocol.validateEventAndDomain(event, DragProtocol.Limits.default) match
                // Drop and sort dispatch forks: their handlers may await lazy file reads served by later
                // frames on this same socket loop, so running them inline would deadlock the session. The
                // client models concurrent decisions (AwaitingDecisionAfterEnd), and session close unblocks
                // a forked handler because the file service fails its pending reads with Disconnected.
                case Result.Success(validated: (DragProtocol.ValidatedEvent.Drop | DragProtocol.ValidatedEvent.SortMove)) =>
                    Fiber.initUnscoped(handle(event.path, validated).unit).unit
                case Result.Success(validated) => handle(event.path, validated).unit
                case _                         => ()
        payload match
            case HttpWebSocket.Payload.Text(data) =>
                Json.decode[UIEvent](data) match
                    case Result.Success(event) => dispatch(event)
                    // Not a bare event: the drag runtime posts ClientMessage envelopes; unwrap Event values and
                    // route validated file transfer responses to the session's read service. A malformed inbound
                    // frame is dropped: a buggy client must not be able to tear down the session. A Panic is a
                    // decoder defect, not bad input, and must propagate.
                    case Result.Failure(_) =>
                        Json.decode[DragProtocol.ClientMessage](data) match
                            case Result.Success(DragProtocol.ClientMessage.Event(event)) => dispatch(event)
                            case Result.Success(message) =>
                                DragProtocol.validate(message, DragProtocol.Limits.default) match
                                    case Result.Success(validated) => files.deliver(validated)
                                    case _                         => ()
                            case Result.Failure(_) => ()
                            case Result.Panic(ex)  => Abort.panic(ex)
                    case Result.Panic(ex) => Abort.panic(ex)
            case HttpWebSocket.Payload.Binary(_) => ()
        end match
    end dispatchEvent

end UIServer
