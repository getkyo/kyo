package kyo.internal

import kyo.*

/** Real-HTTP round-trip test for the Web API request path against an in-process
  * kyo-http server. Proves the live HTTP path end to end: a real `{"ok":true,...}`
  * decodes to the typed result, a real `{"ok":false,"error":...}` surfaces a typed
  * `SlackWebApiException(error)`, an HTTP 429 surfaces a typed `SlackRateLimitException`
  * with the parsed Retry-After, AND the outbound request body splices Block Kit blocks
  * as a NATIVE JSON array (`"blocks":[`, not `"blocks":"[`).
  *
  * Each leaf is `.notNative`-gated because the in-process kyo-http server runs on JVM,
  * JS, and Wasm but not Native (kyo-http's own server suites gate the same way). The
  * cross-platform response-mapping and body-encoding logic is also covered by the
  * in-memory path in SlackWebApiTest on all four platforms; this leaf exercises the
  * real OS socket / HTTP transport that the in-memory path cannot.
  */
class SlackWebApiLiveTest extends kyo.test.Test[Any]:

    private val bot = SlackToken.Bot("xoxb-test")

    // A minimal JSON ok body for the response_url server reply (the real Slack
    // response_url returns a small JSON ok; postResponseUrl decodes the reply as JSON).
    private case class OkResp(ok: Boolean) derives Schema

    private def withSlackServer[A](handlers: HttpHandler[?, ?, ?]*)(
        test: String => A < (Async & Abort[SlackException | HttpException] & Scope)
    )(using Frame): A < (Async & Abort[SlackException | HttpException] & Scope) =
        HttpServer.init(0, "localhost")(handlers*).map { server =>
            val base = s"http://localhost:${server.port}"
            SlackWebApi.baseUrl.let(base) {
                SlackWebApi.local.let(Present(bot))(test(base))
            }
        }

    "chatPostMessage on ok:true returns the posted ts and sends a native blocks array".notNative in {
        AtomicRef.init(Maybe.empty[String]).map { bodyRef =>
            val route = HttpRoute.postRaw("chat.postMessage").request(_.bodyText).response(_.bodyText).handler { req =>
                val received = req.fields.body
                bodyRef.set(Present(received)).andThen(HttpResponse(HttpStatus.OK).addField(
                    "body",
                    """{"ok":true,"ts":"1.99"}"""
                ))
            }
            withSlackServer(route) { _ =>
                val msg = SlackMessage(
                    SlackId.ChannelId("C1"),
                    "hi",
                    blocks = Chunk(SlackBlock.Section(SlackBlock.Text.Markdown("x")))
                )
                Slack.chatPostMessage(msg).map { ts =>
                    bodyRef.get.map { sent =>
                        assert(ts.value == "1.99")
                        assert(sent.exists(_.contains("\"blocks\":[")), s"blocks must be a native JSON array, got: $sent")
                        assert(sent.exists(!_.contains("\"blocks\":\"")), s"blocks must NOT be a quoted string, got: $sent")
                        assert(sent.exists(_.contains("\"channel\":\"C1\"")), s"channel key missing: $sent")
                    }
                }
            }
        }
    }

    "authTest on ok:true returns the decoded identity".notNative in {
        val route = HttpRoute.postRaw("auth.test").response(_.bodyText).handler { _ =>
            HttpResponse(HttpStatus.OK).addField(
                "body",
                """{"ok":true,"user_id":"U1","team_id":"T1","bot_id":"B1","url":"https://x.slack.com"}"""
            )
        }
        withSlackServer(route) { _ =>
            Slack.authTest.map { id =>
                assert(id.botId == SlackId.BotId("B1"))
                assert(id.url == "https://x.slack.com")
            }
        }
    }

    "chatPostMessage on ok:false surfaces SlackWebApiException(error)".notNative in {
        val route = HttpRoute.postRaw("chat.postMessage").response(_.bodyText).handler { _ =>
            HttpResponse(HttpStatus.OK).addField(
                "body",
                """{"ok":false,"error":"channel_not_found"}"""
            )
        }
        withSlackServer(route) { _ =>
            Abort.run[SlackException](Slack.chatPostMessage(SlackMessage(SlackId.ChannelId("C1"), "hi"))).map { result =>
                result match
                    case Result.Failure(ex: SlackWebApiException) => assert(ex.error == "channel_not_found")
                    case other                                    => assert(false, s"expected SlackWebApiException, got: $other")
            }
        }
    }

    "postResponseUrl sends a native blocks array and snake_case thread_ts, not the camelCase value".notNative in {
        AtomicRef.init(Maybe.empty[String]).map { bodyRef =>
            // postResponseUrl POSTs JSON and decodes the response as JSON, so the route
            // reads the raw request body (the assertion target) and replies with a JSON ok.
            val route = HttpRoute.postRaw("hook").request(_.bodyText).response(_.bodyJson[OkResp]).handler { req =>
                val received = req.fields.body
                bodyRef.set(Present(received)).andThen(HttpResponse.ok(OkResp(true)))
            }
            HttpServer.init(0, "localhost")(route).map { server =>
                val hookUrl = s"http://localhost:${server.port}/hook"
                val msg = SlackMessage(
                    SlackId.ChannelId("C1"),
                    "updated",
                    threadTs = Present(SlackTs("1.55")),
                    blocks = Chunk(SlackBlock.Section(SlackBlock.Text.Markdown("x")))
                )
                SlackWebApi.local.let(Present(bot)) {
                    SlackWebApi.postResponseUrl(hookUrl, msg).andThen {
                        bodyRef.get.map { sent =>
                            assert(sent.exists(_.contains("\"blocks\":[")), s"blocks must be a native JSON array, got: $sent")
                            assert(sent.exists(!_.contains("\"blocks\":\"")), s"blocks must NOT be a quoted string, got: $sent")
                            assert(sent.exists(!_.contains("blocksJson")), s"must not ship the camelCase blocksJson key, got: $sent")
                            assert(sent.exists(_.contains("\"thread_ts\":\"1.55\"")), s"thread_ts must be snake_case, got: $sent")
                            assert(sent.exists(!_.contains("threadTs")), s"must not ship the camelCase threadTs key, got: $sent")
                        }
                    }
                }
            }
        }
    }

    "chatPostMessage on HTTP 429 surfaces SlackRateLimitException with the parsed Retry-After".notNative in {
        val route = HttpRoute.postRaw("chat.postMessage").response(_.bodyText).handler { _ =>
            HttpResponse(HttpStatus.TooManyRequests).addField(
                "body",
                """{"ok":false,"error":"rate_limited"}"""
            ).addHeader("Retry-After", "30")
        }
        withSlackServer(route) { _ =>
            Abort.run[SlackException](Slack.chatPostMessage(SlackMessage(SlackId.ChannelId("C1"), "hi"))).map { result =>
                result match
                    case Result.Failure(ex: SlackRateLimitException) => assert(ex.retryAfter == 30.seconds)
                    case other                                       => assert(false, s"expected SlackRateLimitException, got: $other")
            }
        }
    }

end SlackWebApiLiveTest
