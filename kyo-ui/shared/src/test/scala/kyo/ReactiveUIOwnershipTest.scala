package kyo

import kyo.Browser.*
import kyo.internal.HtmlRenderer
import kyo.internal.MouseEventData
import kyo.internal.Ownership
import kyo.internal.ReactiveUI
import kyo.internal.UIEvent
import kyo.internal.UIExchange
import kyo.internal.UIServer

/** A `Log` backend that keeps every warning it is handed, so a leaf can assert on the exact text of a
  * diagnostic. The other levels are dropped: only `warn` carries the diagnostics under test here.
  */
private[kyo] class CapturingLog(val level: Log.Level) extends Log.Unsafe:
    @volatile private var captured: Chunk[String] = Chunk.empty
    def warnings: Chunk[String]                   = captured

    val name: String                                                           = "capturing"
    def withName(n: String): Log.Unsafe                                        = this
    def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
    def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
    def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
    def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
    def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
    def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
    def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = captured = captured.appended(msg)
    def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = captured = captured.appended(msg)
    def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
    def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
end CapturingLog

/** `Element.clientOwned` splits a server-rendered page in two: the WS session drives everything outside a
  * marked boundary, the hydrating browser drives everything inside one, and each region has exactly one
  * writer. These leaves pin that split at the layer it is decided (`ReactiveUI`'s ownership walk), because
  * the two runtime halves that enforce it MASK EACH OTHER end to end: the inline WS client already refuses
  * to post an event from inside a boundary, so the server's own refusal to run that handler is invisible
  * from the browser, and a server that wrongly subscribed a client-owned region would still let the
  * browser's copy climb, so a double writer looks exactly like a single one from the outside.
  */
