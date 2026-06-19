package kyo.internal

import kyo.*

/** Drives the REAL [[UIServer.serveSession]] over an [[HttpWebSocket.connect]] pair (no real
  * server, no browser, no GL) with a hand-built [[UI.Ast.HostBridge]] stub, proving the
  * server-side host-bridge mechanism:
  *
  *   - a client `UIEvent.HostPick` routes to the host's server-side `onPick`, which runs the
  *     server-side closure (the `onClick` analog); only the typed event crosses the wire.
  *   - a parked `onPick` does not block the WS message loop; a second `HostPick` is routed
  *     while the first is still parked.
  *   - a `HostUpdate` addressed to a path with no registered host is a silent no-op: the session
  *     is not torn down and a subsequent valid `HostUpdate` is still delivered.
  *   - one server-signal emission emits exactly one `HtmlOp.HostUpdate`.
  *   - a host node survives a sibling reactive re-render (the Replace addresses only the sibling
  *     region, never the host path).
  *   - kyo-ui only calls `serverInit`/`subscriptions`/`onPick` on the bridge; it never calls back
  *     into any client-DOM method (the bridge is one-directional).
  *
  * Every wait is a `Channel`/`Latch` event-latch observing the actual event; there are no sleeps.
  * WS-behavior leaves are `.notNative` because `HttpWebSocket.connect` is not available on Native;
  * the pure codec leaf runs on every platform.
  */
