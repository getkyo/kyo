package kyo.internal

import kyo.*

/** Tests the locked `UI.runHandlers` PageHead overload's effect on the served SSR page: it threads
  * `head.moduleScript` into the page so a server-push app links its client island bundle, the 1-arg
  * form is unchanged and delegates to the 2-arg form with `PageHead("kyo-ui")`, and the page emits a
  * per-host init data island for every host carrying a `HostBridge` (the client island reads it to
  * mount without a WS round-trip).
  *
  * The serve-and-fetch leaves run a real `HttpServer` and `HttpClient.getText`; that transport is
  * JVM+JS (notNative). The renderPage / injectHostIsland leaves are pure string assertions that run
  * on every platform.
  */
class UIServerHostUpdateTest extends kyo.test.Test[Any]:

    private val pointer = PointerData(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /** A minimal host bridge whose serverInit returns a fixed boot payload, so the served page emits a
      * known init island. Never calls back into kyo-ui; subscriptions returns without parking.
      */
    private class BootBridge(boot: HostPayload) extends UI.Ast.HostBridge:
        private[kyo] def serverInit(path: Seq[String]): HostPayload < Sync =
            Sync.defer(boot)
        private[kyo] def subscriptions(
            path: Seq[String],
            emit: HostPayload => Unit < Async
        )(using Frame): Unit < (Async & Scope) =
            Kyo.unit
        private[kyo] def onPick(path: Seq[String], nodeId: String, pointer: PointerData)(using Frame): Unit < Async =
            Kyo.unit
    end BootBridge

    private def fetchPage(ui: UI, head: Maybe[UI.PageHead])(using Frame): String < (Async & Abort[HttpException]) =
        Scope.run {
            for
                handlers <- head match
                    case Present(h) => UI.runHandlers("/", h)(ui)
                    case Absent     => UI.runHandlers("/")(ui)
                server <- HttpServer.init(0, "localhost")(handlers*)
                body   <- HttpClient.getText(s"http://localhost:${server.port}/")
            yield body
        }

    // ---------- serve-and-fetch leaves (JVM+JS) ----------

    "runHandlers with moduleScript links the island bundle".notNative in {
        fetchPage(
            UI.div("hi"),
            Present(UI.PageHead("app", moduleScript = Present("/_kyo/island.js")))
        ).map { body =>
            assert(
                body.contains("""<script type="module" src="/_kyo/island.js"></script>"""),
                s"the page must link the island bundle; body was:\n$body"
            )
            assert(body.contains("<script>"), "the inline server-push client script must still be present")
        }
    }

    "the 1-arg runHandlers links no island".notNative in {
        fetchPage(UI.div("hi"), Absent).map { body =>
            assert(
                !body.contains("""<script type="module""""),
                s"the 1-arg form must link NO module island; body was:\n$body"
            )
        }
    }

    "the 1-arg form equals the 2-arg PageHead(kyo-ui) default".notNative in {
        val ui = UI.div("hi")
        for
            oneArg <- fetchPage(ui, Absent)
            twoArg <- fetchPage(ui, Present(UI.PageHead("kyo-ui")))
        yield assert(
            oneArg == twoArg,
            s"the 1-arg form must render byte-identically to the 2-arg PageHead(\"kyo-ui\") default"
        )
        end for
    }

    "the served page emits a data-kyo-host-init island for a host carrying a bridge".notNative in {
        val boot = HostPayload.Prop("r.0", "color", HostValue.Col(0x00ff00))
        val app  = UI.div(UI.host("canvas").withServerBridge(BootBridge(boot)))
        fetchPage(app, Present(UI.PageHead("app", moduleScript = Present("/_kyo/island.js")))).map { body =>
            assert(
                body.contains("data-kyo-host"),
                s"the host element must carry the data-kyo-host marker; body was:\n$body"
            )
            assert(
                body.contains("""<script type="application/json" data-kyo-host-init>"""),
                s"the host must nest an init island; body was:\n$body"
            )
            // The init island carries the JSON-encoded boot payload.
            val expectedJson = Json.encode[HostPayload](boot)
            assert(
                body.contains(expectedJson) || body.contains(expectedJson.replace("<", "\\u003c").replace(">", "\\u003e")),
                s"the init island must carry the boot payload JSON ($expectedJson); body was:\n$body"
            )
        }
    }

    "a host with NO bridge gets no init island".notNative in {
        val app = UI.div(UI.host("canvas"))
        fetchPage(app, Absent).map { body =>
            assert(
                !body.contains("data-kyo-host-init"),
                s"a bare host (no bridge) must get no init island; body was:\n$body"
            )
        }
    }

    // ---------- pure renderPage / injectHostIsland leaves (all platforms) ----------

    "renderPage appends a module script only when moduleScript is Present" in {
        val withScript = HtmlRenderer.renderPage("t", "<p>b</p>", "", "/", Present("/x.js"))
        val without    = HtmlRenderer.renderPage("t", "<p>b</p>", "", "/", Absent)
        assert(withScript.contains("""<script type="module" src="/x.js"></script>"""))
        assert(!without.contains("""<script type="module""""))
        // The 1-arg-equivalent (default Absent) equals the explicit Absent.
        assert(HtmlRenderer.renderPage("t", "<p>b</p>", "", "/") == without)
    }

    "injectHostIsland adds the marker and the nested init island at the host path" in {
        val html     = """<div data-kyo-path=""><canvas data-kyo-path="0"></canvas></div>"""
        val injected = HtmlRenderer.injectHostIsland(html, "0", """{"k":1}""")
        assert(injected.contains("data-kyo-host"), "the host opening tag must gain data-kyo-host")
        assert(
            injected.contains("""<script type="application/json" data-kyo-host-init>{"k":1}</script></canvas>"""),
            s"the init island must nest inside the host before its closing tag; got:\n$injected"
        )
    }

    "injectHostIsland escapes a </script> in the JSON body so it cannot close the element early" in {
        val html     = """<canvas data-kyo-path="0"></canvas>"""
        val injected = HtmlRenderer.injectHostIsland(html, "0", """{"x":"</script>"}""")
        assert(!injected.contains("""{"x":"</script>"}"""), "a literal </script> must be escaped in the island body")
        assert(injected.contains("\\u003c/script\\u003e"), "the </script> must be JSON-unicode-escaped")
    }

    "injectHostIsland leaves HTML unchanged when no element matches the path" in {
        val html = """<canvas data-kyo-path="0"></canvas>"""
        assert(HtmlRenderer.injectHostIsland(html, "9", """{"k":1}""") == html)
    }

    "the locked 2-arg runHandlers signature type-checks at its declared type" in {
        val _: (String, UI.PageHead) => (=> UI < Async) => Frame ?=> Seq[HttpHandler[?, ?, ?]] < Sync =
            (basePath, head) => ui => UI.runHandlers(basePath, head)(ui)
        // Sanity: the pointer payload type is the frozen wire pick shape (no closure crosses).
        assert(pointer.distance == 1.0)
    }

    // notNative: WS-behavior leaf; HttpWebSocket.connect is not available on Native.
    "round-trip: server signal emission reaches client as HostUpdate; client HostPick runs server closure; setup fires once".notNative in {
        for
            serverSignal  <- Signal.initRef(0)
            setupCounter  <- AtomicInt.init(0)
            ready         <- Channel.initUnscoped[Unit](4)
            done          <- Channel.initUnscoped[Unit](4)
            capturedValue <- AtomicRef.init(Absent: Maybe[HostPayload])
            // A local host bridge whose subscriptions body forks a signal observer (forked under
            // the ambient Scope so subscriptions returns) and whose onPick writes the signal to 42
            // then signals the done latch. The setup counter counts how many times subscriptions
            // was called; the assertion requires exactly 1.
            bridge = new UI.Ast.HostBridge:
                private[kyo] def serverInit(path: Seq[String]): HostPayload < Sync =
                    Sync.defer(HostPayload.Prop("n0", "value", HostValue.Num(0.0)))
                private[kyo] def subscriptions(
                    path: Seq[String],
                    emit: HostPayload => Unit < Async
                )(using Frame): Unit < (Async & Scope) =
                    setupCounter.incrementAndGet.andThen {
                        // Fork the observer under the ambient Scope; Signal.observe never terminates
                        // so it must not block subscriptions from returning. The sentinel value (0)
                        // signals that the observer is live; non-zero values emit a HostPayload.Prop.
                        Fiber.init {
                            serverSignal.observe { v =>
                                if v != 0 then emit(HostPayload.Prop("n0", "value", HostValue.Num(v.toDouble)))
                                else Abort.run[Closed](ready.put(())).unit
                            }
                        }.unit
                    }
                private[kyo] def onPick(path: Seq[String], nodeId: String, ptr: PointerData)(using Frame): Unit < Async =
                    serverSignal.set(42).andThen(Abort.run[Closed](done.put(())).unit)
            app = UI.div(UI.host("div").withServerBridge(bridge))
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            // Wait until the observer is live before setting the test value, so the
                            // signal set(7) is guaranteed to be observed by the forked observer.
                            _     <- ready.take
                            _     <- serverSignal.set(7)
                            frame <- clientWs.take()
                            _ <- frame match
                                case HttpWebSocket.Payload.Text(data) =>
                                    Json.decode[HtmlOp](data) match
                                        case Result.Success(HtmlOp.HostUpdate(_, payload)) =>
                                            capturedValue.set(Present(payload))
                                        case _ => Kyo.unit
                                case _ => Kyo.unit
                            pick = UIEvent.HostPick(Seq("0"), "node-0", pointer)
                            _ <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](pick)))
                            // Wait for the server's onPick closure to complete (serverSignal.set(42) +
                            // done.put) before the client exits; without this the WS can tear down
                            // before the server's closure body runs to completion.
                            _ <- done.take
                        yield ()
                )
            }
            payload  <- capturedValue.get
            sigFinal <- serverSignal.current
            count    <- setupCounter.get
        yield
            assert(
                payload == Present(HostPayload.Prop("n0", "value", HostValue.Num(7.0))),
                s"expected HostUpdate payload Num(7.0), got: $payload"
            )
            assert(sigFinal == 42, s"expected serverSignal.current == 42 after HostPick, got: $sigFinal")
            assert(count == 1, s"expected setup counter == 1 (subscriptions fires once), got: $count")
        end for
    }

end UIServerHostUpdateTest
