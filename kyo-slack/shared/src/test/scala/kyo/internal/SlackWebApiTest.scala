package kyo.internal

import kyo.*

/** Cross-platform Web API tests with real values and real logic, no mocks. The
  * canonical response-mapping (`mapResponse`) and typed-decode (`decodeOut`) are
  * exercised with real Slack response shapes; the request-body encoding asserts the
  * real Slack snake_case wire keys appear on the outbound DTOs (channel/text/blocks/
  * thread_ts, and the views `view` object with type/callback_id/blocks/title), never
  * the public camelCase (`blocksJson`/`threadTs`). The HTTP transport itself (429
  * header parsing over a live socket, ok:true/ok:false over the wire) is the JVM live
  * test; here the mapping is driven directly with real decoded values.
  */
class SlackWebApiTest extends kyo.test.Test[Any]:

    // The canonical request path: no ambient token bound aborts a typed handshake error.

    "request with no ambient token aborts SlackHandshakeException" in {
        Abort.run[SlackException](SlackWebApi.request[Slack.EmptyBody, Slack.TsResp]("chat.postMessage", Slack.EmptyBody())).map { result =>
            result match
                case Result.Failure(ex: SlackException.SlackHandshakeException) =>
                    assert(ex.getMessage.contains("no Slack connection bound"))
                case other => assert(false, s"expected SlackHandshakeException, got: $other")
        }
    }

    // Response mapping: ok:true returns the decoded typed Out.

    "ok:true returns the decoded Out" in {
        val raw = """{"ok":true,"ts":"1.2"}"""
        Abort.run[SlackException](
            SlackWebApi.mapResponse[Slack.TsResp](
                HttpStatus.OK,
                Absent,
                SlackWebApi.SlackOk(ok = true),
                Present(raw),
                "chat.postMessage"
            )
        ).map { result =>
            assert(result.getOrThrow.ts == "1.2")
        }
    }

    // Response mapping: ok:false aborts a typed Web API error carrying the Slack error code.

    "ok:false aborts SlackWebApiException carrying the error code; message holds no token" in {
        val raw = """{"ok":false,"error":"channel_not_found"}"""
        Abort.run[SlackException](
            SlackWebApi.mapResponse[Slack.TsResp](
                HttpStatus.OK,
                Absent,
                SlackWebApi.SlackOk(ok = false, error = Present("channel_not_found")),
                Present(raw),
                "chat.postMessage"
            )
        ).map { result =>
            result match
                case Result.Failure(ex: SlackException.SlackWebApiException) =>
                    assert(ex.error == "channel_not_found")
                    assert(!ex.getMessage.contains("xoxb"), s"message must not render a token, got: ${ex.getMessage}")
                case other => assert(false, s"expected SlackWebApiException, got: $other")
        }
    }

    // Response mapping: HTTP 429 aborts a typed rate-limit error with the parsed Retry-After.

    "HTTP 429 aborts SlackRateLimitException with the parsed Retry-After" in {
        Abort.run[SlackException](
            SlackWebApi.mapResponse[Slack.TsResp](
                HttpStatus.TooManyRequests,
                Present("30"),
                SlackWebApi.SlackOk(ok = false),
                Absent,
                "chat.postMessage"
            )
        ).map { result =>
            result match
                case Result.Failure(ex: SlackException.SlackRateLimitException) =>
                    assert(ex.retryAfter == 30.seconds)
                case other => assert(false, s"expected SlackRateLimitException, got: $other")
        }
    }

    "HTTP 429 with no Retry-After header defaults to a one-second backoff" in {
        Abort.run[SlackException](
            SlackWebApi.mapResponse[Slack.TsResp](
                HttpStatus.TooManyRequests,
                Absent,
                SlackWebApi.SlackOk(ok = false),
                Absent,
                "chat.postMessage"
            )
        ).map { result =>
            result match
                case Result.Failure(ex: SlackException.SlackRateLimitException) => assert(ex.retryAfter == 1.second)
                case other => assert(false, s"expected SlackRateLimitException, got: $other")
        }
    }

    // A transport HttpException is recovered into a typed SlackTransportException: with a
    // bound token and a base url pointed at an unreachable host, the failure surfaces as a
    // typed SlackTransportException with no HttpException leaking the effect row.

    "a transport HttpException is recovered into SlackTransportException" in {
        SlackWebApi.local.let(Present(SlackToken.Bot("xoxb-1"))) {
            SlackWebApi.baseUrl.let("http://127.0.0.1:1/api") {
                Abort.run[SlackException](
                    SlackWebApi.request[Slack.EmptyBody, Slack.TsResp]("chat.postMessage", Slack.EmptyBody())
                ).map { result =>
                    result match
                        case Result.Failure(_: SlackException.SlackTransportException) => assert(true)
                        case other => assert(false, s"expected SlackTransportException, got: $other")
                }
            }
        }
    }

    // postResponseUrl recovers transport errors into a typed SlackTransportException.

    "postResponseUrl recovers transport errors into SlackTransportException" in {
        Abort.run[SlackException](
            SlackWebApi.postResponseUrl("http://127.0.0.1:1/hooks/x", SlackMessage(SlackId.ChannelId("C1"), "u"))
        ).map { result =>
            result match
                case Result.Failure(_: SlackException.SlackTransportException) => assert(true)
                case other => assert(false, s"expected SlackTransportException, got: $other")
        }
    }

    // chatPostMessage decodes the posted ts from a real ok:true response body.

    "chatPostMessage maps SlackMessage to a real ts via decodeOut" in {
        val raw = """{"ok":true,"ts":"1.55"}"""
        Abort.run[SlackException](SlackWebApi.decodeOut[Slack.TsResp](Present(raw), "chat.postMessage")).map { result =>
            assert(result.getOrThrow.ts == "1.55")
        }
    }

    // auth.test decodes the snake_case identity fields from a real response body.

    "auth.test decodes the snake_case identity fields" in {
        val raw = """{"ok":true,"user_id":"U1","team_id":"T1","bot_id":"B1","url":"https://x.slack.com"}"""
        Abort.run[SlackException](SlackWebApi.decodeOut[Slack.AuthTestResp](Present(raw), "auth.test")).map { result =>
            val r = result.getOrThrow
            assert(r.user_id == Present("U1"))
            assert(r.team_id == Present("T1"))
            assert(r.bot_id == Present("B1"))
            assert(r.url == Present("https://x.slack.com"))
        }
    }

    // views.* decode the opened view id from a real {view:{id}} response body.

    "views.* decode the opened view id from {view:{id}}" in {
        val raw = """{"ok":true,"view":{"id":"V1"}}"""
        Abort.run[SlackException](SlackWebApi.decodeOut[Slack.ViewResp](Present(raw), "views.open")).map { result =>
            assert(result.getOrThrow.view.id == "V1")
        }
    }

    // The one structural-decode site: custom decodes a typed Out, or surfaces a typed
    // decode failure when the response shape does not match.

    "custom decodes a typed Out on ok:true" in {
        val raw = """{"ok":true,"members":["U1","U2"]}"""
        Abort.run[SlackException](
            SlackWebApi.mapResponse[Members](HttpStatus.OK, Absent, SlackWebApi.SlackOk(ok = true), Present(raw), "users.list")
        ).map { result =>
            assert(result.getOrThrow == Members(Chunk("U1", "U2")))
        }
    }

    "custom surfaces SlackDecodeException when Out does not decode" in {
        val raw = """{"ok":true,"unexpected":"shape"}"""
        Abort.run[SlackException](
            SlackWebApi.mapResponse[Members](HttpStatus.OK, Absent, SlackWebApi.SlackOk(ok = true), Present(raw), "users.list")
        ).map { result =>
            result match
                case Result.Failure(_: SlackException.SlackDecodeException) => assert(true)
                case other                                                  => assert(false, s"expected SlackDecodeException, got: $other")
        }
    }

    "decodeOut with an absent body surfaces SlackDecodeException" in {
        Abort.run[SlackException](SlackWebApi.decodeOut[Slack.TsResp](Absent, "chat.postMessage")).map { result =>
            result match
                case Result.Failure(_: SlackException.SlackDecodeException) => assert(true)
                case other                                                  => assert(false, s"expected SlackDecodeException, got: $other")
        }
    }

    // Request-body encoding: the outbound DTOs carry the real Slack snake_case wire keys.

    "chatPostMessage body encodes the real Slack keys: channel/text/blocks (array)/thread_ts, never camelCase" in {
        val message = SlackMessage(
            SlackId.ChannelId("C1"),
            "hi",
            blocksJson = Present("""[{"type":"section","text":{"type":"mrkdwn","text":"x"}}]"""),
            threadTs = Present(SlackId.Ts("1.0"))
        )
        Slack.parseBlocks(message.blocksJson).map { blocks =>
            val body = Slack.PostMessageBody(message.channel, message.text, blocks, message.threadTs)
            val json = Json.encode(body)
            assert(json.contains("\"channel\""), s"missing channel key: $json")
            assert(json.contains("\"text\""), s"missing text key: $json")
            assert(json.contains("\"blocks\""), s"missing blocks key: $json")
            assert(json.contains("\"thread_ts\""), s"missing thread_ts key: $json")
            // blocks splices as a native JSON array, not a quoted string and not blocks_json.
            assert(json.contains("\"blocks\":["), s"blocks must be a JSON array: $json")
            assert(!json.contains("blocksJson"), s"must not leak camelCase blocksJson: $json")
            assert(!json.contains("blocks_json"), s"blocks_json is the wrong key: $json")
            assert(!json.contains("threadTs"), s"must not leak camelCase threadTs: $json")
            assert(json.contains("\"type\":\"section\""), s"raw block content must splice inline: $json")
        }
    }

    "views body encodes view:{type,callback_id,blocks,title}, never camelCase" in {
        val view = SlackView(
            SlackView.Type.Modal,
            callbackId = Present("cb1"),
            blocksJson = """[{"type":"input"}]""",
            titleJson = Present("""{"type":"plain_text","text":"T"}""")
        )
        Slack.encodeView(view).map { v =>
            val body = Slack.ViewsOpenBody(SlackId.TriggerId("T1"), v)
            val json = Json.encode(body)
            assert(json.contains("\"trigger_id\""), s"missing trigger_id key: $json")
            assert(json.contains("\"view\""), s"missing view key: $json")
            assert(json.contains("\"type\":\"modal\""), s"missing view type modal: $json")
            assert(json.contains("\"callback_id\""), s"missing callback_id key: $json")
            assert(json.contains("\"blocks\":["), s"view blocks must be a JSON array: $json")
            assert(json.contains("\"title\""), s"missing title key: $json")
            assert(!json.contains("blocksJson"), s"must not leak camelCase blocksJson: $json")
            assert(!json.contains("titleJson"), s"must not leak camelCase titleJson: $json")
            assert(!json.contains("callbackId"), s"must not leak camelCase callbackId: $json")
        }
    }

    "ephemeral and update bodies encode the real Slack keys" in {
        val message = SlackMessage(SlackId.ChannelId("C1"), "hi", threadTs = Present(SlackId.Ts("1.0")))
        Slack.parseBlocks(message.blocksJson).map { blocks =>
            val ephemeral = Json.encode(Slack.EphemeralBody(message.channel, SlackId.UserId("U1"), message.text, blocks, message.threadTs))
            assert(
                ephemeral.contains("\"channel\"") && ephemeral.contains("\"user\"") && ephemeral.contains("\"text\""),
                s"ephemeral keys: $ephemeral"
            )
            assert(ephemeral.contains("\"thread_ts\""), s"ephemeral thread_ts: $ephemeral")
            assert(!ephemeral.contains("threadTs"), s"ephemeral camelCase leak: $ephemeral")

            val update = Json.encode(Slack.UpdateBody(message.channel, SlackId.Ts("1.0"), message.text, blocks))
            assert(update.contains("\"channel\"") && update.contains("\"ts\"") && update.contains("\"text\""), s"update keys: $update")
            assert(!update.contains("blocks_json"), s"update must not carry blocks_json: $update")
        }
    }

    "a malformed Block Kit blocks string surfaces SlackDecodeException" in {
        Abort.run[SlackException](Slack.parseBlocks(Present("not json"))).map { result =>
            result match
                case Result.Failure(_: SlackException.SlackDecodeException) => assert(true)
                case other => assert(false, s"expected SlackDecodeException for malformed blocks, got: $other")
        }
    }

    private case class Members(members: Chunk[String]) derives Schema, CanEqual

end SlackWebApiTest
