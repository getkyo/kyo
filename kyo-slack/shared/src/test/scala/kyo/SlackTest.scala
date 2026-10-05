package kyo

import kyo.SlackLiterals.*
import kyo.internal.slack.Reconnect
import kyo.internal.slack.SocketEngineTest
import kyo.internal.slack.Transport
import kyo.internal.slack.TransportTest

/** Cross-platform tests for the public `Slack` client: the client's token and base url on
  * its Web API calls, and the manually-managed connection
  * surface. `receive` drives the real receive loop over an in-memory transport conduit (real
  * frames, real acks), and `close` is total and idempotent (the row carries no `Abort`).
  * Teardown is observed via a latch released by the conduit `close`, never a sleep. The full
  * `Slack.init` / `Slack.receive` handshake-to-socket path runs against local kyo-http servers
  * playing `apps.connections.open` and the Socket Mode WebSocket.
  */
class SlackTest extends kyo.test.Test[Any]:

    private val cfg        = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))
    private val url        = "wss://test/socket"
    private val helloFrame =
        """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private val eventFrame =
        """{"type":"events_api","envelope_id":"E1","payload":{"type":"event_callback","event_id":"EvE1","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
    // The envelopes helloFrame and eventFrame decode to.
    private val helloEnvelope: SlackEnvelope[?] = SlackEnvelope.Hello(1, SlackEnvelope.Hello.ConnectionInfo(SlackId.AppId("A1")))
    private val eventEnvelope: SlackEnvelope[?] = SlackEnvelope.EventsApi(
        SlackId.EnvelopeId("E1"),
        SlackEnvelope.EventsApi.Payload(
            SlackId.EventId("EvE1"),
            SlackEvent.Message(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "hi", SlackTs("1.2"))
        )
    )

    private def eventFrameWithId(id: String) =
        s"""{"type":"events_api","envelope_id":"$id","payload":{"type":"event_callback","event_id":"Ev$id","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""

    /** `config` with its Web API at `apiBase`, a local server playing Slack. */
    private def at(apiBase: String, config: SlackConfig = cfg)(using Frame): SlackConfig =
        valid(config.baseUrl(urlOf(apiBase)))

    /** `Slack.init` over the transport that reaches a local Socket Mode server. */
    private def initLocal(config: SlackConfig)(using Frame): Slack < (Async & Abort[SlackInitFailure] & Scope) =
        Scope.acquireRelease(Slack.initUnscopedOver(TransportTest.plainLocal(_, config))(config))(Slack.close)

    import SlackLiveTest.answer

    /** A handler that runs `f` on each envelope, then gives it the bare answer. */
    private def eachThen[E](f: SlackEnvelope[?] => Unit < (Async & Abort[E] & Env[Slack])): [A] => SlackEnvelope[A] => A < (
        Async & Abort[E] & Env[Slack]
    ) =
        [A] => (e: SlackEnvelope[A]) => f(e).andThen(answer[A](e))

    private val ackAll: [A] => SlackEnvelope[A] => A < Async = [A] => (e: SlackEnvelope[A]) => answer[A](e)

    /** The engine active now on a client built with a connection. */
    private def engineOf(client: Slack)(using Frame): kyo.internal.slack.SocketEngine < Sync =
        client.connection match
            case Present(controller) => controller.active.get
            case Absent              => throw new IllegalStateException("the client was built without a connection")

    "a Web API call from inside a receive handler sends the client's bot token to the config's base url" in {
        for
            authSeen <- AtomicRef.init(Maybe.empty[String])
            botSeen  <- AtomicRef.init(Maybe.empty[String])
            api      <- HttpServer.init(0, "127.0.0.1")(
                HttpRoute.postRaw("apps.connections.open").response(_.bodyText).handler { req =>
                    authSeen.set(req.headers.get("Authorization"))
                        .andThen(HttpResponse(HttpStatus.OK).addField("body", s"""{"ok":true,"url":"$url"}"""))
                },
                HttpRoute.postRaw("auth.test").response(_.bodyText).handler { req =>
                    botSeen.set(req.headers.get("Authorization")).andThen(HttpResponse(HttpStatus.OK).addField(
                        "body",
                        """{"ok":true,"user_id":"U1","team_id":"T1","bot_id":"B1","url":"https://example.slack.com/"}"""
                    ))
                }
            )
            conduit    <- newConduit(Seq(helloFrame, eventFrame))
            conn       <- Slack.initUnscopedOver(_ => conduit)(at(s"http://127.0.0.1:${api.port}"))
            apiOutcome <- Channel.init[Result[SlackException, Slack.Identity]](4)
            handler = eachThen {
                case _: SlackEnvelope.EventsApi =>
                    Abort.run[SlackException](Slack.identity).map(r => Abort.run[Closed](apiOutcome.put(r)).unit)
                case _ => Kyo.unit
            }
            loop    <- Fiber.initUnscoped(Abort.run[SlackException](Slack.run(conn)(Slack.receive(handler))))
            outcome <- apiOutcome.take
            _       <- Slack.close(conn)
            ended   <- loop.get
            app     <- authSeen.get
            bot     <- botSeen.get
        yield
            assert(outcome == Result.succeed(Slack.Identity(
                SlackId.UserId("U1"),
                SlackId.TeamId("T1"),
                SlackId.BotId("B1"),
                urlOf("https://example.slack.com/")
            )))
            assert((app, bot) == (Present("Bearer xapp-1"), Present("Bearer xoxb-1")), s"got: ${(app, bot)}")
            assert(ended == Result.succeed(()), s"close ends the loop, got: $ended")
        end for
    }

    /** A conduit that streams `scripted` (then stays open until close), records acks,
      * and releases `closed` on `conn.close` so teardown is observable.
      */
    final private class Conduit(
        scripted: Seq[String],
        val recorded: Channel[String],
        val closed: Latch,
        feed: Channel[String]
    ) extends Transport:
        private[kyo] def connect[A, S](u: HttpUrl, c: HttpWebSocket.Config)(
            f: Transport.Conn => A < (S & Async)
        )(using Frame): A < (S & Async & Abort[SlackTransportException]) =
            Kyo.foreach(scripted)(fr => Abort.run[Closed](feed.put(fr))).andThen {
                val conn = new Transport.Conn:
                    private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) = recorded.put(text)
                    private[kyo] def stream(using Frame): Stream[String, Async]                     = feed.streamUntilClosed()
                    private[kyo] def close(using Frame): Unit < Async                               =
                        // Close only the inbound feed (stopping the receiver). Leave the
                        // recorded ack sink OPEN so a test can drain pre-teardown acks
                        // without racing a Closed on the sink.
                        closed.release.andThen(feed.close.unit)
                    private[kyo] def onPeerClose(using Frame): Unit < Async = closed.await
                f(conn)
            }
    end Conduit

    private def newConduit(scripted: Seq[String])(using Frame): Conduit < Sync =
        for
            recorded <- Channel.initUnscoped[String](64)
            closed   <- Latch.init(1)
            feed     <- Channel.initUnscoped[String](64)
        yield Conduit(scripted, recorded, closed, feed)

    /** A client whose Socket Mode connection is a conduit streaming `scripted`, opened through a local `apps.connections.open`. */
    private def connection(scripted: Seq[String], config: SlackConfig = cfg)(using
        Frame
    ): (Slack, Conduit) < (Async & Scope & Abort[SlackException | HttpException]) =
        for
            authSeen <- AtomicRef.init(Maybe.empty[String])
            apiBase  <- connectionsOpenServer(url, authSeen)
            conduit  <- newConduit(scripted)
            slack    <- Slack.initUnscopedOver(_ => conduit)(at(apiBase, config))
        yield (slack, conduit)

    "receive drives the real loop: an event is delivered and acked with its envelope_id" in {
        for
            pair <- connection(Seq(helloFrame, eventFrameWithId("E1")))
            (conn, conduit) = pair
            delivered <- Channel.init[String](8)
            handler = eachThen {
                case e: SlackEnvelope.EventsApi => Abort.run[Closed](delivered.put(e.envelopeId.value)).unit
                case _                          => Kyo.unit
            }
            loop <- Fiber.initUnscoped(Abort.run[SlackException](Slack.run(conn)(Slack.receive(handler))))
            ids  <- delivered.stream().take(1).run
            acks <- conduit.recorded.stream().take(1).run
            _    <- Slack.close(conn)
            _    <- loop.interrupt
        yield
            assert(ids == Chunk("E1"), s"E1 delivered, got: $ids")
            assert(acks == Chunk("""{"envelope_id":"E1"}"""), s"E1 acked, got: $acks")
        end for
    }

    "close is total and idempotent: closing twice tears the loop down once and the second close is a no-op" in {
        for
            pair <- connection(Seq(helloFrame))
            (conn, conduit) = pair
            loop <- Fiber.initUnscoped(Abort.run[SlackException](Slack.run(conn)(Slack.receive(ackAll))))
            // First close releases the teardown latch; the close row is Unit < Async (no Abort),
            // so the test compiles only because close never aborts.
            _ <- Slack.close(conn)
            _ <- conduit.closed.await
            // Observable post-state: the close propagated to the receive loop. The engine's
            // inbound is now closed, so the loop's next take aborts Closed and it ends cleanly
            // (Reaction.Stop -> Unit), without an interrupt.
            loopResult <- loop.get
            // A second close is a no-op on the already-closed engine and surfaces no error.
            _ <- Slack.close(conn)
            // Second observable post-state: a receive started AFTER close stops immediately,
            // because the engine is closed (its inbound is closed), proving "behaves as closed".
            postCloseRun <- Abort.run[SlackException](Slack.run(conn)(Slack.receive(ackAll)))
        yield
            assert(loopResult == Result.Success(()), s"close tears the loop down cleanly; got: $loopResult")
            assert(postCloseRun == Result.succeed(()), "a receive after close returns immediately on the closed engine")
        end for
    }

    "receive on a client Slack.run(config) built opens a connection for the loop and closes it when the loop ends" in {
        for
            pair <- localSlack(_ => Chunk(helloFrame, eventFrameWithId("E1"), """{"type":"disconnect","reason":"warning"}"""))
            (apiBase, observed) = pair
            delivered <- Channel.init[SlackEnvelope[?]](8)
            offConfig = at(apiBase, cfg.reconnect(SlackConfig.Reconnect.Off))
            ended <- Abort.run[SlackException](Slack.runOver(TransportTest.plainLocal(_, offConfig))(offConfig) {
                Slack.receive(eachThen(env => Abort.run[Closed](delivered.put(env)).unit))
            })
            closed <- observed.closed.take
            envs   <- delivered.drain
            acks   <- drained(observed.acked)
            opened <- observed.opened.get
        yield
            assert(ended == Result.succeed(()), s"a routine disconnect under Off ends the loop, got: $ended")
            assert(envs.take(2) == Chunk(helloEnvelope, eventEnvelope), s"got: $envs")
            assert(acks == Chunk("""{"envelope_id":"E1"}"""), s"got: $acks")
            assert((opened, closed) == (1, 1), s"one connection opened and closed, got: ${(opened, closed)}")
        end for
    }

    "Slack.run(config) closes its client's HttpClient when its region ends" in {
        Slack.run(cfg)(Env.get[Slack]).map { client =>
            // Unsafe: whether a pool is closed has no safe accessor; this reads the flag once, after the region ended.
            import AllowUnsafe.embrace.danger
            assert(client.http.isPoolClosed)
        }
    }

    // Local servers playing Slack: a WebSocket endpoint for the Socket Mode stream and an
    // apps.connections.open route that returns its url.

    private def socketModeServer(wsHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]))(using
        Frame
    ): String < (Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "127.0.0.1")(HttpHandler.webSocket("ws/slack")(wsHandler)).map { server =>
            s"wss://127.0.0.1:${server.port}/ws/slack"
        }

    private def connectionsOpenServer(wssUrl: String, authSeen: AtomicRef[Maybe[String]])(using
        Frame
    ): String < (Async & Scope & Abort[HttpException]) =
        val openRoute = HttpRoute.postRaw("apps.connections.open").response(_.bodyText).handler { req =>
            authSeen.set(req.headers.get("Authorization")).andThen(
                HttpResponse(HttpStatus.OK).addField("body", s"""{"ok":true,"url":"$wssUrl"}""")
            )
        }
        HttpServer.init(0, "127.0.0.1")(openRoute).map(server => s"http://127.0.0.1:${server.port}")
    end connectionsOpenServer

    "init then receive over local servers delivers the event and acks over the real socket" in {
        // End-to-end manual path: apps.connections.open returns a wss url, the live
        // transport connects, receive drives the loop, and the engine acks E1 back over
        // the socket (captured by the server).
        for
            acked    <- Channel.init[String](8)
            authSeen <- AtomicRef.init(Maybe.empty[String])
            wssUrl   <- socketModeServer { (_, ws) =>
                ws.put(HttpWebSocket.Payload.Text(helloFrame))
                    .andThen(ws.put(HttpWebSocket.Payload.Text(eventFrame)))
                    .andThen {
                        ws.stream.foreach {
                            case HttpWebSocket.Payload.Text(s) => Abort.run[Closed](acked.put(s)).unit
                            case _                             => Kyo.unit
                        }
                    }
            }
            apiBase   <- connectionsOpenServer(wssUrl, authSeen)
            delivered <- Channel.init[SlackEnvelope[?]](8)
            handler = eachThen(env => Abort.run[Closed](delivered.put(env)).unit)
            conn <- initLocal(at(apiBase))
            loop <- Fiber.initUnscoped(Abort.run[SlackException](Slack.run(conn)(Slack.receive(handler))))
            envs <- delivered.stream().take(2).run
            acks <- acked.stream().take(1).run
            _    <- Slack.close(conn)
            _    <- loop.interrupt
            auth <- authSeen.get
        yield
            assert(envs == Chunk(helloEnvelope, eventEnvelope), s"hello then the event, got: $envs")
            assert(acks == Chunk("""{"envelope_id":"E1"}"""), s"real socket ack, got: $acks")
            assert(auth == Present("Bearer xapp-1"), s"connections.open signed with the app-level token, got: $auth")
        end for
    }

    "receive under run(config) over local servers holds its region: ending it tears the socket down" in {
        // Slack.run(config) brackets its client in its own Scope, and receive brackets the connection it opens: ending the run
        // closes the active engine.
        // The server observes the client socket close (its ws.stream ends) and releases
        // the teardown latch, so teardown is an OBSERVED event, not a log line.
        for
            serverSawClose <- Latch.init(1)
            delivered      <- Channel.init[SlackEnvelope[?]](8)
            authSeen       <- AtomicRef.init(Maybe.empty[String])
            wssUrl         <- socketModeServer { (_, ws) =>
                ws.put(HttpWebSocket.Payload.Text(helloFrame))
                    .andThen(ws.put(HttpWebSocket.Payload.Text(eventFrame)))
                    .andThen(ws.stream.foreach(_ => Kyo.unit))
                    .andThen(serverSawClose.release)
            }
            apiBase <- connectionsOpenServer(wssUrl, authSeen)
            handler = eachThen(env => Abort.run[Closed](delivered.put(env)).unit)
            // Run in a fiber; once the event is delivered, interrupt it so the run's region ends
            // and its finalizer closes the socket.
            connFiber <-
                Fiber.initUnscoped(Abort.run[SlackException](Slack.runOver(TransportTest.plainLocal(
                    _,
                    at(apiBase)
                ))(at(apiBase))(Slack.receive(handler))))
            envs <- delivered.stream().take(2).run
            _    <- connFiber.interrupt
            _    <- serverSawClose.await
        yield assert(envs == Chunk(helloEnvelope, eventEnvelope), s"hello then the event before teardown, got: $envs")
        end for
    }

    "init opens via apps.connections.open over HTTP, then connects the wss url it returned" in {
        // The openEngine HTTP path end to end: apps.connections.open returns a wss url (a
        // 2xx body, the case kyo-http leaves rawBody Absent for), and init then connects the
        // live transport and reaches readiness on the hello.
        for
            connected <- Latch.init(1)
            authSeen  <- AtomicRef.init(Maybe.empty[String])
            wssUrl    <- socketModeServer { (_, ws) =>
                connected.release
                    .andThen(ws.put(HttpWebSocket.Payload.Text(helloFrame)))
                    .andThen(ws.stream.foreach(_ => Kyo.unit))
            }
            apiBase <- connectionsOpenServer(wssUrl, authSeen)
            conn    <- initLocal(at(apiBase))
            _       <- connected.await
            _       <- Slack.close(conn)
            auth    <- authSeen.get
        yield assert(auth == Present("Bearer xapp-1"), s"connections.open signed with the app-level token, got: $auth")
        end for
    }

    // --- Handler outcomes and lifecycle over local servers ---

    private case class Boom(id: String) derives CanEqual

    /** What a local Socket Mode endpoint observed (the client frames, each closed connection's number,
      * how many opened), and `feed`, whose frames it pushes to the open connection after its first ones.
      */
    final private class Observed(val acked: Channel[String], val closed: Channel[Int], val opened: AtomicInt, val feed: Channel[String])

    /** A local Slack: `apps.connections.open` answers the Socket Mode url, and the n-th connection is
      * pushed `framesFor(n)` and then each frame put on `feed`, has each client frame recorded in
      * `acked`, and puts `n` on `closed` once the client socket closes.
      */
    private def localSlack(framesFor: Int => Chunk[String])(using Frame): (String, Observed) < (Async & Scope & Abort[HttpException]) =
        for
            acked    <- Channel.init[String](64)
            closed   <- Channel.init[Int](16)
            opened   <- AtomicInt.init(0)
            feed     <- Channel.init[String](64)
            authSeen <- AtomicRef.init(Maybe.empty[String])
            wssUrl   <- socketModeServer { (_, ws) =>
                opened.incrementAndGet.map { n =>
                    val push = Kyo.foreachDiscard(framesFor(n))(f => ws.put(HttpWebSocket.Payload.Text(f)))
                        .andThen(feed.streamUntilClosed().foreach(f => ws.put(HttpWebSocket.Payload.Text(f))))
                    val read = ws.stream.foreach {
                        case HttpWebSocket.Payload.Text(s) => acked.put(s)
                        case _                             => Kyo.unit
                    }
                    Async.race(Abort.run[Closed](push).unit, Abort.run[Closed](read).unit).andThen(closed.put(n))
                }
            }
            apiBase <- connectionsOpenServer(wssUrl, authSeen)
        yield (apiBase, Observed(acked, closed, opened, feed))

    private def whenTornDown[A](tornDown: Boolean)(v: A < (Async & Abort[Closed]))(using Frame): Maybe[A] < (Async & Abort[Closed]) =
        if tornDown then v.map(Present(_)) else Absent

    private def drained(ch: Channel[String])(using Frame): Chunk[String] < Async =
        Abort.run[Closed](ch.drain).map(_.getOrElse(Chunk.empty))

    "a handler's typed failure ends run with it, leaves its envelope unacknowledged, and the socket closes as the scope ends" in {
        for
            pair <- localSlack(_ => Chunk(helloFrame, eventFrameWithId("E0")))
            (apiBase, observed) = pair
            handler             = eachThen {
                case e: SlackEnvelope.EventsApi if e.envelopeId.value == "A" => Abort.fail(Boom("A"))
                case _                                                       => Kyo.unit
            }
            fiber <- Fiber.initUnscoped(Abort.run[Boom | SlackException](Slack.runOver(TransportTest.plainLocal(
                _,
                at(apiBase)
            ))(at(apiBase))(Slack.receive(handler))))
            first  <- observed.acked.take
            _      <- observed.feed.put(eventFrameWithId("A"))
            result <- fiber.get
            closed <- observed.closed.take
            rest   <- drained(observed.acked)
        yield
            assert(result == Result.fail(Boom("A")), s"got: $result")
            assert(closed == 1, s"the one connection closed, got: $closed")
            assert(first +: rest == Chunk("""{"envelope_id":"E0"}"""), s"E0 acked and A not, got: ${first +: rest}")
        end for
    }

    "receive interrupted while a handler is parked: no ack, the server sees the close, no panic logged" in {
        for
            pair <- localSlack(_ => Chunk(helloFrame, eventFrameWithId("E1")))
            (apiBase, observed) = pair
            sink    <- SocketEngineTest.LogSink.init
            entered <- Latch.init(1)
            parked  <- Latch.init(1)
            handler = eachThen {
                case _: SlackEnvelope.EventsApi => entered.release.andThen(parked.await)
                case _                          => Kyo.unit
            }
            fiber <- Log.let(Log(sink)) {
                Fiber.initUnscoped(Abort.run[SlackException](Slack.runOver(TransportTest.plainLocal(
                    _,
                    at(apiBase)
                ))(at(apiBase))(Slack.receive(handler))))
            }
            _      <- entered.await
            _      <- fiber.interrupt
            result <- fiber.getResult
            closed <- observed.closed.take
            acks   <- drained(observed.acked)
            lines  <- sink.engineLines
        yield
            assert(result.panic.map(_.getClass.getSimpleName) == Present("Interrupted"), s"got: $result")
            assert(closed == 1, s"got: $closed")
            assert(acks == Chunk.empty[String], s"no ack, got: $acks")
            assert(lines == Chunk.empty[SocketEngineTest.Line], s"nothing logged, got: $lines")
        end for
    }

    "init's connection closes when its scope ends, and receive on it then returns at once" in {
        for
            pair <- localSlack(_ => Chunk(helloFrame))
            (apiBase, observed) = pair
            conn <- Scope.run(initLocal(at(apiBase)))
            // The scope's end is the only teardown here, so a live socket afterwards means init registered none.
            tornDown <- engineOf(conn).map(_.intentionalClose.get)
            closed   <- whenTornDown(tornDown)(observed.closed.take)
            after    <- whenTornDown(tornDown)(Abort.run[SlackException](Slack.run(conn)(Slack.receive(ackAll))))
        yield
            assert(tornDown, "the scope's end closed the connection")
            assert(closed == Present(1), s"the server saw the close, got: $closed")
            assert(after == Present(Result.succeed(())), s"receive on the closed connection returns at once, got: $after")
        end for
    }

    "initUnscoped's connection outlives a scope and closes on close, which the server observes" in {
        for
            pair <- localSlack(_ => Chunk(helloFrame))
            (apiBase, observed) = pair
            conn       <- Scope.run(Slack.initUnscopedOver(TransportTest.plainLocal(_, at(apiBase)))(at(apiBase)))
            afterScope <- engineOf(conn).map(_.intentionalClose.get)
            _          <- Slack.close(conn)
            closed     <- observed.closed.take
            afterClose <- engineOf(conn).map(_.intentionalClose.get)
            runDone    <- Abort.run[SlackException](Slack.run(conn)(Slack.receive(ackAll)))
        yield
            assert(!afterScope, "a scope's end leaves an unscoped connection open")
            assert(afterClose, "close closes it")
            assert(closed == 1, s"the server saw the close, got: $closed")
            assert(runDone == Result.succeed(()), s"receive on the closed connection returns at once, got: $runDone")
        end for
    }

    "after a rotation and a handler failure, a second receive on an init client reads the rotated connection, and close closes it" in {
        // Connection 1 ends with a routine disconnect, so the first receive rotates to connection 2 under Overlap.
        val rotating = Chunk(helloFrame, """{"type":"disconnect","reason":"warning"}""")
        val handler  = eachThen {
            case e: SlackEnvelope.EventsApi => Abort.fail(Boom(e.envelopeId.value))
            case _                          => Kyo.unit
        }
        for
            pair <- localSlack(n => if n == 1 then rotating else Chunk(helloFrame, eventFrameWithId("A")))
            (apiBase, observed) = pair
            conn   <- Slack.initUnscopedOver(TransportTest.plainLocal(_, at(apiBase)))(at(apiBase))
            first  <- Abort.run[Boom | SlackException](Slack.run(conn)(Slack.receive(handler)))
            closed <- observed.closed.take
            second <- Fiber.initUnscoped(Abort.run[Boom | SlackException](Slack.run(conn)(Slack.receive(handler))))
            _      <- observed.feed.put(eventFrameWithId("B"))
            result <- second.get
            _      <- Slack.close(conn)
            last   <- observed.closed.take
            opened <- observed.opened.get
        yield
            assert(first == Result.fail(Boom("A")), s"got: $first")
            assert(closed == 1, s"the rotation closed connection 1, got: $closed")
            assert(result == Result.fail(Boom("B")), s"the second receive read connection 2, got: $result")
            assert(last == 2, s"close reached connection 2, got: $last")
            assert(opened == 2, s"got: $opened")
        end for
    }

    "receive keeps one finalizer for the active engine across rotations: every connection closes exactly once" in {
        // Connections 1 and 2 each end with a routine disconnect, so run rotates twice under Overlap.
        val rotating = Chunk(helloFrame, """{"type":"disconnect","reason":"warning"}""")
        for
            pair <- localSlack(n => if n < 3 then rotating else Chunk(helloFrame))
            (apiBase, observed) = pair
            hellos       <- Channel.init[Int](8)
            clientOpened <- AtomicInt.init(0)
            clientClose  <- AtomicRef.init(Chunk.empty[Int])
            config  = at(apiBase)
            counted = (http: HttpClient) =>
                new Transport:
                    private[kyo] def connect[A, S](u: HttpUrl, c: HttpWebSocket.Config)(
                        f: Transport.Conn => A < (S & Async)
                    )(using Frame): A < (S & Async & Abort[SlackTransportException]) =
                        clientOpened.incrementAndGet.map { n =>
                            TransportTest.plainLocal(http, config).connect(u, c) { conn =>
                                val counting = new Transport.Conn:
                                    private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) = conn.put(text)
                                    private[kyo] def stream(using Frame): Stream[String, Async]                     = conn.stream
                                    private[kyo] def close(using Frame): Unit < Async                               =
                                        clientClose.updateAndGet(_ :+ n).andThen(conn.close)
                                    private[kyo] def onPeerClose(using Frame): Unit < Async = conn.onPeerClose
                                f(counting)
                            }
                        }
            count <- AtomicInt.init(0)
            handler = eachThen {
                case _: SlackEnvelope.Hello => count.incrementAndGet.map(n => Abort.run[Closed](hellos.put(n)).unit)
                case _                      => Kyo.unit
            }
            fiber     <- Fiber.initUnscoped(Abort.run[SlackException](Slack.runOver(counted)(config)(Slack.receive(handler))))
            seen      <- hellos.stream().take(3).run
            _         <- fiber.interrupt
            _         <- fiber.getResult
            serverEnd <- observed.closed.stream().take(3).run
            clientEnd <- clientClose.get
            opened    <- observed.opened.get
        yield
            assert(seen == Chunk(1, 2, 3), s"three connections said hello, got: $seen")
            assert(opened == 3, s"got: $opened")
            assert(serverEnd.sorted == Chunk(1, 2, 3), s"the server saw each connection close, got: $serverEnd")
            assert(clientEnd.sorted == Chunk(1, 2, 3), s"each engine closed its socket exactly once, got: $clientEnd")
        end for
    }

    // --- The live suite's scenarios against a local Slack ---

    private case class Posted(channel: String, text: String) derives Schema, CanEqual
    private case class Deleted(channel: String, ts: String) derives Schema, CanEqual

    private val fakeBotUser = "U0BOT"

    /** What the local Slack recorded: the messages posted, in order, and the deletions. */
    final private class FakeSlack(val base: String, val posted: AtomicRef[Chunk[Posted]], val deleted: AtomicRef[Chunk[Deleted]])

    /** A local Slack for the live suite's calls: `auth.test`, `apps.connections.open`, a Socket Mode
      * endpoint that sends hello and then a message event for every post, `chat.postMessage` (which
      * answers `channel_not_found` for the channel Slack never assigns) and `chat.delete`.
      */
    private def fakeSlack(using Frame): FakeSlack < (Async & Scope & Abort[HttpException]) =
        for
            posted  <- AtomicRef.init(Chunk.empty[Posted])
            deleted <- AtomicRef.init(Chunk.empty[Deleted])
            events  <- Channel.init[String](64)
            socket = HttpHandler.webSocket("ws/slack") { (_, ws) =>
                val push = ws.put(HttpWebSocket.Payload.Text(helloFrame))
                    .andThen(events.streamUntilClosed().foreach(f => ws.put(HttpWebSocket.Payload.Text(f))))
                Async.race(Abort.run[Closed](push).unit, Abort.run[Closed](ws.stream.foreach(_ => Kyo.unit)).unit)
            }
            server <- HttpServer.init(0, "127.0.0.1")(socket)
            wss  = s"wss://127.0.0.1:${server.port}/ws/slack"
            json = (body: String) => HttpResponse(HttpStatus.OK).addField("body", body)
            api <- HttpServer.init(0, "127.0.0.1")(
                HttpRoute.postRaw("auth.test").response(_.bodyText).handler { _ =>
                    json(s"""{"ok":true,"user_id":"$fakeBotUser","team_id":"T1","bot_id":"B1","url":"https://example.slack.com/"}""")
                },
                HttpRoute.postRaw("apps.connections.open").response(_.bodyText).handler(_ => json(s"""{"ok":true,"url":"$wss"}""")),
                // A body the stand-in cannot read is answered as Slack answers bad arguments, so the
                // scenario fails on it rather than recording a value that was never sent.
                HttpRoute.postRaw("chat.postMessage").request(_.bodyText).response(_.bodyText).handler { req =>
                    Json.decode[Posted](req.fields.body) match
                        case Result.Success(message) =>
                            if message.channel == SlackLiveTest.MissingChannel.value then
                                json("""{"ok":false,"error":"channel_not_found"}""")
                            else
                                posted.updateAndGet(_ :+ message).map { all =>
                                    val ts    = s"1700000000.${100000 + all.size}"
                                    val frame =
                                        s"""{"type":"events_api","envelope_id":"E${all.size}","payload":{"type":"event_callback","event_id":"EvE${all.size}","event":{"type":"message","channel":"${message.channel}","user":"$fakeBotUser","text":"${message.text}","ts":"$ts"}}}"""
                                    Abort.run[Closed](
                                        events.put(frame)
                                    ).andThen(json(s"""{"ok":true,"channel":"${message.channel}","ts":"$ts"}"""))
                                }
                        case _ => json("""{"ok":false,"error":"invalid_arguments"}""")
                },
                HttpRoute.postRaw("chat.delete").request(_.bodyText).response(_.bodyText).handler { req =>
                    Json.decode[Deleted](req.fields.body) match
                        case Result.Success(request) => deleted.updateAndGet(_ :+ request).andThen(json("""{"ok":true}"""))
                        case _                       => json("""{"ok":false,"error":"invalid_arguments"}""")
                }
            )
        yield FakeSlack(s"http://127.0.0.1:${api.port}", posted, deleted)

    private val fakeChannel = SlackId.ChannelId("C0LIVE")

    private def credentialsAt(slack: FakeSlack)(using Frame) =
        SlackLiveTest.Credentials(at(slack.base), fakeChannel, TransportTest.plainLocal)

    "the live suite's connecting scenario, against a local Slack, answers hello first" in {
        for
            slack <- fakeSlack
            first <- SlackLiveTest.connectAndReceiveFirst(credentialsAt(slack))
        yield assert(first == helloEnvelope, s"got: $first")
    }

    "the live suite's posting scenario, against a local Slack, answers the timestamp and deletes the message" in {
        for
            slack   <- fakeSlack
            outcome <- SlackLiveTest.postAndDelete(credentialsAt(slack))
            posted  <- slack.posted.get
            deleted <- slack.deleted.get
        yield
            assert(outcome == (SlackTs("1700000000.100001"), Result.succeed(())), s"got: $outcome")
            assert(posted == Chunk(Posted(fakeChannel.value, "kyo-slack live suite: posting")), s"got: $posted")
            assert(deleted == Chunk(Deleted(fakeChannel.value, "1700000000.100001")), s"got: $deleted")
        end for
    }

    "the live suite's event scenario, against a local Slack, receives the bot's own post as that message event" in {
        for
            slack   <- fakeSlack
            outcome <- SlackLiveTest.receiveOwnPost(credentialsAt(slack), Absent)
            posted  <- slack.posted.get
            deleted <- slack.deleted.get
        yield
            val marker = posted.map(_.text)
            assert(marker.size == 1 && marker.head.startsWith("kyo-slack live suite: "), s"got: $posted")
            val expected = SlackEvent.Message(fakeChannel, SlackId.UserId(fakeBotUser), marker.head, SlackTs("1700000000.100001"))
            assert(outcome == SlackLiveTest.OwnPost(expected, Present(expected), Result.succeed(())), s"got: $outcome")
            assert(deleted == Chunk(Deleted(fakeChannel.value, "1700000000.100001")), s"got: $deleted")
        end for
    }

    "the live suite's error scenario, against a local Slack, answers the chat.postMessage leaf for a missing channel" in {
        for
            slack  <- fakeSlack
            result <- SlackLiveTest.postToMissingChannel(credentialsAt(slack))
        yield assert(result == Result.fail(SlackChannelNotFoundException("chat.postMessage", Chunk.empty)), s"got: $result")
    }

    "the live suite's long-text scenario, against a local Slack, posts the whole text and deletes it" in {
        for
            slack   <- fakeSlack
            outcome <- SlackLiveTest.postLongText(credentialsAt(slack))
            posted  <- slack.posted.get
            deleted <- slack.deleted.get
        yield
            assert(outcome == (Result.succeed(SlackTs("1700000000.100001")), Result.succeed(())), s"got: $outcome")
            assert(posted.map(_.text.length) == Chunk(SlackLiveTest.LongTextLength), s"got: ${posted.map(_.text.length)}")
            assert(deleted == Chunk(Deleted(fakeChannel.value, "1700000000.100001")), s"got: $deleted")
        end for
    }

    // No path renders a token. The secrets are built from parts so their full text appears
    // nowhere in this file's source, which a KyoException message can quote.

    private val appSecret = Seq("xapp", "1", "A0SLACKTEST", "h6Ty").mkString("-")
    private val botSecret = Seq("xoxb", "44", "B0SLACKTEST", "n3Ws").mkString("-")
    private val secretCfg = configOf(appLevelOf(appSecret), botOf(botSecret))

    private def rendersNoToken(text: String): Boolean =
        !text.contains(appSecret) && !text.contains(botSecret)

    /** Every message and `toString` along the cause chain, so a token hidden in a wrapped
      * kyo-http exception is caught too.
      */
    private def renderChain(t: Throwable): String =
        Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).take(8)
            .map(e => s"${e.toString}\n${e.getMessage}").mkString("\n")

    private def failureText(result: Result[SlackException, Any]): String =
        result match
            case Result.Failure(ex) => renderChain(ex)
            case other              => s"$other"

    /** Open through `Slack.init` in its own scope and through `Slack.initUnscoped`, and answer
      * `init`'s outcome once both are shown to fail alike and the unscoped one renders no token.
      */
    private def initAgainst(base: String, config: SlackConfig)(using Frame, kyo.test.AssertScope) =
        val atBase = at(base, config)
        for
            scoped   <- Abort.run[SlackException](Scope.run(Slack.init(atBase)))
            unscoped <- Abort.run[SlackException](Slack.initUnscoped(atBase))
        yield
            assert(scoped.failure == unscoped.failure, "init and initUnscoped fail alike")
            assert(rendersNoToken(failureText(unscoped)), "a Slack.initUnscoped failure renders a token")
            scoped
        end for
    end initAgainst

    "Slack.init and Slack.initUnscoped failures render no token: unreachable, ok:false, undecodable, unreachable wss, a refused socket url" in {
        def openServer(body: String)(using Frame) =
            val route = HttpRoute.postRaw("apps.connections.open").response(_.bodyText).handler { _ =>
                HttpResponse(HttpStatus.OK).addField("body", body)
            }
            HttpServer.init(0, "127.0.0.1")(route).map(server => s"http://127.0.0.1:${server.port}")
        end openServer
        import SlackTransportException.Kind
        for
            okFalseBase <- openServer("""{"ok":false,"error":"invalid_auth"}""")
            garbageBase <- openServer("not json")
            deadWssBase <- openServer("""{"ok":true,"url":"wss://127.0.0.1:1/ws/slack"}""")
            httpUrlBase <- openServer("""{"ok":true,"url":"http://127.0.0.1:1/ws/slack"}""")
            plainWsBase <- openServer("""{"ok":true,"url":"ws://127.0.0.1:1/ws/slack"}""")
            unreachable <- initAgainst("http://127.0.0.1:1/api", secretCfg)
            okFalse     <- initAgainst(okFalseBase, secretCfg)
            undecodable <- initAgainst(garbageBase, secretCfg)
            deadWss     <- initAgainst(deadWssBase, secretCfg)
            refused     <- initAgainst(httpUrlBase, secretCfg)
            plainWs     <- initAgainst(plainWsBase, secretCfg)
        yield
            val results = Chunk(unreachable, okFalse, undecodable, deadWss, refused, plainWs)
            assert(results.map(_.failure) == Chunk[Maybe[SlackException]](
                Present(SlackTransportException("apps.connections.open", Kind.Connect, "127.0.0.1", 1, Absent)(Absent)),
                Present(SlackInvalidAuthException("apps.connections.open", Chunk.empty)),
                Present(SlackDecodeException(
                    "apps.connections.open",
                    SlackDecodeException.Part.Envelope,
                    SlackDecodeException.Failure.Parse,
                    Chunk.empty,
                    Present(0)
                )),
                Present(SlackTransportException("socket-connect", Kind.Connect, "127.0.0.1", 1, Absent)(Absent)),
                Present(SlackRefusedUrlException("socket-connect")),
                Present(SlackRefusedUrlException("socket-connect"))
            ))
            results.foreach(r => assert(rendersNoToken(failureText(r)), "a Slack.init failure renders a token"))
        end for
    }

    "the client, its connection, engine, controller and config render no token" in {
        for
            pair <- connection(Seq(helloFrame), secretCfg)
            (conn, _) = pair
            engine <- engineOf(conn)
            rendered = Chunk(
                conn.toString,
                conn.connection.toString,
                engine.toString,
                conn.config.toString
            )
            _ <- Slack.close(conn)
        yield
            rendered.foreach(text => assert(rendersNoToken(text), "a rendered handle shows a token"))
            assert(conn.config.toString.contains("SlackToken.Bot(<redacted>)"), conn.config.toString)
        end for
    }

end SlackTest
