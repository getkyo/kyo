package kyo

import TeamsLocal.*
import kyo.charset.Charset
import kyo.internal.charset.Utf8

class TeamsWebhookTest extends kyo.test.Test[Any]:

    // A local HttpServer's closed connections are reaped on the selector's next pass, which the socket leak check sees as an open
    // descriptor.
    override def config = super.config.leakCheckSockets(false)

    private val path = Teams.WebhookPath.init("/api/messages").getOrThrow

    /** A served handler reads the server fiber's clock, not the test's controlled one, so its tokens are current at every instant. */
    private def lasting(local: Local, aud: String = s"\"${appId.value}\""): String =
        s"Bearer ${unsigned(local.claims(aud = aud, exp = "253402300799"))}"

    /** Serves `handler` on a local server and posts `deliveries` to it, answering each status with the number of times `f` ran. */
    private def post(local: Local, config: TeamsConfig, deliveries: Chunk[(Maybe[String], Span[Byte])])(using
        Frame
    ): (Chunk[HttpStatus], Int) < (Async & Abort[Any] & Scope) =
        AtomicInt.init.map { calls =>
            val f: [A] => Teams.Activity[A] => A < (Async & Abort[Nothing] & Env[Teams]) =
                [A] => (_: Teams.Activity[A]) => calls.incrementAndGet.andThen(Abort.panic(new Exception("f ran")))
            Teams.run(config)(Teams.Webhook.handler[Nothing](path)(f).map { handler =>
                HttpServer.init(HttpServerConfig.default.port(0).host("127.0.0.1"))(handler).map { server =>
                    val url = HttpUrl(Present("http"), "127.0.0.1", server.port, path.value, Absent)
                    Kyo.foreach(Chunk.from(deliveries)) { (authorization, body) =>
                        HttpClient.postTextResponse(
                            url,
                            Charset.Utf8.decode(body),
                            headers = authorization.fold(HttpHeaders.empty)(a => HttpHeaders.empty.add("Authorization", a)),
                            failOnError = false
                        ).map(_.status)
                    }.map(statuses => calls.get.map(statuses -> _))
                }
            })
        }

    private val verified = s"Bearer $signed"

    /** Serves `handler` with `f` on a local server and posts each body with `authorization`, answering each reply's status, body and
      * content type.
      */
    private def exchange[E](local: Local, authorization: String, bodies: Chunk[String])(
        f: [A] => Teams.Activity[A] => A < (Async & Abort[E] & Env[Teams])
    )(using Frame): Chunk[(HttpStatus, String, Maybe[String])] < (Async & Abort[Any] & Scope) =
        Teams.run(local.config)(Teams.Webhook.handler[E](path)(f).map { handler =>
            HttpServer.init(HttpServerConfig.default.port(0).host("127.0.0.1"))(handler).map { server =>
                val url = HttpUrl(Present("http"), "127.0.0.1", server.port, path.value, Absent)
                Kyo.foreach(Chunk.from(bodies)) { body =>
                    HttpClient.postTextResponse(
                        url,
                        body,
                        headers = HttpHeaders.empty.add("Authorization", authorization),
                        failOnError = false
                    )
                        .map(response => (response.status, response.fields.body, response.headers.get("Content-Type")))
                }
            }
        })

    /** A delivery from Teams at the service URL the vendored token names, of `kind` with `fields` added. */
    private def delivery(kind: String, fields: String = ""): String =
        s"""{"type":"$kind",$fields"id":"1:a","serviceUrl":"$signedServiceUrl","channelId":"msteams","from":{"id":"29:u"},"conversation":{"id":"${conversation.value}"},"recipient":{"id":"28:b"}}"""

    "decode reads a delivery's Activity" in {
        withLocal { local =>
            Abort.run[TeamsWebhookDecodeFailure](Teams.Webhook.decode(local.activity())).map { result =>
                val expected: Teams.Activity[Teams.Activity.Answer] = Teams.Activity.Typing(Teams.Activity.Common(
                    id = Teams.ActivityId.init("1:a").getOrThrow,
                    serviceUrl = local.serviceUrl,
                    channel = Teams.BotChannel.MsTeams,
                    sender = Teams.Account(Teams.UserId.init("29:u").getOrThrow),
                    conversation = Teams.ConversationAccount(conversation),
                    recipient = Teams.Account(Teams.UserId.init("28:b").getOrThrow)
                ))
                assert(result == Result.succeed(expected))
            }
        }
    }

    "decode fails a body that is not an Activity with the webhook's decode leaf, quoting nothing of it" in {
        // Built from parts: a failure's rendering quotes the source lines around its frame.
        val words = Chunk("private", "words").mkString("_")
        val body  = Utf8.encode(s"""{"type":"message","text":"$words"}""")
        Abort.run[TeamsWebhookDecodeFailure](Teams.Webhook.decode(body)).map { result =>
            assert(
                result == Result.fail(TeamsWebhookDecodeException(TeamsDecodeException.Failure.MissingField, Chunk("id"), Absent)),
                s"got: ${result.failure.map(fieldsOf)}"
            )
            assert(!result.failure.exists(e => rendered(e).contains(words)))
        }
    }

    "the handler answers 401 to a delivery whose token does not verify, without running f" in {
        withLocal { local =>
            val body = local.activity()
            post(
                local,
                local.config,
                Chunk(
                    Absent                                     -> body,
                    Present("Basic YTpi")                      -> body,
                    Present("Bearer a.b")                      -> body,
                    Present(lasting(local, aud = "\"other\"")) -> body,
                    Present(lasting(local))                    -> body
                )
            ).map { (statuses, calls) =>
                assert((statuses, calls) == (Chunk.fill(5)(HttpStatus.Unauthorized), 0))
            }
        }
    }

    "the handler answers 503 when the signing keys cannot be fetched, since the delivery may be genuine" in {
        withLocal { local =>
            val body          = local.activity()
            val authorization = Present(lasting(local))
            for
                _          <- local.reply("metadata", Reply(HttpStatus(500), ""))
                unexpected <- post(local, local.config, Chunk(authorization -> body))
                _          <- local.reply("metadata", local.metadata(algorithms = "\"RS512\""))
                algorithm  <- post(local, local.config, Chunk(authorization -> body))
                _          <- local.reply("metadata", local.metadata(jwksUri = "http://keys.example.com/keys"))
                refused    <- post(local, local.config, Chunk(authorization -> body))
                _          <- local.reply("metadata", local.metadata())
                _          <- local.reply("jwks", json("not json"))
                undecoded  <- post(local, local.config, Chunk(authorization -> body))
            yield
                val results = Chunk(unexpected, algorithm, refused, undecoded)
                assert(results == Chunk.fill(4)((Chunk(HttpStatus.ServiceUnavailable), 0)), s"got: $results")
            end for
        }
    }

    "a verified Activity that takes no answer runs f with it and is answered 200 with no body" in {
        withLocal { local =>
            AtomicRef.init(Chunk.empty[String]).map { seen =>
                val f: [A] => Teams.Activity[A] => A < (Async & Abort[Nothing] & Env[Teams]) =
                    [A] =>
                        (activity: Teams.Activity[A]) =>
                            activity match
                                case typing: Teams.Activity.Typing        => seen.updateAndGet(_ :+ typing.common.id.value).unit
                                case _: Teams.Activity.Plain              => Abort.panic(new Exception("another Activity"))
                                case _: Teams.Activity.OtherInvoke        => Abort.panic(new Exception("an invoke"))
                                case _: Teams.Activity.AdaptiveCardAction => Abort.panic(new Exception("an invoke"))
                exchange(local, verified, Chunk(delivery("typing")))(f).map { replies =>
                    seen.get.map(ids =>
                        assert(
                            (replies, ids) == (Chunk((HttpStatus.OK, "", Present("application/octet-stream"))), Chunk("1:a")),
                            s"got: $replies $ids"
                        )
                    )
                }
            }
        }
    }

    "every delivery runs f with the caller's Teams, the one Teams.run provides" in {
        withLocal { local =>
            AtomicRef.init(Chunk.empty[Teams]).map { seen =>
                val f: [A] => Teams.Activity[A] => A < (Async & Abort[Nothing] & Env[Teams]) =
                    [A] =>
                        (activity: Teams.Activity[A]) =>
                            activity match
                                case _: Teams.Activity.Plain              => Env.get[Teams].map(teams => seen.updateAndGet(_ :+ teams).unit)
                                case _: Teams.Activity.OtherInvoke        => Abort.panic(new Exception("an invoke"))
                                case _: Teams.Activity.AdaptiveCardAction => Abort.panic(new Exception("an invoke"))
                Teams.run(local.config)(Env.get[Teams].map { outer =>
                    Teams.Webhook.handler[Nothing](path)(f).map { handler =>
                        HttpServer.init(HttpServerConfig.default.port(0).host("127.0.0.1"))(handler).map { server =>
                            val url = HttpUrl(Present("http"), "127.0.0.1", server.port, path.value, Absent)
                            Kyo.foreach(Chunk(delivery("typing"), delivery("typing"))) { body =>
                                HttpClient.postTextResponse(
                                    url,
                                    body,
                                    headers = HttpHeaders.empty.add("Authorization", verified),
                                    failOnError = false
                                ).map(_.status)
                            }.map { statuses =>
                                seen.get.map { inner =>
                                    assert(statuses == Chunk(HttpStatus.OK, HttpStatus.OK), s"got: $statuses")
                                    assert(
                                        inner.size == 2 && inner.forall(_ eq outer),
                                        s"f ran with ${inner.size} clients, not the caller's"
                                    )
                                }
                            }
                        }
                    }
                })
            }
        }
    }

    "a verified invoke is answered 200 with f's card answer as JSON, or with the status and body f gives another invoke" in {
        withLocal { local =>
            val payload = Teams.RawJson(Json.decode[Structure.Value]("""{"composeExtension":{"type":"result"}}""").getOrThrow)
            val f: [A] => Teams.Activity[A] => A < (Async & Abort[Nothing] & Env[Teams]) =
                [A] =>
                    (activity: Teams.Activity[A]) =>
                        activity match
                            case action: Teams.Activity.AdaptiveCardAction =>
                                Teams.Card.ActionResponse.ShowMessage(action.value.action.verb.getOrElse("none"))
                            case other: Teams.Activity.OtherInvoke =>
                                if other.name == "composeExtension/query" then Teams.InvokeResponse(HttpStatus(202), Present(payload))
                                else Teams.InvokeResponse(HttpStatus.NotImplemented)
                            case _: Teams.Activity.Plain => Abort.panic(new Exception("not an invoke"))
            val action = delivery(
                "invoke",
                """"name":"adaptiveCard/action","value":{"action":{"type":"Action.Execute","verb":"ack"},"trigger":"manual"},"""
            )
            val query   = delivery("invoke", """"name":"composeExtension/query","value":{"commandId":"search"},""")
            val unknown = delivery("invoke", """"name":"task/fetch",""")
            exchange(local, verified, Chunk(action, query, unknown))(f).map { replies =>
                val json = Present("application/json")
                assert(
                    replies.map((s, b, t) => (s, TeamsJsonTree(if b.isEmpty then "null" else b), t)) == Chunk(
                        (
                            HttpStatus.OK,
                            TeamsJsonTree(Json.encode[Teams.Card.ActionResponse](Teams.Card.ActionResponse.ShowMessage("ack"))),
                            json
                        ),
                        (HttpStatus(202), TeamsJsonTree("""{"composeExtension":{"type":"result"}}"""), json),
                        (HttpStatus.NotImplemented, TeamsJsonTree("null"), Present("application/octet-stream"))
                    ),
                    s"got: $replies"
                )
            }
        }
    }

    "the handler answers 403 when the signing key does not endorse the channel, and 400 when the verified body is not an Activity" in {
        withLocal { local =>
            val unendorsed = delivery("typing").replace("\"msteams\"", "\"webchat\"")
            val noId       = delivery("typing").replace(""""id":"1:a",""", "")
            AtomicInt.init.map { calls =>
                val f: [A] => Teams.Activity[A] => A < (Async & Abort[Nothing] & Env[Teams]) =
                    [A] => (_: Teams.Activity[A]) => calls.incrementAndGet.andThen(Abort.panic(new Exception("f ran")))
                exchange(local, verified, Chunk(unendorsed, noId))(f).map { replies =>
                    calls.get.map(ran => assert((replies.map(_._1), ran) == (Chunk(HttpStatus.Forbidden, HttpStatus.BadRequest), 0)))
                }
            }
        }
    }

    "the handler answers 500 when f fails or panics" in {
        withLocal { local =>
            val f: [A] => Teams.Activity[A] => A < (Async & Abort[String] & Env[Teams]) =
                [A] =>
                    (activity: Teams.Activity[A]) =>
                        activity match
                            case _: Teams.Activity.Typing => Abort.fail("failed")
                            case _                        => Abort.panic(new Exception("panicked"))
            exchange(local, verified, Chunk(delivery("typing"), delivery("message")))(f).map { replies =>
                assert(replies.map(_._1) == Chunk(HttpStatus.InternalServerError, HttpStatus.InternalServerError))
            }
        }
    }

end TeamsWebhookTest
