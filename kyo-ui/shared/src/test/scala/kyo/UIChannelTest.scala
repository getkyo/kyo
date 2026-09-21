package kyo

import kyo.internal.HtmlOp
import kyo.internal.MouseEventData
import kyo.internal.UIChannel
import kyo.internal.UIEvent
import kyo.internal.UIServer

/** The session's transport is a parameter, and a transport that is not a WebSocket serves the same session.
  *
  * The leaves below run the real [[UIServer.serveSession]] over a pair of channels, with no socket, no HTTP server and
  * no browser: what they assert is that the frames a session opens with, the event round trip and the end are the
  * channel's to carry and the session's to decide. An MCP Apps view driven by tool calls is the same session over a
  * different [[UIChannel]], which is what makes that mount possible at all.
  */
class UIChannelTest extends kyo.test.Test[Any]:

    /** A channel whose frames go into `outbound` and come from `inbound`, ending when `gone` completes. */
    private def pair(
        outbound: Channel[String],
        inbound: Channel[String],
        gone: Fiber.Promise[Unit, Any]
    ): UIChannel =
        new UIChannel:
            def send(frame: String)(using Frame): Unit < (Async & Abort[Closed]) = outbound.put(frame)
            def received(using Frame): Stream[String, Async]                     = inbound.streamUntilClosed()
            def awaitClose(using Frame): Unit < Async                            = gone.get.unit

    private def opOf(frame: String)(using kyo.test.AssertScope, Frame): HtmlOp =
        Json.decode[HtmlOp](frame) match
            case Result.Success(op)    => op
            case Result.Failure(error) => fail(s"not an HtmlOp: $error")
            case Result.Panic(error)   => throw error

    "a session over a channel that is not a WebSocket announces itself, renders, and answers an event" in {
        for
            ref      <- Signal.initRef("before")
            outbound <- Channel.initUnscoped[String](32)
            inbound  <- Channel.initUnscoped[String](32)
            gone     <- Fiber.Promise.init[Unit, Any]
            app = UI.div(
                UI.button("Click").id("btn").onClick(ref.set("after")),
                ref.map(v => UI.span(v).id("val"))
            )
            session <- Fiber.initUnscoped(Abort.run[Closed](UIServer.serveSession(pair(outbound, inbound, gone), app)))
            ready   <- outbound.take
            initial <- outbound.take
            _       <- inbound.put(Json.encode[UIEvent](UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))))
            update  <- outbound.take
            _       <- gone.completeDiscard(Result.succeed(()))
            _       <- session.get
        yield
            assert(opOf(ready) == HtmlOp.SessionReady())
            assert(opOf(initial).isInstanceOf[HtmlOp.ReplaceRange] || opOf(initial).isInstanceOf[HtmlOp.Replace], initial)
            opOf(update) match
                case HtmlOp.ReplaceRange(_, html) => assert(html.contains("after"), html)
                case other                        => fail(s"expected the click's ReplaceRange, got $other")
        end for
    }

    // A view that arrived as a generic shell has nothing rendered, so the session sends it the document before anything
    // addressed against it. A page served by UI.runHandlers already has it and is sent no such frame.
    "a session told to mount sends the rendered document, and one that is not does not" in {
        for
            ref     <- Signal.initRef("before")
            mounted <- Channel.initUnscoped[String](32)
            plain   <- Channel.initUnscoped[String](32)
            inbound <- Channel.initUnscoped[String](32)
            goneOne <- Fiber.Promise.init[Unit, Any]
            goneTwo <- Fiber.Promise.init[Unit, Any]
            app = UI.div(UI.button("Click").id("btn").onClick(ref.set("after")), ref.map(v => UI.span(v).id("val")))
            first     <- Fiber.initUnscoped(Abort.run[Closed](UIServer.serveSession(pair(mounted, inbound, goneOne), app, mount = true)))
            second    <- Fiber.initUnscoped(Abort.run[Closed](UIServer.serveSession(pair(plain, inbound, goneTwo), app)))
            _         <- mounted.take
            mountOp   <- mounted.take
            _         <- plain.take
            nextPlain <- plain.take
            _         <- goneOne.completeDiscard(Result.succeed(()))
            _         <- goneTwo.completeDiscard(Result.succeed(()))
            _         <- first.get
            _         <- second.get
        yield
            opOf(mountOp) match
                case HtmlOp.Mount(html, _) =>
                    assert(html.contains("before"), html)
                    assert(html.contains("""id="btn""""), html)
                case other => fail(s"expected the mount, got $other")
            end match
            assert(!opOf(nextPlain).isInstanceOf[HtmlOp.Mount], nextPlain)
        end for
    }

    "a frame the session cannot decode is dropped, and the session keeps serving" in {
        for
            ref      <- Signal.initRef("before")
            outbound <- Channel.initUnscoped[String](32)
            inbound  <- Channel.initUnscoped[String](32)
            gone     <- Fiber.Promise.init[Unit, Any]
            app = UI.div(
                UI.button("Click").id("btn").onClick(ref.set("after")),
                ref.map(v => UI.span(v).id("val"))
            )
            session <- Fiber.initUnscoped(Abort.run[Closed](UIServer.serveSession(pair(outbound, inbound, gone), app)))
            _       <- outbound.take
            _       <- outbound.take
            // Neither a UIEvent nor a DragProtocol.ClientMessage: a buggy peer must not end the session.
            _      <- inbound.put("""{"NoSuchEvent":{"path":[]}}""")
            _      <- inbound.put(Json.encode[UIEvent](UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))))
            update <- outbound.take
            _      <- gone.completeDiscard(Result.succeed(()))
            _      <- session.get
        yield opOf(update) match
            case HtmlOp.ReplaceRange(_, html) => assert(html.contains("after"), html)
            case other                        => fail(s"expected the click's ReplaceRange, got $other")
        end for
    }

    "the peer going ends the session, even with frames still unread" in {
        for
            outbound <- Channel.initUnscoped[String](32)
            inbound  <- Channel.initUnscoped[String](32)
            gone     <- Fiber.Promise.init[Unit, Any]
            ended    <- AtomicBoolean.init(false)
            app = UI.div(UI.span("static"))
            session <- Fiber.initUnscoped(
                Sync.ensure(ended.set(true))(Abort.run[Closed](UIServer.serveSession(pair(outbound, inbound, gone), app)))
            )
            _     <- outbound.take
            _     <- gone.completeDiscard(Result.succeed(()))
            _     <- session.get
            after <- ended.get
        yield assert(after)
    }

    "the WebSocket view carries text and drops what is not text" in {
        for
            received <- Scope.run {
                for
                    frames <- AtomicRef.init(Chunk.empty[String])
                    _      <- HttpWebSocket.connect(
                        (serverWs: HttpWebSocket) =>
                            UIChannel.webSocket(serverWs).received.foreach(frame => frames.updateAndGet(_.append(frame)).unit),
                        (clientWs: HttpWebSocket) =>
                            for
                                _ <- clientWs.put(HttpWebSocket.Payload.Binary(Span("ignored".getBytes("UTF-8")*)))
                                _ <- clientWs.put(HttpWebSocket.Payload.Text("kept"))
                                _ <- assertEventually(frames.get.map(_ == Chunk("kept")))
                                _ <- clientWs.close()
                            yield ()
                    )
                    seen <- frames.get
                yield seen
            }
        yield assert(received == Chunk("kept"))
    }

end UIChannelTest