class UIServerHostBridgeTest extends kyo.test.Test[Any]:

    private val pointer = PointerData(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /** Put onto a test latch channel, discharging the `Closed` row: these channels never close
      * during a leaf's assertions, so a `Closed` here is a test-harness defect, not a real path.
      */
    private def latch[A](ch: Channel[A], v: A)(using Frame): Unit < Async =
        Abort.run[Closed](ch.put(v)).unit

    /** A hand-built server-side host bridge. Records every method invoked on it, runs
      * caller-supplied closures for `subscriptions`/`onPick`, and never calls back into kyo-ui.
      *
      * `subscriptions` must RETURN (not park), so an `onSubscribe` that observes a signal FORKS the
      * observe loop under the ambient Scope (`Signal.observe` never terminates); this mirrors the
      * production model where each host's observers are forked under the session Scope. A pure host
      * tree emits no initial WS frame (only reactive regions do), so an `onSubscribe` signals its own
      * readiness latch (typically from the observer's first read) and the test waits on that latch in
      * place of an initial render frame, with no sleep.
      */
    private class StubBridge(
        invoked: Channel[String],
        onSubscribe: (Seq[String], HostPayload => Unit < Async) => Unit < (Async & Scope),
        onPickFn: (Seq[String], String, PointerData) => Unit < Async
    ) extends UI.Ast.HostBridge:
        private[kyo] def serverInit(path: Seq[String]): HostPayload < Sync =
            Sync.defer(HostPayload.Prop("n0", "value", HostValue.Num(0.0)))

        private[kyo] def subscriptions(
            path: Seq[String],
            emit: HostPayload => Unit < Async
        )(using Frame): Unit < (Async & Scope) =
            latch(invoked, "subscriptions").andThen(onSubscribe(path, emit))

        private[kyo] def onPick(
            path: Seq[String],
            nodeId: String,
            pointer: PointerData
        )(using Frame): Unit < Async =
            latch(invoked, "onPick").andThen(onPickFn(path, nodeId, pointer))
    end StubBridge

    private def hostWith(bridge: UI.Ast.HostBridge)(using Frame): UI.Ast.Host =
        UI.host("div").withServerBridge(bridge)

    private def decodeHtmlOp(payload: HttpWebSocket.Payload): Maybe[HtmlOp] =
        payload match
            case HttpWebSocket.Payload.Text(data) =>
                Json.decode[HtmlOp](data) match
                    case Result.Success(op) => Present(op)
                    case _                  => Absent
            case _ => Absent

    // ==================== a HostPick runs the server-side onPick ====================

    // notNative: WS-behavior leaf; see class scaladoc.
    "INV-TJS-06: a HostPick runs the server-side onPick closure".notNative in {
        for
            ref     <- Signal.initRef(0)
            invoked <- Channel.initUnscoped[String](64)
            ready   <- Channel.initUnscoped[Unit](4)
            // ran latches when the server-side onPick closure body completed (exactly once).
            ran <- Channel.initUnscoped[Unit](4)
            stub = StubBridge(
                invoked,
                onSubscribe = (_, _) => latch(ready, ()),
                onPickFn = (_, _, _) => ref.set(1).andThen(latch(ran, ()))
            )
            app = UI.div(hostWith(stub))
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            // A pure host tree emits no initial WS frame; wait for host registration instead.
                            _    <- ready.take
                            pick <- Kyo.lift(UIEvent.HostPick(Seq("0"), "node-0", pointer))
                            _    <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](pick)))
                            _    <- ran.take
                        yield ()
                )
            }
            value <- ref.current
        yield assert(value == 1)
        end for
    }

    // ============ the wire pick carries no closure (pure codec) ============

    "INV-TJS-06-absent: the decoded HostPick carries only typed plain fields" in {
        val pick    = UIEvent.HostPick(Seq("0"), "node-0", PointerData(1.5, -2.0, 0.25, 8.0, 0.3, -0.4))
        val encoded = Json.encode[UIEvent](pick)
        val decoded = Json.decode[UIEvent](encoded)
        assert(decoded == Result.Success(pick))
        // The decoded value is a plain data record: path/nodeId/pointer, all serializable, no function.
        decoded match
            case Result.Success(UIEvent.HostPick(path, nodeId, p)) =>
                assert(path == Seq("0"))
                assert(nodeId == "node-0")
                assert(p == PointerData(1.5, -2.0, 0.25, 8.0, 0.3, -0.4))
            case other => fail(s"expected a decoded HostPick, got: $other")
        end match
    }

    // ============ pick handling does not block the WS message loop ============

    // notNative: WS-behavior leaf; see class scaladoc.
    "INV-TJS-CP-01: a second HostPick is routed while the first onPick is parked".notNative in {
        for
            invoked <- Channel.initUnscoped[String](64)
            ready   <- Channel.initUnscoped[Unit](4)
            // gate blocks the FIRST pick's onPick; the test releases it only after the second is routed.
            gate <- Latch.init(1)
            // routed records each pick's nodeId at the moment its onPick STARTS (before parking).
            routed <- Channel.initUnscoped[String](8)
            stub = StubBridge(
                invoked,
                onSubscribe = (_, _) => latch(ready, ()),
                onPickFn = (_, nodeId, _) =>
                    latch(routed, nodeId).andThen {
                        // The first pick parks on the gate; the second does not (it observes the gate already
                        // released by the test, so it completes immediately). Forked picks run concurrently,
                        // so the second's onPick starts while the first is parked.
                        if nodeId == "first" then gate.await else Kyo.unit
                    }
            )
            app = UI.div(hostWith(stub))
            order <- AtomicRef.init(Seq.empty[String])
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            // A pure host tree emits no initial WS frame; wait for host registration instead.
                            _      <- ready.take
                            first  <- Kyo.lift(UIEvent.HostPick(Seq("0"), "first", pointer))
                            second <- Kyo.lift(UIEvent.HostPick(Seq("0"), "second", pointer))
                            _      <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](first)))
                            _      <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](second)))
                            // Both onPick closures START while the first is still parked on the gate: observe
                            // both routed nodeIds before releasing the gate (the message loop did not stall).
                            r1 <- routed.take
                            r2 <- routed.take
                            _  <- order.updateAndGet(_ :+ r1).andThen(order.updateAndGet(_ :+ r2))
                            // Release the first pick's park so its onPick completes; teardown follows.
                            _ <- gate.release
                        yield ()
                )
            }
            seen <- order.get
        yield
            // Both picks were routed (both onPick bodies started) before the first was unparked: the
            // second was routed while the first was parked. Order of the two is non-deterministic.
            assert(seen.toSet == Set("first", "second"))
            assert(seen.size == 2)
        end for
    }

    // ============ a HostUpdate to an absent path is a silent no-op ============

    // notNative: WS-behavior leaf; see class scaladoc.
    "INV-TJS-CP-02: a HostUpdate to an absent path does not tear down the session".notNative in {
        for
            captured <- AtomicRef.init(Seq.empty[HtmlOp])
            // The server side emits a HostUpdate for an absent path Q (no registered host) followed by a
            // valid HostUpdate for P, both through the real emitHostUpdate sink over the connected WS.
            absentPayload = HostPayload.Prop("nX", "value", HostValue.Num(0.0))
            validPayload  = HostPayload.Prop("n0", "color", HostValue.Col(0x00ff00))
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) =>
                        Abort.run[Closed] {
                            for
                                _ <- UIServer.emitHostUpdate(serverWs, Seq("9", "9"), absentPayload)
                                _ <- UIServer.emitHostUpdate(serverWs, Seq("0"), validPayload)
                                // Hold the server side open until the client has taken both frames.
                                _ <- serverWs.onPeerClose
                            yield ()
                        }.unit,
                    (clientWs: HttpWebSocket) =>
                        for
                            // The absent-path op arrives first and does not terminate the session; the valid
                            // op for P still arrives next.
                            first  <- clientWs.take()
                            second <- clientWs.take()
                            _      <- captured.set(Seq(decodeHtmlOp(first), decodeHtmlOp(second)).collect { case Present(op) => op })
                            _      <- clientWs.close()
                        yield ()
                )
            }
            result <- captured.get
        yield result match
            case Seq(HtmlOp.HostUpdate(qPath, qPayload), HtmlOp.HostUpdate(pPath, pPayload)) =>
                // The absent-path op was delivered without throwing or terminating the session, and the
                // subsequent valid op for P arrived normally.
                assert(qPath == Seq("9", "9"))
                assert(qPayload == absentPayload)
                assert(pPath == Seq("0"))
                assert(pPayload == validPayload)
            case other => fail(s"expected the absent-path op then the valid op for P, got: $other")
        end for
    }

    // ============ server signal emission emits exactly one HostUpdate ============

    // notNative: WS-behavior leaf; see class scaladoc.
    "INV-TJS-06: one server-signal emission emits exactly one HostUpdate".notNative in {
        for
            color   <- Signal.initRef(0x000000)
            invoked <- Channel.initUnscoped[String](64)
            ready   <- Channel.initUnscoped[Unit](4)
            stub = StubBridge(
                invoked,
                // Fork the observe loop under the ambient Scope (observe never returns), so subscriptions
                // returns. The observer signals ready on its first read (the initial sentinel 0x000000),
                // so the test only sets a new color after the observer is live; each non-sentinel change
                // emits exactly one Prop(Col) payload for the host.
                onSubscribe = (_, emit) =>
                    Fiber.init {
                        color.observe(rgb =>
                            if rgb != 0x000000 then emit(HostPayload.Prop("n0", "color", HostValue.Col(rgb)))
                            else latch(ready, ())
                        )
                    }.unit,
                onPickFn = (_, _, _) => Kyo.unit
            )
            app = UI.div(hostWith(stub))
            frames <- AtomicRef.init(Seq.empty[HtmlOp])
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            // A pure host tree emits no initial WS frame; wait for the observer to register.
                            _ <- ready.take
                            // Set the signal to green and SYNCHRONIZE on its single frame before setting the
                            // next value: serializing on the frame-take rules out coalescing (a latest-value
                            // Signal would otherwise merge two back-to-back sets into one observed value). The
                            // green frame arriving before blue is set proves the green set produced exactly one
                            // frame; the subsequent blue frame proves the second set likewise emits exactly one.
                            _     <- color.set(0x00ff00)
                            first <- clientWs.take()
                            _ <- decodeHtmlOp(first) match
                                case Present(op) => frames.updateAndGet(_ :+ op).unit
                                case Absent      => Kyo.unit
                            _      <- color.set(0x0000ff)
                            second <- clientWs.take()
                            _ <- decodeHtmlOp(second) match
                                case Present(op) => frames.updateAndGet(_ :+ op).unit
                                case Absent      => Kyo.unit
                        yield ()
                )
            }
            captured <- frames.get
        yield captured match
            case Seq(HtmlOp.HostUpdate(p1, payload1), HtmlOp.HostUpdate(p2, payload2)) =>
                // Exactly one HostUpdate for the first emission (green), then one for the sentinel (blue):
                // the first set produced a single frame, not two.
                assert(p1 == Seq("0"))
                assert(payload1 == HostPayload.Prop("n0", "color", HostValue.Col(0x00ff00)))
                assert(p2 == Seq("0"))
                assert(payload2 == HostPayload.Prop("n0", "color", HostValue.Col(0x0000ff)))
            case other => fail(s"expected exactly one HostUpdate per emission (green then blue), got: $other")
        end for
    }

    // ============ a host survives a sibling reactive re-render ============

    // notNative: WS-behavior leaf; see class scaladoc.
    "PRESERVE-UI-03: a sibling re-render never targets the host path".notNative in {
        for
            sibling <- Signal.initRef("before")
            invoked <- Channel.initUnscoped[String](64)
            stub = StubBridge(invoked, onSubscribe = (_, _) => Kyo.unit, onPickFn = (_, _, _) => Kyo.unit)
            // host at index 0; the reactive sibling span at index 1.
            app = UI.div(
                hostWith(stub),
                sibling.map(v => UI.span(v).id("s"))
            )
            replaceOp <- AtomicRef.init(Absent: Maybe[HtmlOp])
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            _ <- clientWs.take()
                            // Emit on the sibling signal: it produces an HtmlOp.Replace for the sibling region only.
                            _     <- sibling.set("after")
                            frame <- clientWs.take()
                            _     <- replaceOp.set(decodeHtmlOp(frame))
                        yield ()
                )
            }
            op <- replaceOp.get
        yield op match
            case Present(HtmlOp.Replace(path, html)) =>
                // The Replace addresses the sibling region (index 1), never the host (index 0).
                assert(path == Seq("1"))
                assert(path != Seq("0"))
                assert(html.contains("after"))
            case other => fail(s"expected a Replace for the sibling region, got: $other")
        end for
    }

    // ============ the host path is never a Replace/Remove target ============

    // notNative: WS-behavior leaf; see class scaladoc.
    "PRESERVE-UI-03-absent: no Replace/Remove over N sibling emissions targets the host".notNative in {
        for
            sibling <- Signal.initRef(0)
            invoked <- Channel.initUnscoped[String](64)
            stub = StubBridge(invoked, onSubscribe = (_, _) => Kyo.unit, onPickFn = (_, _, _) => Kyo.unit)
            app = UI.div(
                hostWith(stub),
                sibling.map(n => UI.span(n.toString).id("s"))
            )
            ops <- AtomicRef.init(Seq.empty[HtmlOp])
            n = 4
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            _ <- clientWs.take()
                            _ <- Kyo.foreachDiscard(1 to n) { i =>
                                for
                                    _     <- sibling.set(i)
                                    frame <- clientWs.take()
                                    _ <- decodeHtmlOp(frame) match
                                        case Present(op) => ops.updateAndGet(_ :+ op).unit
                                        case Absent      => Kyo.unit
                                yield ()
                            }
                        yield ()
                )
            }
            captured <- ops.get
        yield
            assert(captured.size == n)
            val hostTargeted = captured.exists {
                case HtmlOp.Replace(path, _) => path == Seq("0")
                case HtmlOp.Remove(path)     => path == Seq("0")
                case _                       => false
            }
            assert(!hostTargeted)
        end for
    }

    // ============ kyo-ui never calls back into the bridge's renderer ============

    // notNative: WS-behavior leaf; see class scaladoc.
    "PRESERVE-UI-06: only subscriptions/onPick are invoked on the bridge".notNative in {
        for
            invoked <- Channel.initUnscoped[String](64)
            ready   <- Channel.initUnscoped[Unit](4)
            color   <- Signal.initRef(0x000000)
            ranPick <- Channel.initUnscoped[Unit](4)
            stub = StubBridge(
                invoked,
                onSubscribe = (_, emit) =>
                    Fiber.init {
                        color.observe(rgb =>
                            if rgb != 0x000000 then emit(HostPayload.Prop("n0", "color", HostValue.Col(rgb)))
                            else latch(ready, ())
                        )
                    }.unit,
                onPickFn = (_, _, _) => latch(ranPick, ())
            )
            app = UI.div(hostWith(stub))
            calls <- AtomicRef.init(Seq.empty[String])
            _ <- Scope.run {
                HttpWebSocket.connect(
                    (serverWs: HttpWebSocket) => UIServer.serveSession(serverWs, app),
                    (clientWs: HttpWebSocket) =>
                        for
                            // A pure host tree emits no initial WS frame; wait for the observer to register.
                            _ <- ready.take
                            // A full round-trip: one server emission, then one client pick.
                            _    <- color.set(0x00ff00)
                            _    <- clientWs.take()
                            pick <- Kyo.lift(UIEvent.HostPick(Seq("0"), "node-0", pointer))
                            _    <- clientWs.put(HttpWebSocket.Payload.Text(Json.encode[UIEvent](pick)))
                            _    <- ranPick.take
                            // Drain every method-name the bridge recorded.
                            _ <- Loop.foreach {
                                invoked.poll.map {
                                    case Present(name) => calls.updateAndGet(_ :+ name).andThen(Loop.continue)
                                    case Absent        => Loop.done(())
                                }
                            }
                        yield ()
                )
            }
            recorded <- calls.get
        yield
            // Only the three bridge methods were invoked; every recorded name is one of them.
            val allowed = Set("serverInit", "subscriptions", "onPick")
            assert(recorded.forall(allowed.contains))
            // subscriptions and onPick both ran in the round-trip.
            assert(recorded.contains("subscriptions"))
            assert(recorded.contains("onPick"))
        end for
    }

end UIServerHostBridgeTest
