package kyo

import kyo.internal.HtmlOp
import kyo.internal.MouseEventData
import kyo.internal.UIEvent
import kyo.internal.UIServer

/** Direct end-to-end proof that the REAL UIServer.serveSession handler round-trips events and tears
  * down the server's per-connection subscription tree on disconnect (disconnect = teardown cascade,
  * no leaked observation).
  *
  * Leaves 1 and 2 use [[HttpWebSocket.connect]] to wire the real [[UIServer.serveSession]] body to
  * a test client without starting a real [[HttpServer]]. Both leaves are `.notNative`: while
  * [[HttpWebSocket.connect]] itself is epoll-free, the plan mandates `.notNative` on all WS-behavior
  * leaves (RI-001 rationale; leaves 3 and 4 are pure codec tests and run on every platform).
  *
  * Teardown witness (public API only): the test wraps the real `serveSession` call in `Sync.ensure`,
  * so `serverEnded` flips when the connection's handler ends (its `Scope.run` completes or is
  * interrupted on disconnect, either way closing the subscription's owning `Scope`). After the client
  * closes, `assertEventually(serverEnded)` confirms that scope closed; then a single set on a
  * test-held leaf `SignalRef` proves the subscription's observation was released (a leaked live
  * subscription would re-park on the swapped promise and keep `waiters >= 1`).
  */
class UIServerWsTest extends kyo.test.Test[Any]:

    // ==================== Leaf 1: round trip ====================

    // notNative: WS-behavior leaf; see class scaladoc for RI-001 rationale.
    "round trip: event in -> render out over WS".notNative in {
        for
            ref <- Signal.initRef("before")
            // captured holds the second frame received by the client (the reactive-push after the click).
            captured <- AtomicRef.init(Absent: Maybe[String])
            app = UI.div(
                UI.button("Click").id("btn").onClick(ref.set("after")),
                ref.map(v => UI.span(v).id("val"))
            )
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            // Await the initial render frame to confirm the subscription is live.
                            _ <- clientWs.take()
                            // Dispatch a Click on the button (path Seq("0"): first child of the div).
                            clickEvent = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
                            _ <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](clickEvent)))
                            // The next frame must be an HtmlOp.Replace containing "after".
                            frame <- clientWs.take()
                            text = frame match
                                case HttpWebSocket.Payload.Text(data) => Present(data)
                                case _                                => Absent
                            _ <- captured.set(text)
                        yield ()
                )
            }
            capturedData <- captured.get
        yield capturedData match
            case Present(data) =>
                Json.decode[HtmlOp](data) match
                    case Result.Success(HtmlOp.Replace(_, html)) => assert(html.contains("after"))
                    case other                                   => fail(s"expected HtmlOp.Replace with 'after', got: $other")
            case Absent => fail("client did not receive a second frame after the click")
        end for
    }

    // ==================== Leaf 2: disconnect tears down ====================

    // notNative: WS-behavior leaf; see class scaladoc for RI-001 rationale.
    // Stressed .times(20) to confirm non-flaky teardown cascade.
    "disconnect tears down: subscription released after socket close".notNative.times(20) in {
        for
            // The test retains leafRef to assert its observation (waiters) and to SET it after teardown.
            leafRef <- Signal.initRef(0)
            app = UI.div(leafRef.map(n => UI.span(n.toString)))
            // serverEnded flips when the real serveSession handler ends on disconnect (its Scope.run completes or is
            // interrupted, either way closing the connection's subscription Scope). Public witness, no internal hook.
            serverEnded <- AtomicBoolean.init(false)
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => Sync.ensure(serverEnded.set(true))(UIServer.serveSession(serverWs, app)),
                    (clientWs: HttpWebSocket) =>
                        for
                            // Await the initial render frame, then confirm the server subscription is live: it parks on
                            // the test-held leaf, so leafRef has exactly one waiter.
                            _ <- clientWs.take()
                            _ <- assertEventually(leafRef.waiters.map(_ == 1))
                            // Close the client: fires ws.onPeerClose on the server, ending the race and closing the
                            // connection's subscription Scope (cascade teardown).
                            _ <- clientWs.close()
                        yield ()
                )
            }
            // The connection's owning Scope closed (serveSession ended): the cascade ran.
            _ <- assertEventually(serverEnded.get)
            // Leaf witness: SET the leaf to swap its promise, discarding the parked ghost. A leaked live subscription
            // re-parks on the new promise (waiters >= 1); a torn-down one does not, so waiters settles to 0.
            _       <- leafRef.set(99)
            _       <- assertEventually(leafRef.waiters.map(_ == 0))
            waiters <- leafRef.waiters
        yield assert(waiters == 0)
        end for
    }

    // ==================== Leaf 3: HtmlOp JSON codec (cross-platform) ====================

    "HtmlOp JSON round-trips through the wire codec" in {
        val op      = HtmlOp.Replace(Seq("a", "b"), "<span>x</span>")
        val encoded = Json.encode[HtmlOp](op)
        val decoded = Json.decode[HtmlOp](encoded)
        assert(decoded == Result.Success(op))
    }

    // ==================== Leaf 4: UIEvent JSON codec (cross-platform) ====================

    "UIEvent JSON round-trips through the wire codec" in {
        val event   = UIEvent.Click(Seq("btn"), MouseEventData(UI.Modifiers.none, Absent))
        val encoded = Json.encode[UIEvent](event)
        val decoded = Json.decode[UIEvent](encoded)
        assert(decoded == Result.Success(event))
    }

end UIServerWsTest
