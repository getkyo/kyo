package kyo.internal

import kyo.*

/** Real-WebSocket round-trip test for the receive engine over `SlackTransport.live`
  * against an in-process kyo-http WS server. The server sends a real `hello` then a real
  * `events_api` frame; the engine observes hello (readiness), the receive loop delivers
  * the event to a handler returning `SlackAck.Ack`, and the server captures the real ack
  * frame the engine sends back over the socket.
  *
  * Each leaf is `.notNative`-gated because the in-process kyo-http WebSocket server runs
  * on JVM, JS, and Wasm but not Native (kyo-http's own WebSocket suite gates the same
  * way); the cross-platform decode/deliver/ack logic is also covered by the in-memory
  * conduit in SlackSocketEngineTest on all four platforms. This leaf exercises the real
  * OS socket byte-transport that the in-memory path cannot.
  */
class SlackSocketEngineLiveTest extends kyo.test.Test[Any]:

    private val cfg = SlackConfig(SlackToken.AppLevel("xapp-test"), SlackToken.Bot("xoxb-test"))

    private val helloFrame =
        """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private val eventFrame =
        """{"type":"events_api","envelope_id":"E1","payload":{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""

    "engine over a real WS server observes hello, delivers the event, and acks over the socket".notNative in {
        // The server pushes hello + one event, then forwards every client frame (the ack)
        // into `acked` so the test observes the real ack bytes that travelled the socket.
        Channel.init[String](8).map { acked =>
            val wsHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
                (_, ws) =>
                    ws.put(HttpWebSocket.Payload.Text(helloFrame))
                        .andThen(ws.put(HttpWebSocket.Payload.Text(eventFrame)))
                        .andThen {
                            ws.stream.foreach {
                                case HttpWebSocket.Payload.Text(s) => Abort.run[Closed](acked.put(s)).unit
                                case _                             => Kyo.unit
                            }
                        }
            HttpServer.init(0, "localhost")(HttpHandler.webSocket("ws/slack")(wsHandler)).map { server =>
                val wsUrl = s"ws://localhost:${server.port}/ws/slack"
                SlackSocketEngine.initUnscoped(SlackTransport.live, wsUrl, cfg).map { engine =>
                    Channel.init[SlackEnvelope](8).map { delivered =>
                        val handler: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) =
                            env => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
                        Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler))).map { _ =>
                            // The hello is delivered first, then the event; the engine acks E1.
                            delivered.stream().take(2).run.map { envs =>
                                acked.stream().take(1).run.map { acks =>
                                    engine.closeNow.andThen {
                                        assert(envs.head.isInstanceOf[SlackEnvelope.Hello], s"hello first, got: ${envs.head}")
                                        assert(envs(1).isInstanceOf[SlackEnvelope.EventsApi], s"event second, got: ${envs(1)}")
                                        assert(acks == Chunk("""{"envelope_id":"E1"}"""), s"real socket ack, got: $acks")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "connectUnscoped then receive delivers the event and acks over the real socket".notNative in {
        // End-to-end manual path: a real apps.connections.open returns a wss url, the
        // live transport connects, receive drives the loop, and the engine acks E1 back
        // over the real socket (captured by the server).
        Channel.init[String](8).map { acked =>
            val wsHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
                (_, ws) =>
                    ws.put(HttpWebSocket.Payload.Text(helloFrame))
                        .andThen(ws.put(HttpWebSocket.Payload.Text(eventFrame)))
                        .andThen {
                            ws.stream.foreach {
                                case HttpWebSocket.Payload.Text(s) => Abort.run[Closed](acked.put(s)).unit
                                case _                             => Kyo.unit
                            }
                        }
            HttpServer.init(0, "localhost")(HttpHandler.webSocket("ws/slack")(wsHandler)).map { wsServer =>
                val wssUrl = s"ws://localhost:${wsServer.port}/ws/slack"
                val openRoute = HttpRoute.postRaw("apps.connections.open").response(_.bodyText).handler { _ =>
                    HttpResponse(HttpStatus.OK).addField("body", s"""{"ok":true,"url":"$wssUrl"}""")
                }
                HttpServer.init(0, "localhost")(openRoute).map { apiServer =>
                    SlackWebApi.baseUrl.let(s"http://localhost:${apiServer.port}") {
                        Slack.connectUnscoped(cfg).map { conn =>
                            Channel.init[SlackEnvelope](8).map { delivered =>
                                val handler: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) =
                                    env => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
                                Fiber.initUnscoped(Abort.run[SlackException](conn.receive(handler))).map { loop =>
                                    delivered.stream().take(2).run.map { envs =>
                                        acked.stream().take(1).run.map { acks =>
                                            conn.close.andThen(loop.interrupt).andThen {
                                                assert(envs.exists(_.isInstanceOf[SlackEnvelope.EventsApi]), s"event delivered, got: $envs")
                                                assert(acks == Chunk("""{"envelope_id":"E1"}"""), s"real socket ack, got: $acks")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "connect runs the loop under a Scope and tears the socket down on scope exit".notNative in {
        // Slack.connect is Scope-managed: the scope finalizer closes the active engine.
        // The server observes the client socket close (its ws.stream ends) and releases
        // the teardown latch, so teardown is an OBSERVED event, not a log line.
        Latch.init(1).map { serverSawClose =>
            Channel.init[SlackEnvelope](8).map { delivered =>
                val wsHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
                    (_, ws) =>
                        ws.put(HttpWebSocket.Payload.Text(helloFrame))
                            .andThen(ws.put(HttpWebSocket.Payload.Text(eventFrame)))
                            .andThen(ws.stream.foreach(_ => Kyo.unit))
                            .andThen(serverSawClose.release)
                HttpServer.init(0, "localhost")(HttpHandler.webSocket("ws/slack")(wsHandler)).map { wsServer =>
                    val wssUrl = s"ws://localhost:${wsServer.port}/ws/slack"
                    val openRoute = HttpRoute.postRaw("apps.connections.open").response(_.bodyText).handler { _ =>
                        HttpResponse(HttpStatus.OK).addField("body", s"""{"ok":true,"url":"$wssUrl"}""")
                    }
                    HttpServer.init(0, "localhost")(openRoute).map { apiServer =>
                        SlackWebApi.baseUrl.let(s"http://localhost:${apiServer.port}") {
                            val handler: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) =
                                env => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
                            // Run connect in a fiber; once the event is delivered, interrupt it so the
                            // scope exits and the finalizer closes the socket.
                            Fiber.initUnscoped(Abort.run[SlackException](Scope.run(Slack.connect(cfg)(handler)))).map { connFiber =>
                                delivered.stream().take(2).run.map { envs =>
                                    connFiber.interrupt.andThen(serverSawClose.await).andThen {
                                        assert(
                                            envs.exists(_.isInstanceOf[SlackEnvelope.EventsApi]),
                                            s"event delivered before teardown, got: $envs"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "connectUnscoped opens via a real apps.connections.open HTTP call then connects the wss".notNative in {
        // Prove the openEngine HTTP path end to end: a real apps.connections.open returns a
        // wss url (a 2xx body, the case kyo-http leaves rawBody Absent for), and connectUnscoped
        // then connects the live transport and reaches readiness on the hello.
        val wsHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
            (_, ws) => ws.put(HttpWebSocket.Payload.Text(helloFrame)).andThen(ws.stream.foreach(_ => Kyo.unit))
        HttpServer.init(0, "localhost")(
            HttpHandler.webSocket("ws/slack")(wsHandler)
        ).map { wsServer =>
            val wssUrl = s"ws://localhost:${wsServer.port}/ws/slack"
            val openRoute = HttpRoute.postRaw("apps.connections.open").response(_.bodyText).handler { _ =>
                HttpResponse(HttpStatus.OK).addField("body", s"""{"ok":true,"url":"$wssUrl"}""")
            }
            HttpServer.init(0, "localhost")(openRoute).map { apiServer =>
                SlackWebApi.baseUrl.let(s"http://localhost:${apiServer.port}") {
                    Slack.connectUnscoped(cfg).map { conn =>
                        val engine = SlackConnection.handle(conn).engine
                        engine.closeNow.andThen(assert(true))
                    }
                }
            }
        }
    }

end SlackSocketEngineLiveTest