class ReactiveUIOwnershipTest extends UITest:

    private type Entry = (Boolean, Seq[String])

    /** Records every region this exchange is handed, tagged as the control's or the subject's. */
    private def recordTo(ch: Channel[Entry], control: Boolean): UIExchange =
        new UIExchange:
            def onChange(region: ReactiveUI.Region, value: Any)(using Frame): Unit < Async =
                val path = region match
                    case ReactiveUI.Region.DomRegion(p)           => p
                    case ReactiveUI.Region.PropRegion(p, _, _)    => p
                    case ReactiveUI.Region.StructuralRegion(p, _) => p
                Abort.run[Closed](ch.put((control, path))).map {
                    case Result.Success(_) => ()
                    // The recording channel is open for the whole leaf; a Closed here would silently shrink
                    // the recorded set and turn every assertion below into a vacuous pass.
                    case other => Abort.panic(new IllegalStateException(s"recording channel closed: $other"))
                }
            end onChange

    /** The region paths `ownership` actually subscribes, sorted.
      *
      * Every subscribed region emits its current value once at subscribe time, so the recorded set IS the
      * subscribed set. The fence is a CONTROL subscription of the same tree under `Ownership.All`, which
      * owns every region by definition and is subscribed LAST, so its region fibers queue behind the
      * subject's: once all `totalRegions` control emissions have landed, every emission the subject was
      * going to make has landed too. Draining on that, rather than on a timer, is what makes "subscribes
      * NOTHING" a real assertion instead of one that merely looked too early. The test clock is frozen, so
      * a timed settle window would elapse zero time and every negative assertion here would pass vacuously.
      */
    private def subscribedPaths(ui: UI, ownership: Ownership, totalRegions: Int)(using
        Frame
    ): Seq[Seq[String]] < (Async & Scope) =
        def drain(ch: Channel[Entry], seen: Chunk[Entry], controls: Int): Chunk[Entry] < (Async & Abort[Closed]) =
            if controls < totalRegions then
                ch.take.map(e => drain(ch, seen.appended(e), controls + (if e._1 then 1 else 0)))
            else
                // Every control emission has landed, so every subject emission has too; take what is left.
                ch.size.map(n => Kyo.foreach(Chunk.fill(n)(()))(_ => ch.take).map(rest => seen.concat(rest)))
        for
            ch      <- Channel.init[Entry](128)
            subject <- ReactiveUI.normalize(ui, Seq.empty)
            control <- ReactiveUI.normalize(ui, Seq.empty)
            _       <- ReactiveUI.subscribe(subject, recordTo(ch, control = false), ownership)
            _       <- ReactiveUI.subscribe(control, recordTo(ch, control = true), Ownership.All)
            seen <- Abort.run[Closed](drain(ch, Chunk.empty, 0)).map {
                case Result.Success(entries) => entries
                // A drain that ends early would shrink the recorded set and make every assertion vacuous.
                case other => Abort.panic(new IllegalStateException(s"recording channel closed while draining: $other"))
            }
        yield seen.collect { case (false, path) => path }.toSeq.sortBy(_.mkString("."))
        end for
    end subscribedPaths

    /** A server region at path "0" and a client-owned boundary at "1" holding a region at "1.0". */
    private def splitPage(using Frame): UI < Sync =
        for
            serverRef <- Signal.initRef("server-initial")
            clientRef <- Signal.initRef("client-initial")
        yield UI.div(
            serverRef.map(v => UI.span(v).id("server-region")),
            UI.div(
                clientRef.map(v => UI.span(v).id("client-region"))
            ).id("boundary").clientOwned
        )

    /** The same two regions with NO boundary: what every server-rendered page that never says `clientOwned`
      * looks like, and the case the whole feature must leave untouched.
      */
    private def plainPage(using Frame): UI < Sync =
        for
            aRef <- Signal.initRef("a-initial")
            bRef <- Signal.initRef("b-initial")
        yield UI.div(
            aRef.map(v => UI.span(v).id("a-region")),
            UI.div(bRef.map(v => UI.span(v).id("b-region"))).id("plain")
        )

    // ---- The ownership partition: each region has exactly one writer ----

    "Ownership.All subscribes every region" in {
        Scope.run {
            for
                ui   <- splitPage
                seen <- subscribedPaths(ui, Ownership.All, totalRegions = 2)
            yield assert(seen == Seq(Seq("0"), Seq("1", "0")))
        }
    }

    "Ownership.Server subscribes the server region and NOT the client-owned one" in {
        // The double-writer guard. A server that subscribes a client-owned region pushes its own stale value
        // over the wire while the browser drives the same region locally, and end to end that is invisible:
        // the browser's value still lands, so the page still looks right.
        Scope.run {
            for
                ui   <- splitPage
                seen <- subscribedPaths(ui, Ownership.Server, totalRegions = 2)
            yield assert(seen == Seq(Seq("0")))
        }
    }

    "Ownership.Client subscribes the client-owned region and NOTHING else" in {
        Scope.run {
            for
                ui   <- splitPage
                seen <- subscribedPaths(ui, Ownership.Client, totalRegions = 2)
            yield assert(seen == Seq(Seq("1", "0")))
        }
    }

    "Server and Client partition All exactly: no region unowned, none owned twice" in {
        Scope.run {
            for
                ui     <- splitPage
                all    <- subscribedPaths(ui, Ownership.All, totalRegions = 2)
                server <- subscribedPaths(ui, Ownership.Server, totalRegions = 2)
                client <- subscribedPaths(ui, Ownership.Client, totalRegions = 2)
            yield
                assert((server ++ client).sortBy(_.mkString(".")) == all, "every region has exactly one owner")
                assert(server.intersect(client).isEmpty, "no region is owned by both sides")
            end for
        }
    }

    // ---- Default-off: what every existing server-rendered page depends on ----

    "with no clientOwned anywhere, Server subscribes exactly what All does" in {
        Scope.run {
            for
                ui     <- plainPage
                all    <- subscribedPaths(ui, Ownership.All, totalRegions = 2)
                server <- subscribedPaths(ui, Ownership.Server, totalRegions = 2)
            yield
                assert(all == Seq(Seq("0"), Seq("1", "0")))
                assert(server == all, "an unmarked page is entirely server-owned, as it always is")
            end for
        }
    }

    "with no clientOwned anywhere, Client subscribes nothing" in {
        // What `UI.runHydrate` now runs on EVERY hydrated page. If it owned anything here it would be a
        // second writer against the session for a region the server is already pushing.
        Scope.run {
            for
                ui     <- plainPage
                client <- subscribedPaths(ui, Ownership.Client, totalRegions = 2)
            yield assert(client == Seq.empty)
        }
    }

    "with no clientOwned anywhere, the rendered page carries no ownership marker" in {
        Scope.run {
            for
                ui   <- plainPage
                html <- HtmlRenderer.render(ui, Seq.empty)
            yield assert(!html.contains("data-kyo-client-owned"))
        }
    }

    // ---- Inheritance: the boundary is sticky, total, and outermost-governs ----

    "ownership is inherited through plain elements down to a nested region" in {
        val ui =
            for deepRef <- Signal.initRef("deep")
            yield UI.div(
                UI.div(
                    UI.div(deepRef.map(v => UI.span(v).id("deep-region")))
                ).id("boundary").clientOwned
            )
        Scope.run {
            for
                tree   <- ui
                client <- subscribedPaths(tree, Ownership.Client, totalRegions = 1)
                server <- subscribedPaths(tree, Ownership.Server, totalRegions = 1)
            yield
                assert(client == Seq(Seq("0", "0", "0")), "the boundary reaches a region two plain levels down")
                assert(server == Seq.empty, "and the server owns none of it")
            end for
        }
    }

    "a boundary nested inside a boundary stays client-owned (marking is idempotent)" in {
        val ui =
            for ref <- Signal.initRef("v")
            yield UI.div(
                UI.div(
                    UI.div(ref.map(v => UI.span(v).id("r"))).id("inner").clientOwned
                ).id("outer").clientOwned
            )
        Scope.run {
            for
                tree   <- ui
                client <- subscribedPaths(tree, Ownership.Client, totalRegions = 1)
                server <- subscribedPaths(tree, Ownership.Server, totalRegions = 1)
            yield
                assert(client == Seq(Seq("0", "0", "0")))
                assert(server == Seq.empty)
            end for
        }
    }

    "a region OUTSIDE the boundary is still server-owned when a sibling subtree is marked" in {
        Scope.run {
            for
                ui     <- splitPage
                server <- subscribedPaths(ui, Ownership.Server, totalRegions = 2)
            yield assert(server == Seq(Seq("0")), "marking one subtree does not leak ownership to its siblings")
        }
    }

    // ---- The SSR marker, and the path scheme the two halves agree on ----

    "a marked element renders data-kyo-client-owned and an unmarked sibling does not" in {
        Scope.run {
            for
                html <- HtmlRenderer.render(
                    UI.div(UI.div("plain").id("plain"), UI.div("owned").id("owned").clientOwned),
                    Seq.empty
                )
            yield
                val plainTag = html.substring(html.indexOf("""id="plain""""))
                val ownedTag = html.substring(html.indexOf("""id="owned""""))
                assert(ownedTag.take(ownedTag.indexOf(">")).contains("data-kyo-client-owned"))
                assert(!plainTag.take(plainTag.indexOf(">")).contains("data-kyo-client-owned"))
            end for
        }
    }

    "clientOwnedRoots names the boundary at the SAME data-kyo-path the renderer stamps on it" in {
        // The server's event filter compares an inbound event path against these roots, and the browser's
        // guard walks the DOM for the marker. They agree only if this walk uses the renderer's path scheme,
        // so assert the path against the rendered markup rather than against a hand-written expectation.
        Scope.run {
            for
                ui   <- splitPage
                html <- HtmlRenderer.render(ui, Seq.empty)
            yield
                val roots = ReactiveUI.clientOwnedRoots(ui)
                assert(roots == Seq(Seq("1")), "one boundary, at the second child")
                // The marker and the path the filter compares against must sit on the SAME tag. Locate the
                // tag by its MARKER and read the path back off it, so the assertion is that the renderer
                // stamped the path this walk predicted, rather than a restatement of how the tag was found.
                val markerAt = html.indexOf("data-kyo-client-owned")
                val tagStart = html.lastIndexOf("<", markerAt)
                val tag      = html.substring(tagStart, html.indexOf(">", tagStart))
                assert(
                    tag.contains(s"""data-kyo-path="${roots.head.mkString(".")}""""),
                    s"the marked tag must carry the very path clientOwnedRoots reports, got $tag"
                )
            end for
        }
    }

    "clientOwnedRoots is empty for a page that marks nothing" in {
        Scope.run {
            for ui <- plainPage
            yield assert(ReactiveUI.clientOwnedRoots(ui) == Seq.empty)
        }
    }

    // ---- The server's event filter, tested apart from the inline client that also blocks the post ----

    private def clickAt(path: Seq[String]): UIEvent =
        UIEvent.Click(path, MouseEventData(UI.Modifiers(), Absent))

    private def dispatched(event: UIEvent, clientRoots: Seq[Seq[String]])(using Frame): Boolean < (Async & Sync) =
        for
            ran <- AtomicRef.init(false)
            handle = (_: Seq[String], _: UIEvent) => ran.set(true).andThen(true)
            _   <- UIServer.dispatchEvent(handle, HttpWebSocket.Payload.Text(Json.encode(event)), clientRoots)
            out <- ran.get
        yield out

    "the session refuses to run a handler inside a client-owned boundary, and still runs one outside it" in {
        // Independent of the inline client's own guard: a stale or hand-made client CAN post this, and the
        // browser has already run its copy of the handler, so running the session's copy fires it twice.
        //
        // The control is not decoration. "The handler did not run" is satisfied by ANY failure to reach the
        // handler at all: a wire value that stops decoding, a payload shape that stops matching, a handler
        // that is never invoked for a reason having nothing to do with ownership. Every one of those makes
        // this leaf green while the filter it names goes untested. Dispatching an event OUTSIDE the boundary
        // through the SAME encode and dispatch path, and requiring THAT one to run, is what proves the
        // machinery works and pins the refusal on the boundary rather than on a broken pipe.
        for
            inside  <- dispatched(clickAt(Seq("1", "0")), Seq(Seq("1")))
            outside <- dispatched(clickAt(Seq("0")), Seq(Seq("1")))
        yield
            assert(outside, "the same encode and dispatch path must reach a handler outside the boundary")
            assert(!inside, "so refusing the one inside it is the ownership filter, not a dead pipe")
        end for
    }

    "with no client-owned boundary the session runs every handler" in {
        dispatched(clickAt(Seq("1", "0")), Seq.empty).map(ran => assert(ran))
    }

    "a path that merely shares a prefix segment with a boundary is not treated as inside it" in {
        // Seq("1") is a boundary; Seq("10") is a different sibling whose path STRING starts with "1".
        dispatched(clickAt(Seq("10")), Seq(Seq("1"))).map(ran => assert(ran))
    }

    // ---- The unhydratable-page warning ----

    private def warningsFor(ui: UI, head: UI.PageHead)(using Frame): Chunk[String] < Sync =
        val backend = new CapturingLog(Log.Level.warn)
        Log.let(Log(backend)) {
            UIServer.warnUnhydratableClientRegions(ui, head).andThen(backend.warnings)
        }
    end warningsFor

    "a page with a client-owned region and no moduleScript is reported as unhydratable" in {
        Scope.run {
            for
                ui       <- splitPage
                warnings <- warningsFor(ui, UI.PageHead("t"))
            yield
                assert(warnings.size == 1)
                assert(warnings.head.contains("clientOwned"))
                assert(warnings.head.contains("moduleScript"))
                assert(warnings.head.contains("1"), "the warning names the path that will not move")
            end for
        }
    }

    "a page with a client-owned region and a moduleScript is not reported" in {
        Scope.run {
            for
                ui       <- splitPage
                warnings <- warningsFor(ui, UI.PageHead("t", moduleScript = Present("/app.js")))
            yield assert(warnings.isEmpty)
        }
    }

    "a page with no client-owned region is not reported, moduleScript or not" in {
        Scope.run {
            for
                ui       <- plainPage
                warnings <- warningsFor(ui, UI.PageHead("t"))
            yield assert(warnings.isEmpty)
        }
    }

    // ---- A boundary that lands in a reactive position is reported, not half-supported ----

    "a clientOwned boundary inside a reactive region is reported, not silently half-supported" in {
        // The boundary must sit in a non-reactive position: a server re-render of the enclosing reactive
        // region replaces the very subtree the browser would drive, so the ownership walk warns rather than
        // half-support it. Every other ownership leaf here places the boundary in a const position, so this
        // is the one guard that a future change to the underReactive propagation cannot silence unnoticed.
        // The warning fires inside the outer region's fiber, so the fence is subscribedPaths' control drain
        // (the outer region has emitted and re-walked to the boundary by the time both control regions have),
        // never a timer: the test clock is frozen and a timed settle would elapse zero time.
        val backend = new CapturingLog(Log.Level.warn)
        Log.let(Log(backend)) {
            Scope.run {
                for
                    outerRef <- Signal.initRef("v")
                    innerRef <- Signal.initRef("w")
                    ui = UI.div(
                        outerRef.map(_ =>
                            UI.div(innerRef.map(v => UI.span(v).id("inner"))).id("boundary").clientOwned
                        )
                    )
                    seen <- subscribedPaths(ui, Ownership.Server, totalRegions = 2)
                yield
                    assert(seen == Seq(Seq("0")), "the server owns the enclosing reactive region and stops at the boundary")
                    assert(backend.warnings.size == 1, "exactly one warning for the boundary in a reactive position")
                    assert(backend.warnings.head.contains("clientOwned"))
                    assert(backend.warnings.head.contains("reactive region"))
                end for
            }
        }
    }

    // ---- End to end, in a real browser against a real session ----

    /** A server-owned button at path "0" and a client-owned one at "2.0". */
    private def buttonPage: UI < Async =
        for
            serverHits <- Signal.initRef("none")
            clientHits <- Signal.initRef("none")
        yield UI.div(
            UI.button("server").id("server-btn").onClick(serverHits.set("hit")),
            serverHits.map(v => UI.span(v).id("server-out")),
            UI.div(
                UI.button("client").id("client-btn").onClick(clientHits.set("hit")),
                clientHits.map(v => UI.span(v).id("client-out"))
            ).id("owned").clientOwned
        )

    "the inline client does not forward an event out of a client-owned subtree" in {
        // The inline guard tested APART from the session's own filter, which would otherwise mask it: this
        // asserts on what the browser PUTS ON THE WIRE, so it holds regardless of what the server would have
        // done with the frame. Patching send on the prototype catches the already-open socket.
        withUI(buttonPage) {
            for
                _ <- Browser.evalDiscard(
                    """window.__posts=[];
                      |var _s=WebSocket.prototype.send;
                      |WebSocket.prototype.send=function(d){window.__posts.push(d);return _s.call(this,d);};""".stripMargin
                )
                _ <- Browser.click(Selector.id("client-btn"))
                _ <- Browser.click(Selector.id("server-btn"))
                // The server-owned click round-trips, which fences the read below and proves the page's event
                // channel is alive: the client-owned click's absence is the guard, not a dead page.
                _ <- Browser.assertText(Selector.id("server-out"), "hit")
                posts <- Browser.eval(
                    """JSON.stringify(window.__posts.map(function(p){
                      |  var o=JSON.parse(p); var k=Object.keys(o)[0];
                      |  return k+":"+o[k].path.join(".");
                      |}))""".stripMargin
                )
            yield assert(posts == """["Click:0"]""", "only the server-owned button's click reached the wire")
        }
    }

    "on a server-driven page, a click inside a client-owned region never reaches the server handler" in {
        withUI(buttonPage) {
            for
                _ <- Browser.click(Selector.id("server-btn"))
                _ <- Browser.assertText(Selector.id("server-out"), "hit")
                _ <- Browser.click(Selector.id("client-btn"))
                // Nothing hydrates this page, so the browser does not run the handler either. The server must
                // not run it: if it did, this region would read "hit".
                _ <- Browser.assertText(Selector.id("client-out"), "none")
            yield ()
        }
    }

end ReactiveUIOwnershipTest
