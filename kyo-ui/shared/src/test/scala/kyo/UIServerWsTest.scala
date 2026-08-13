package kyo

import kyo.internal.DragProtocol
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

    // ==================== Leaf 5: drag protocol envelopes ====================

    "DragProtocol.ClientMessage cases round-trip through exact JSON wire representations" in {
        val event = UIEvent.DragEnd(
            Seq("source"),
            DragProtocol.EndData("session-2", Drag.Operation.Link, cancelled = false)
        )
        val file = Drag.FileMeta(
            token = "file-token",
            name = "notes.txt",
            mediaType = "text/plain",
            size = 12.bytes,
            lastModified = Instant.Epoch
        )
        val cases = Chunk[(DragProtocol.ClientMessage, String)](
            DragProtocol.ClientMessage.Event(event) ->
                """{"Event":{"value":{"DragEnd":{"path":["source"],"event":{"sessionId":"session-2","operation":{"Link":{}},"cancelled":false}}}}}""",
            DragProtocol.ClientMessage.FileChunk("read-1", Chunk[Byte](1, -2, 3), done = true) ->
                """{"FileChunk":{"requestId":"read-1","bytes":[1,-2,3],"done":true}}""",
            DragProtocol.ClientMessage.FileEntries(
                "read-2",
                Chunk(
                    DragProtocol.EntryData.File(file),
                    DragProtocol.EntryData.Directory("dir-token", "assets")
                ),
                done = false
            ) ->
                """{"FileEntries":{"requestId":"read-2","entries":[{"File":{"meta":{"token":"file-token","name":"notes.txt","mediaType":"text/plain","size":12,"lastModified":"1970-01-01T00:00:00Z"}}},{"Directory":{"token":"dir-token","name":"assets"}}],"done":false}}""",
            DragProtocol.ClientMessage.FileFailure(
                "read-3",
                DragProtocol.FileFailureData.LimitExceeded("entry limit")
            ) ->
                """{"FileFailure":{"requestId":"read-3","failure":{"LimitExceeded":{"reason":"entry limit"}}}}"""
        )

        cases.foreach { case (message, expected) =>
            val encoded = Json.encode[DragProtocol.ClientMessage](message)
            assert(encoded == expected)
            assert(Json.decode[DragProtocol.ClientMessage](encoded) == Result.succeed(message))
        }
    }

    "DragProtocol.ClientMessage distinguishes UI events from file responses" in {
        val eventJson =
            """{"Event":{"value":{"DragEnd":{"path":["source"],"event":{"sessionId":"session-2","operation":{"Copy":{}},"cancelled":false}}}}}"""
        val chunkJson = """{"FileChunk":{"requestId":"read-1","bytes":[7],"done":true}}"""

        assert(
            Json.decode[DragProtocol.ClientMessage](eventJson) == Result.succeed(
                DragProtocol.ClientMessage.Event(
                    UIEvent.DragEnd(Seq("source"), DragProtocol.EndData("session-2", Drag.Operation.Copy, cancelled = false))
                )
            )
        )
        assert(
            Json.decode[DragProtocol.ClientMessage](chunkJson) == Result.succeed(
                DragProtocol.ClientMessage.FileChunk("read-1", Chunk[Byte](7), done = true)
            )
        )
    }

    "drag HtmlOp cases round-trip through exact JSON wire representations" in {
        val cases = Chunk[(HtmlOp, String)](
            HtmlOp.ReadDropFile("read-file", "file-token", 4.kib, 64.kib) ->
                """{"ReadDropFile":{"requestId":"read-file","token":"file-token","offset":4096,"maxSize":65536}}""",
            HtmlOp.ReadDropDirectory("read-dir", "dir-token", Present("cursor-1"), 50) ->
                """{"ReadDropDirectory":{"requestId":"read-dir","token":"dir-token","cursor":"cursor-1","maxEntries":50}}""",
            HtmlOp.CancelDropRead("read-file") ->
                """{"CancelDropRead":{"requestId":"read-file"}}""",
            HtmlOp.ResolveDrag("session-2", Drag.Decision.Reject(Drag.Rejection.Application("locked"))) ->
                """{"ResolveDrag":{"sessionId":"session-2","decision":{"Reject":{"rejection":{"Application":{"reason":"locked"}}}}}}"""
        )

        cases.foreach { case (op, expected) =>
            val encoded = Json.encode[HtmlOp](op)
            assert(encoded == expected)
            assert(Json.decode[HtmlOp](encoded) == Result.succeed(op))
        }
    }

    // ==================== Leaf 6: scrollIntoView command over WS ====================

    // notNative: WS-behavior leaf; see class scaladoc for RI-001 rationale.
    "scrollIntoView: a click handler's command reaches the client as a ScrollIntoView op".notNative in {
        for
            // captured holds the frame received after the click (the command the handler sent).
            captured <- AtomicRef.init(Absent: Maybe[String])
            ref      <- Signal.initRef("static")
            // The reactive region produces the initial render frame the client awaits before clicking;
            // the handler never touches it, so the click's only response frame is the scroll command.
            app = UI.div(
                UI.button("Jump").id("btn").onClick(UI.scrollIntoView("card-7")),
                ref.map(v => UI.span(v))
            )
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            // Await the initial render frame to confirm the subscription is live.
                            _ <- clientWs.take()
                            clickEvent = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
                            _     <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](clickEvent)))
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
                assert(Json.decode[HtmlOp](data) == Result.Success(HtmlOp.ScrollIntoView("card-7")))
            case Absent => fail("client did not receive a frame after the click")
        end for
    }

    "scrollIntoView outside any session is a no-op" in {
        // No runner installed a sink (plain test context): the command completes without effect.
        UI.scrollIntoView("nowhere").andThen(succeed)
    }

    "ScrollIntoView JSON round-trips through the wire codec" in {
        val op      = HtmlOp.ScrollIntoView("card-7")
        val encoded = Json.encode[HtmlOp](op)
        assert(encoded.contains("ScrollIntoView"), s"the client dispatches on the op name; got $encoded")
        assert(Json.decode[HtmlOp](encoded) == Result.Success(op))
    }

end UIServerWsTest
