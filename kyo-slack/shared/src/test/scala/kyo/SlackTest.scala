package kyo

import kyo.internal.SlackSocketEngine
import kyo.internal.SlackTransport
import kyo.internal.SlackWebApi

class SlackTest extends kyo.test.Test[Any]:

    "Slack.connect resolves with its public signature" in {
        typeCheck(
            "Slack.connect(SlackConfig(SlackToken.AppLevel(\"x\"), SlackToken.Bot(\"y\")))((_: SlackEnvelope) => SlackAck.Ack)"
        )
    }

    "the concise connect example reads idiomatically: a typed event match returning a SlackAck" in {
        typeCheck(
            "Slack.connect(SlackConfig(SlackToken.AppLevel(\"x\"), SlackToken.Bot(\"y\"))) { case SlackEnvelope.EventsApi(_, SlackEvent.Message(ch, _, text, _, _)) => Slack.chatPostMessage(SlackMessage(ch, s\"echo: $text\")).andThen(SlackAck.Ack); case _ => SlackAck.Ack }"
        )
    }

    private val cfg = SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"))
    private val helloFrame =
        """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private val eventFrame =
        """{"type":"events_api","envelope_id":"E1","payload":{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""

    // A Web API call from inside the receive-loop handler resolves the bot token bound
    // around the loop body. The loop is driven directly with the SlackWebApi.local binding
    // around the loop body, the exact scope the handler runs under, so the ambient token
    // resolves on the handler fiber. The handler reads the bound token via
    // SlackWebApi.local.use AND issues a real Slack.authTest against an unreachable base
    // url: it fails with a SlackTransportException (the token resolved and the request was
    // attempted), NOT a SlackHandshakeException (which an UNbound token would produce). This
    // gives the connectUnscoped handler path Web-API parity with the scoped connect.
    "a Web API call from inside a connectUnscoped + receive handler resolves the bound token" in {
        SlackTransport.inMemory(Chunk(helloFrame, eventFrame)).map { case (transport, _) =>
            SlackSocketEngine.initUnscoped(transport, "wss://test/socket", cfg).map { engine =>
                Channel.init[Maybe[SlackToken.Bot]](4).map { tokenSeen =>
                    Channel.init[SlackException](4).map { apiOutcome =>
                        val handler: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) =
                            case _: SlackEnvelope.EventsApi =>
                                SlackWebApi.local.use { bound =>
                                    Abort.run[Closed](tokenSeen.put(bound)).andThen {
                                        Abort.run[SlackException](Slack.authTest).map {
                                            case Result.Failure(ex: SlackException) =>
                                                Abort.run[Closed](apiOutcome.put(ex)).andThen(SlackAck.Ack: SlackAck)
                                            case _ => SlackAck.Ack: SlackAck
                                        }
                                    }
                                }
                            case _ => SlackAck.Ack: SlackAck
                        end handler
                        // Bind the bot token around the loop body, exactly where receive binds it,
                        // and point the Web API at an unreachable base so authTest attempts a real
                        // request and fails at the transport (proving the token resolved).
                        SlackWebApi.baseUrl.let("http://127.0.0.1:1/api") {
                            SlackWebApi.local.let(Present(cfg.bot)) {
                                Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler))).map { _ =>
                                    tokenSeen.stream().take(1).run.map { tokens =>
                                        assert(
                                            tokens.head == Present(cfg.bot),
                                            s"handler must observe the bound token, got: ${tokens.head}"
                                        )
                                        apiOutcome.stream().take(1).run.map { outcomes =>
                                            outcomes.head match
                                                case _: SlackException.SlackTransportException => assert(true)
                                                case _: SlackException.SlackHandshakeException =>
                                                    assert(false, "token was NOT bound around the loop (the connectUnscoped Local bug)")
                                                case other => assert(false, s"expected SlackTransportException, got: $other")
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

end SlackTest
