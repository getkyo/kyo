package kyo

import DiscordGatewayTest.*
import java.nio.charset.StandardCharsets.UTF_8

class DiscordGatewayTest extends kyo.test.Test[Any]:

    import Discord.*
    // kyo's channel, over the wildcard's `Discord.Channel`.
    import kyo.Channel

    // --- Connecting ---

    "init identifies after Hello, returns once Ready arrived, and receive delivers Ready and then the dispatches" in {
        withGateway { gw =>
            Fiber.initUnscoped(Discord.initUnscoped(gw.config)).map { initFiber =>
                gw.nextConnection.map { conn =>
                    for
                        _        <- conn.send(hello(1000))
                        identify <- conn.next
                        _        <- conn.send(ready(1, "S1", gw.resumeUrl))
                        discord  <- initFiber.get
                        received <- Channel.init[String](16)
                        loop     <- Fiber.initUnscoped(Discord.run(discord)(Discord.receive[Nothing]([A] =>
                            (event: Event[A]) => record(received, event).andThen(answer[A](event))
                        )))
                        // Handlers run on fibers of their own, so the dispatch goes out once Ready's handler ran.
                        first  <- received.take
                        _      <- conn.send(dispatch(2, "MESSAGE_CREATE", messageJson))
                        second <- received.take
                        events = Chunk(first, second)
                        _ <- loop.interrupt
                        _ <- Discord.close(discord)
                    yield
                        assert(field(identify, "op") == Present(Structure.Value.Integer(2)))
                        assert(field(identify, "d", "token") == Present(Structure.Value.Str(tokenSecret)))
                        assert(field(identify, "d", "intents") == Present(Structure.Value.Integer(intents.value)))
                        assert(field(identify, "d", "properties", "browser") == Present(Structure.Value.Str("kyo")))
                        assert(field(identify, "d", "shard") == Absent)
                        assert(conn.query == Present("v=10&encoding=json"))
                        assert(events == Chunk("Ready", "MessageCreated"))
                    end for
                }
            }
        }
    }

    "a shard in the config is sent as [shard_id, num_shards]" in {
        withGateway { gw =>
            val sharded = gw.config.copy(shard = Present(Shard.init(1, 2).getOrThrow))
            Fiber.initUnscoped(Discord.initUnscoped(sharded)).map { initFiber =>
                gw.nextConnection.map { conn =>
                    conn.send(hello(1000)).andThen(conn.next).map { identify =>
                        conn.send(ready(1, "S1", gw.resumeUrl)).andThen(initFiber.get).map(Discord.close).andThen {
                            assert(field(identify, "d", "shard") ==
                                Present(Structure.Value.Sequence(Chunk(Structure.Value.Integer(1), Structure.Value.Integer(2)))))
                        }
                    }
                }
            }
        }
    }

    // --- Heartbeats ---

    "the first heartbeat goes out within one interval with the last sequence, and an acknowledged one keeps the connection" in {
        // The jitter is pinned inside the interval: one advance of a whole interval must fire the first beat but not the next, which a
        // jitter under a millisecond would also fire, before the ack.
        withGateway { gw =>
            Clock.withTimeControl { control =>
                Random.let(fixedJitter(0.5))(connected(gw) { (conn, discord) =>
                    for
                        _     <- control.advance(1000.millis)
                        first <- conn.next
                        _     <- conn.send(ack)
                        _     <- conn.send(dispatch(2, "TYPING_START", typingJson))
                        // Frames are read in order, so the answer to op 1 carries the sequence of the dispatch before it.
                        _         <- conn.send("""{"op":1,"d":null}""")
                        requested <- conn.next
                        _         <- control.advance(1000.millis)
                        second    <- conn.next
                        _         <- Discord.close(discord)
                    yield assert((first, requested, second) == (heartbeat(1), heartbeat(2), heartbeat(2)))
                })
            }
        }
    }

    "a first heartbeat due at once goes out before any time passes, and an acknowledged one keeps the connection" in {
        withGateway { gw =>
            Clock.withTimeControl { control =>
                Random.let(fixedJitter(0.0))(connected(gw) { (conn, discord) =>
                    for
                        // Due at once, the beat goes out before Ready, so it carries no sequence yet.
                        first <- conn.next
                        _     <- conn.send(ack)
                        // The answer to op 1 is read after the ack, so the ack has landed before the interval elapses.
                        _         <- conn.send("""{"op":1,"d":null}""")
                        requested <- conn.next
                        _         <- control.advance(1000.millis)
                        second    <- conn.next
                        _         <- Discord.close(discord)
                    yield
                        val noSequence = Json.decode[Structure.Value]("""{"op":1,"d":null}""").getOrThrow
                        assert(
                            (first, requested, second) == (noSequence, heartbeat(1), heartbeat(1)),
                            s"got: ${Json.encode(first)}, ${Json.encode(requested)}, ${Json.encode(second)}"
                        )
                })
            }
        }
    }

    "a heartbeat Discord did not acknowledge before the next closes the connection with 4900 and resumes at resume_gateway_url" in {
        withGateway { gw =>
            Clock.withTimeControl { control =>
                connected(gw) { (conn, discord) =>
                    for
                        _      <- conn.send(dispatch(7, "TYPING_START", typingJson))
                        _      <- control.advance(1000.millis)
                        _      <- conn.next
                        _      <- control.advance(1000.millis)
                        closed <- conn.closedWith
                        second <- gw.nextConnection
                        _      <- second.send(hello(1000))
                        resume <- second.next
                        _      <- Discord.close(discord)
                    yield
                        assert(closed.map(_._1) == Present(4900))
                        assert(second.path == "/resume")
                        val expected = s"""{"op":6,"d":{"token":"$tokenSecret","session_id":"S1","seq":7}}"""
                        assert(resume == Json.decode[Structure.Value](expected).getOrThrow, s"got: ${Json.encode(resume)}")
                    end for
                }
            }
        }
    }

    "a heartbeat Discord asks for with op 1 goes out at once" in {
        withGateway { gw =>
            connected(gw) { (conn, discord) =>
                conn.send("""{"op":1,"d":null}""").andThen(conn.next).map { beat =>
                    Discord.close(discord).andThen(assert(beat == heartbeat(1)))
                }
            }
        }
    }

    "a first heartbeat due at once still follows the Identify" in {
        // A jitter of 0 makes the first heartbeat due the moment Hello arrives, so the second frame is that heartbeat. When the two raced,
        // the heartbeat went first in 11 to 14 of 200 handshakes, so 200 fresh clients make a reordering all but certain to show.
        withGateway { gw =>
            Kyo.foreach(Chunk.range(0, 200)) { _ =>
                Fiber.initUnscoped(Random.let(fixedJitter(0.0))(Discord.initUnscoped(gw.config))).map { initFiber =>
                    for
                        conn    <- gw.nextConnection
                        _       <- conn.send(hello(1000))
                        first   <- conn.next
                        second  <- conn.next
                        _       <- conn.send(ready(1, "S1", gw.resumeUrl))
                        discord <- initFiber.get
                        _       <- Discord.close(discord)
                    yield (field(first, "op"), field(second, "op"))
                }
            }.map { ops =>
                val inOrder = (Present(Structure.Value.Integer(2)), Present(Structure.Value.Integer(1)))
                assert(ops == Chunk.fill(200)(inOrder), s"out of order: ${ops.filterNot(_ == inOrder).size} of 200, ops: $ops")
            }
        }
    }

    // --- Reconnecting ---

    "op 7 closes the connection with 4900 and resumes on a new one, which delivers the replay and Resumed" in {
        withGateway { gw =>
            connected(gw) { (conn, discord) =>
                Channel.init[String](16).map { received =>
                    Fiber.initUnscoped(Discord.run(discord)(Discord.receive[Nothing]([A] =>
                        (event: Event[A]) => record(received, event).andThen(answer[A](event))
                    ))).map { loop =>
                        for
                            _      <- received.take
                            _      <- conn.send("""{"op":7,"d":null}""")
                            second <- gw.nextConnection
                            _      <- second.send(hello(1000))
                            resume <- second.next
                            _      <- second.send(dispatch(2, "MESSAGE_CREATE", messageJson))
                            _      <- second.send(dispatch(3, "RESUMED", "{}"))
                            events <- received.takeExactly(2)
                            closed <- conn.closedWith
                            _      <- loop.interrupt
                            _      <- Discord.close(discord)
                        yield
                            assert(field(resume, "op") == Present(Structure.Value.Integer(6)))
                            assert(closed.map(_._1) == Present(4900))
                            assert(events.toSet == Set("MessageCreated", "Resumed"))
                        end for
                    }
                }
            }
        }
    }

    "an invalid session that can resume resumes, and one that cannot waits and identifies afresh" in {
        withGateway { gw =>
            Clock.withTimeControl { control =>
                connected(gw) { (conn, discord) =>
                    for
                        _       <- conn.send("""{"op":9,"d":true}""")
                        atOnce  <- gw.reconnectWait
                        second  <- gw.nextConnection
                        _       <- second.send(hello(1000))
                        resume  <- second.next
                        _       <- second.send("""{"op":9,"d":false}""")
                        wait    <- gw.reconnectWait
                        _       <- control.advance(wait)
                        third   <- gw.nextConnection
                        _       <- third.send(hello(1000))
                        fresh   <- third.next
                        lookups <- gw.gatewayLookups
                        _       <- Discord.close(discord)
                    yield
                        // Discord asks for a wait of 1 to 5 seconds before the fresh Identify.
                        assert(atOnce == Duration.Zero)
                        assert(wait >= 1.second && wait <= 5.seconds, s"waited $wait")
                        assert(field(resume, "op") == Present(Structure.Value.Integer(6)))
                        assert(field(fresh, "op") == Present(Structure.Value.Integer(2)))
                        assert(lookups == 2)
                    end for
                }
            }
        }
    }

    "a close Discord marks as needing a new session identifies afresh, and any other resumes" in {
        withGateway { gw =>
            Clock.withTimeControl { control =>
                connected(gw) { (conn, discord) =>
                    for
                        _      <- conn.close(4000)
                        atOnce <- gw.reconnectWait
                        second <- gw.nextConnection
                        _      <- second.send(hello(1000))
                        resume <- second.next
                        _      <- second.send(dispatch(2, "RESUMED", "{}"))
                        _      <- second.close(4009)
                        wait   <- gw.reconnectWait
                        _      <- control.advance(wait)
                        third  <- gw.nextConnection
                        _      <- third.send(hello(1000))
                        fresh  <- third.next
                        _      <- Discord.close(discord)
                    yield
                        // Identifies on one client are 5 seconds apart with max_concurrency 1, and the first was at the start.
                        assert((atOnce, wait) == (Duration.Zero, 5.seconds))
                        assert((field(resume, "op"), field(fresh, "op")) ==
                            (Present(Structure.Value.Integer(6)), Present(Structure.Value.Integer(2))))
                    end for
                }
            }
        }
    }

    "a close code that ends the session is its leaf on init" in {
        Kyo.foreach(Chunk(4004, 4014)) { code =>
            withGateway { gw =>
                Fiber.initUnscoped(Abort.run[DiscordInitFailure](Discord.initUnscoped(gw.config))).map { initFiber =>
                    gw.nextConnection.map { conn =>
                        conn.send(hello(1000)).andThen(conn.next).andThen(conn.close(code)).andThen(initFiber.get).map(_.failure)
                    }
                }
            }
        }.map { failures =>
            assert(failures == Chunk(Present(DiscordAuthenticationFailedException()), Present(DiscordDisallowedIntentsException(intents))))
        }
    }

    "under Reconnect.Off a close the session could resume from ends receive cleanly" in {
        withGateway { gw =>
            val off = gw.config.copy(reconnect = DiscordConfig.Reconnect.Off)
            Fiber.initUnscoped(Discord.initUnscoped(off)).map { initFiber =>
                gw.nextConnection.map { conn =>
                    for
                        _       <- conn.send(hello(1000))
                        _       <- conn.next
                        _       <- conn.send(ready(1, "S1", gw.resumeUrl))
                        discord <- initFiber.get
                        loop    <-
                            Fiber.initUnscoped(Discord.run(discord)(Abort.run[DiscordReceiveFailure](Discord.receive[Nothing]([A] =>
                                (event: Event[A]) => answer[A](event)
                            ))))
                        _      <- conn.close(4000)
                        result <- loop.get
                        _      <- Discord.close(discord)
                        lines  <- gw.logLines
                    yield assert(result == Result.unit, s"got: $result; logged: ${lines.mkString(" | ")}")
                }
            }
        }
    }

    "a spent session start budget fails init with no connection opened" in {
        withGateway { gw =>
            gw.answerGatewayBot(gatewayBot(gw.gatewayUrl, remaining = 0)).andThen {
                Abort.run[DiscordInitFailure](Discord.initUnscoped(gw.config)).map { result =>
                    gw.connections.map { opened =>
                        assert((result.failure, opened) == (Present(DiscordSessionStartLimitException(1000, 3600000.millis)), 0))
                    }
                }
            }
        }
    }

    "a Gateway URL that is not wss with a host is refused, copying nothing of it" in {
        withGateway { gw =>
            gw.answerGatewayBot(gatewayBot("ws://127.0.0.1:1/gw")).andThen {
                Abort.run[DiscordInitFailure](Discord.initUnscoped(gw.config)).map { result =>
                    assert(result.failure == Present(DiscordRefusedUrlException("gateway-connect")))
                }
            }
        }
    }

    // --- Interactions ---

    "an interaction's answer is posted to its callback, and a deferred one writes the type alone" in {
        withGateway { gw =>
            connected(gw) { (conn, discord) =>
                Fiber.initUnscoped(Discord.run(discord)(Discord.receive[Nothing]([A] =>
                    (event: Event[A]) =>
                        event match
                            case e: Event.Command =>
                                if e.data.name == "roll" then
                                    InteractionResponse.Message(Message.Create.init(content = Present("4")).getOrThrow)
                                else InteractionResponse.DeferredMessage()
                            case other => answer[A](other)
                ))).map { loop =>
                    for
                        _         <- conn.send(dispatch(2, "INTERACTION_CREATE", commandJson("901", "roll")))
                        _         <- conn.send(dispatch(3, "INTERACTION_CREATE", commandJson("902", "slow")))
                        callbacks <- gw.callbacks(2)
                        _         <- loop.interrupt
                        _         <- Discord.close(discord)
                    yield assert(callbacks.toSet == Set(
                        (s"/interactions/901/$interactionSecret/callback", """{"type":4,"data":{"content":"4","tts":false}}"""),
                        (s"/interactions/902/$interactionSecret/callback", """{"type":5}""")
                    ))
                }
            }
        }
    }

    "an interaction the handler declines is left unanswered, for every kind, with one warn record naming it" in {
        withGateway { gw =>
            connected(gw) { (conn, discord) =>
                Fiber.initUnscoped(Discord.run(discord)(Discord.receive[Nothing]([A] => (event: Event[A]) => Event.unhandled(event))))
                    .map { loop =>
                        val kinds = Chunk(
                            interactionJson("901", 2, Present("""{"id":"800","name":"roll","type":1}""")),
                            interactionJson("902", 3, Present("""{"custom_id":"pick","component_type":2}""")),
                            interactionJson("903", 4, Present("""{"id":"800","name":"roll","type":1}""")),
                            interactionJson("904", 5, Present("""{"custom_id":"form","components":[]}""")),
                            interactionJson("905", 9, Absent)
                        )
                        for
                            _ <- conn.send(dispatch(2, "MESSAGE_CREATE", messageJson))
                            _ <-
                                Kyo.foreachDiscard(kinds.zipWithIndex)((json, i) => conn.send(dispatch(3L + i, "INTERACTION_CREATE", json)))
                            declined <- gw.logged(5, "declined")
                            posted   <- gw.callbackCount
                            _        <- loop.interrupt
                            _        <- Discord.close(discord)
                        yield
                            assert(declined.toSet == Set(
                                "Discord: interaction 901 (command) was declined, so it goes unanswered.",
                                "Discord: interaction 902 (component) was declined, so it goes unanswered.",
                                "Discord: interaction 903 (autocomplete) was declined, so it goes unanswered.",
                                "Discord: interaction 904 (modal submit) was declined, so it goes unanswered.",
                                "Discord: interaction 905 (type 9) was declined, so it goes unanswered."
                            ))
                            assert(posted == 0)
                        end for
                    }
            }
        }
    }

    "a handler past the interaction deadline is interrupted and nothing is posted" in {
        withGateway { gw =>
            Clock.withTimeControl { control =>
                connected(gw) { (conn, discord) =>
                    Latch.init(1).map { started =>
                        Latch.init(1).map { interrupted =>
                            Fiber.initUnscoped(Discord.run(discord)(Discord.receive[Nothing]([A] =>
                                (event: Event[A]) =>
                                    event match
                                        case _: Event.Command =>
                                            Sync.ensure(interrupted.release)(started.release.andThen(Async.never))
                                        case other => answer[A](other)
                            ))).map { loop =>
                                for
                                    _         <- conn.send(dispatch(2, "INTERACTION_CREATE", commandJson("901", "roll")))
                                    _         <- started.await
                                    _         <- control.advance(2500.millis)
                                    _         <- interrupted.await
                                    callbacks <- gw.callbackCount
                                    _         <- loop.interrupt
                                    _         <- Discord.close(discord)
                                yield assert(callbacks == 0)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Handlers and the end of receive ---

    "a handler's typed failure ends receive with it and closes the socket with 1000, and a panic is logged and the loop goes on" in {
        withGateway { gw =>
            Fiber.initUnscoped(Abort.run[DiscordReceiveFailure | String](Discord.run(gw.config)(Discord.receive[String]([A] =>
                (event: Event[A]) =>
                    event match
                        case Event.MessageCreated(message) if message.content == "panic" => Abort.panic(new Exception("boom"))
                        case Event.MessageCreated(_)                                     => Abort.fail("stop")
                        case other                                                       => answer[A](other)
            )))).map { runFiber =>
                gw.nextConnection.map { conn =>
                    for
                        _      <- conn.send(hello(1000))
                        _      <- conn.next
                        _      <- conn.send(ready(1, "S1", gw.resumeUrl))
                        _      <- conn.send(dispatch(2, "MESSAGE_CREATE", messageJson.replace("\"hi\"", "\"panic\"")))
                        _      <- conn.send(dispatch(3, "MESSAGE_CREATE", messageJson))
                        result <- runFiber.get
                        closed <- conn.closedWith
                    yield assert((result, closed.map(_._1)) == (Result.fail("stop"), Present(1000)), s"got: $result and $closed")
                }
            }
        }
    }

    // --- Rate limits on REST ---

    "a bucket Discord said is spent refuses a call whose wait would pass requestTimeout, sending nothing" in {
        withGateway { gw =>
            val config = gw.config.copy(requestTimeout = 1.second)
            gw.answerChannel(Seq("X-RateLimit-Bucket" -> "b1", "X-RateLimit-Remaining" -> "0", "X-RateLimit-Reset-After" -> "5")).andThen {
                Discord.run(config) {
                    Discord.channel(ChannelId(111L)).andThen(Abort.run[DiscordChannelFailure](Discord.channel(ChannelId(111L))))
                }.map { second =>
                    gw.channelLookups.map { sent =>
                        assert((second.failure, sent) == (
                            Present(DiscordRouteRateLimitException(
                                "GET /channels/{channel.id}",
                                Present(5.seconds),
                                Absent,
                                Present("b1")
                            )),
                            1
                        ))
                    }
                }
            }
        }
    }

end DiscordGatewayTest

object DiscordGatewayTest:

    import Discord.*
    // kyo's channel, over the wildcard's `Discord.Channel`.
    import kyo.Channel

    // Built from parts: a failure's message quotes the source lines around its frame, which would otherwise show the literal.
    val tokenSecret: String       = Seq("MTA0OTI3NjU0MzIxMDk4NzY1", "gateway", "TESTsecretPart").mkString(".")
    val interactionSecret: String = Seq("aW50ZXJhY3Rpb24", "gwLEAK", "check").mkString("_")

    val intents: Intents = Intents.Guilds.union(Intents.GuildMessages)

    val messageJson =
        """{"id":"300","channel_id":"111","author":{"id":"7","username":"kyo"},"content":"hi","timestamp":"2026-10-02T10:00:00.000000+00:00"}"""
    val typingJson = """{"channel_id":"111","user_id":"7","timestamp":1700000000}"""

    def interactionJson(id: String, kind: Int, data: Maybe[String]): String =
        s"""{"id":"$id","application_id":"900","type":$kind,"token":"$interactionSecret","channel_id":"111"""" +
            data.fold("")(d => s""","data":$d""") + "}"

    def commandJson(id: String, name: String): String =
        s"""{"id":"$id","application_id":"900","type":2,"token":"$interactionSecret","channel_id":"111","data":{"id":"800","name":"$name","type":1}}"""

    def hello(intervalMillis: Int): String = s"""{"op":10,"d":{"heartbeat_interval":$intervalMillis}}"""

    val ack: String = """{"op":11}"""

    def dispatch(seq: Long, name: String, data: String): String = s"""{"op":0,"s":$seq,"t":"$name","d":$data}"""

    def ready(seq: Long, sessionId: String, resumeUrl: String): String =
        dispatch(
            seq,
            "READY",
            s"""{"v":10,"user":{"id":"7","username":"kyo","bot":true},"guilds":[],"session_id":"$sessionId",""" +
                s""""resume_gateway_url":"$resumeUrl","application":{"id":"900","flags":0}}"""
        )

    def heartbeat(seq: Long)(using Frame): Structure.Value = Json.decode[Structure.Value](s"""{"op":1,"d":$seq}""").getOrThrow

    /** A `Random` whose every double is `value`: the first heartbeat's jitter, pinned. */
    def fixedJitter(value: Double): Random =
        Random(Random.Unsafe(new java.util.Random:
            override def nextDouble(): Double = value))

    def gatewayBot(url: String, remaining: Int = 999): String =
        s"""{"url":"$url","shards":1,"session_start_limit":{"total":1000,"remaining":$remaining,"reset_after":3600000,"max_concurrency":1}}"""

    /** The value at `path` in a JSON object. */
    def field(value: Structure.Value, path: String*): Maybe[Structure.Value] =
        path.foldLeft(Present(value): Maybe[Structure.Value]) { (at, key) =>
            at.flatMap {
                case Structure.Value.Record(fields) => Maybe.fromOption(fields.collectFirst { case (`key`, v) => v })
                case _                              => Absent
            }
        }

    /** Records the event's case name. */
    def record[A](received: Channel[String], event: Event[A])(using Frame): Unit < Async =
        Abort.run[Closed](received.put(event.getClass.getSimpleName.stripSuffix("$"))).unit

    /** The answer a test handler gives an event it does not otherwise answer: nothing for a dispatch, a deferral for an interaction. */
    def answer[A](event: Event[A]): A =
        event match
            case _: Event.Command            => InteractionResponse.DeferredMessage()
            case _: Event.Component          => InteractionResponse.DeferredUpdate
            case _: Event.Autocomplete       => InteractionResponse.Autocomplete.init(Chunk.empty).getOrThrow
            case _: Event.ModalSubmit        => InteractionResponse.DeferredMessage()
            case _: Event.UnknownInteraction => InteractionResponse.DeferredMessage()
            case _: (Event.Ready | Event.Resumed.type | Event.MessageCreated | Event.MessageUpdated | Event.MessageDeleted |
                    Event.ReactionAdded | Event.ReactionRemoved | Event.ThreadCreated | Event.ThreadUpdated | Event.ThreadDeleted |
                    Event.ChannelCreated | Event.ChannelUpdated | Event.ChannelDeleted | Event.GuildCreated | Event.GuildDeleted |
                    Event.MemberJoined | Event.MemberLeft | Event.TypingStarted | Event.Unknown) => ()

    /** One WebSocket connection the client opened to the local Gateway: its path and query, and the socket the test scripts. */
    final class Conn(val path: String, val query: Maybe[String], ws: HttpWebSocket):
        def send(json: String)(using Frame): Unit < (Async & Abort[Closed]) = ws.put(HttpWebSocket.Payload.Text(json))

        /** The next frame the client sent, as JSON. */
        def next(using Frame): Structure.Value < (Async & Abort[Any]) =
            ws.take().map {
                case HttpWebSocket.Payload.Text(text) => Abort.get(Json.decode[Structure.Value](text))
                case HttpWebSocket.Payload.Binary(_)  => Abort.fail("the client sent a binary frame")
            }

        /** Closes the connection from Discord's side with `code`. */
        def close(code: Int)(using Frame): Unit < Async = ws.close(code, "test")

        /** The code and reason the client closed with, once it has. */
        def closedWith(using Frame): Maybe[(Int, String)] < Async = ws.onPeerClose.andThen(ws.closeReason)
    end Conn

    /** A local Discord over TLS: `GET /gateway/bot`, `GET /channels/{id}`, the interaction callbacks, and the Gateway at `/gw` and
      * `/resume`, each connection handed to the test. Every leaf runs under `Clock.withTimeControl`.
      */
    final class Gateway(
        val port: Int,
        conns: Channel[Conn],
        opened: AtomicInt,
        gatewayBotBody: AtomicRef[String],
        lookups: AtomicInt,
        channelHeaders: AtomicRef[Seq[(String, String)]],
        channelCalls: AtomicInt,
        posted: Channel[(String, String)],
        postedCount: AtomicInt,
        logs: Channel[String]
    ):
        /** The wait the session logged before its next reconnection. It logs once the wait's timer is armed, so advancing the clock by
          * it after this returns always reaches the reconnection.
          */
        /** Every line logged and not yet read. */
        def logLines(using Frame): Chunk[String] < (Sync & Abort[Closed]) = logs.drain

        /** The next `n` lines logged that contain `text`, skipping the others. */
        def logged(n: Int, text: String)(using Frame): Chunk[String] < (Async & Abort[Closed]) =
            Loop(Chunk.empty[String]) { found =>
                if found.size == n then Loop.done(found)
                else logs.take.map(line => Loop.continue(if line.contains(text) then found :+ line else found))
            }

        def reconnectWait(using Frame): Duration < (Async & Abort[Any]) =
            Loop.foreach {
                logs.take.map { line =>
                    Maybe.fromOption(ReconnectLine.findFirstMatchIn(line)) match
                        case Present(m) => Abort.get(Duration.parse(s"${m.group(1)} millis")).map(Loop.done(_))
                        case Absent     => Loop.continue
                }
            }

        def gatewayUrl: String = s"wss://127.0.0.1:$port/gw"
        def resumeUrl: String  = s"wss://127.0.0.1:$port/resume"

        def config(using Frame): DiscordConfig =
            DiscordConfig.init(
                Token.init(tokenSecret).getOrThrow,
                intents,
                baseUrl = HttpUrl(Present("https"), "127.0.0.1", port, "/api/v10", Absent),
                tls = HttpTlsConfig(trustAll = true)
            ).getOrThrow

        def nextConnection(using Frame): Conn < (Async & Abort[Closed]) = conns.take
        def connections(using Frame): Int < Sync                        = opened.get
        def gatewayLookups(using Frame): Int < Sync                     = lookups.get
        def channelLookups(using Frame): Int < Sync                     = channelCalls.get

        def answerGatewayBot(body: String)(using Frame): Unit < Sync = gatewayBotBody.set(body)

        def answerChannel(headers: Seq[(String, String)])(using Frame): Unit < Sync = channelHeaders.set(headers)

        /** The next `n` callbacks posted: each one's path and body. */
        def callbacks(n: Int)(using Frame): Chunk[(String, String)] < (Async & Abort[Closed]) = posted.takeExactly(n)

        def callbackCount(using Frame): Int < Sync = postedCount.get
    end Gateway

    private val ReconnectLine = """reconnecting in (\d+) milliseconds""".r

    /** A logger that hands every line it is given to `lines`, dropping lines past its capacity. */
    final class LogCapture(lines: Channel[String]) extends Log.Unsafe:
        def level: Log.Level                   = Log.Level.trace
        def name: String                       = "discord-test"
        def withName(name: String): Log.Unsafe = this
        // Unsafe: a logger's methods run outside the effect system; offering to the channel never suspends.
        private def keep(msg: String)(using AllowUnsafe): Unit                                   = discard(lines.unsafe.offer(msg))
        def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = keep(msg)
        def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = keep(msg)
        def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = keep(msg)
        def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = keep(msg)
        def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = keep(msg)
        def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = keep(msg)
        def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                   = keep(msg)
        def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit  = keep(msg)
        def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit                  = keep(msg)
        def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit = keep(msg)
    end LogCapture

    def withGateway[A](test: Gateway => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Clock.withTimeControl { _ =>
            for
                logs           <- Channel.init[String](1024)
                conns          <- Channel.init[Conn](16)
                opened         <- AtomicInt.init
                gatewayBotBody <- AtomicRef.init("")
                lookups        <- AtomicInt.init
                channelHeaders <- AtomicRef.init(Seq.empty[(String, String)])
                channelCalls   <- AtomicInt.init
                posted         <- Channel.init[(String, String)](16)
                postedCount    <- AtomicInt.init
                portRef        <- AtomicInt.init
                socket = (req: HttpRequest[Any], ws: HttpWebSocket) =>
                    opened.incrementAndGet.andThen(conns.put(Conn(req.url.path, req.url.rawQuery, ws))).andThen(ws.onPeerClose)
                gatewayBotRoute = HttpRoute.getRaw("api" / "v10" / "gateway" / "bot").response(_.bodyText).handler { _ =>
                    lookups.incrementAndGet.andThen(gatewayBotBody.get).map(body => HttpResponse.ok(body))
                }
                channel = HttpRoute.getRaw("api" / "v10" / "channels" / HttpPath.Capture.Rest("id")).response(_.bodyText).handler { _ =>
                    channelCalls.incrementAndGet.andThen(channelHeaders.get).map { headers =>
                        headers.foldLeft(HttpResponse.ok("""{"id":"111","type":0}"""))((r, h) => r.addHeader(h._1, h._2))
                    }
                }
                callback = HttpRoute.postRaw("api" / "v10" / "interactions" / HttpPath.Capture.Rest("path")).request(_.bodyBinary)
                    .response(_.bodyText).handler { req =>
                        val path = req.url.path.stripPrefix("/api/v10")
                        postedCount.incrementAndGet.andThen(posted.put((path, new String(req.fields.body.toArray, UTF_8))))
                            .andThen(HttpResponse(HttpStatus(204)).addField("body", ""))
                    }
                pems   <- kyo.net.TlsTestCertShared.writePems
                server <-
                    HttpServer.init(HttpServerConfig.default.port(0).host("127.0.0.1").tls(
                        HttpTlsConfig(certChainPath = Present(pems._1), privateKeyPath = Present(pems._2))
                    ))(
                        gatewayBotRoute,
                        channel,
                        callback,
                        HttpHandler.webSocket("gw")(socket),
                        HttpHandler.webSocket("resume")(socket)
                    )
                gw = Gateway(server.port, conns, opened, gatewayBotBody, lookups, channelHeaders, channelCalls, posted, postedCount, logs)
                _      <- gw.answerGatewayBot(gatewayBot(gw.gatewayUrl))
                result <- Log.let(Log(LogCapture(logs)))(test(gw))
            yield result
            end for
        }

    /** Runs `test` on a client built by `initUnscoped` whose session reached Ready with sequence 1, session `S1`, and Hello's interval
      * of 1 second, with the connection it holds.
      */
    def connected[A](gw: Gateway)(test: (Conn, Discord) => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        Fiber.initUnscoped(Discord.initUnscoped(gw.config)).map { initFiber =>
            gw.nextConnection.map { conn =>
                conn.send(hello(1000)).andThen(conn.next).andThen(conn.send(ready(1, "S1", gw.resumeUrl))).andThen(initFiber.get).map {
                    discord => test(conn, discord)
                }
            }
        }

end DiscordGatewayTest
