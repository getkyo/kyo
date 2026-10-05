package kyo.internal.slack

import kyo.*
import kyo.SlackLiterals.*
import kyo.internal.charset.Utf8

/** Web API tests against a local kyo-http server playing Slack. Every failure leaf is produced by
  * the real request path and asserted by value; a wrapped cause stays out of a leaf's equality, so an
  * expected leaf is built with a placeholder cause.
  */
class WebApiTest extends kyo.test.Test[Any]:

    private val bot = botOf("xoxb-test")

    /** The connection refused at 127.0.0.1:1, the address every unreachable scenario uses. */
    private def refused(method: String)(using Frame): SlackException =
        SlackTransportException(method, SlackTransportException.Kind.Connect, "127.0.0.1", 1, Absent)(Absent)

    private def decode(
        method: String,
        part: SlackDecodeException.Part,
        failure: SlackDecodeException.Failure,
        path: Chunk[String] = Chunk.empty,
        position: Maybe[Int] = Absent
    )(using Frame): SlackException =
        SlackDecodeException(method, part, failure, path, position)

    private case class OkResp(ok: Boolean) derives Schema

    private case class Members(members: Chunk[String]) derives Schema, CanEqual

    private case class NoArgs() derives Schema

    /** What the local server answers next: a status, a body, and an optional `Retry-After`. */
    private case class Reply(status: HttpStatus, body: String, retryAfter: Maybe[String] = Absent)

    private val methods =
        Chunk(
            "apps.connections.open",
            "auth.test",
            "chat.postMessage",
            "chat.postEphemeral",
            "chat.update",
            "views.open",
            "views.update",
            "views.publish",
            "users.list"
        )

    private val message     = SlackMessage(SlackId.ChannelId("C1"), "hi")
    private val view        = SlackView(SlackView.Type.Modal, title = Present("T"))
    private val slackConfig = configOf(appLevelOf("xapp-test"), bot)

    private def configAt(base: String, config: SlackConfig = slackConfig)(using Frame): SlackConfig =
        valid(config.baseUrl(urlOf(base)))

    /** The verbs on `config`, each run on a client `Slack.run` builds for that call, so every call takes the real path
      * from the provided client to Slack's answer.
      */
    final private class Verbs(val config: SlackConfig):
        def identity(using Frame)                                          = Slack.run(config)(Slack.identity)
        def send(m: SlackMessage)(using Frame)                             = Slack.run(config)(Slack.send(m))
        def sendEphemeral(m: SlackMessage, u: SlackId.UserId)(using Frame) =
            Slack.run(config)(Slack.sendEphemeral(m, u))
        def edit(c: SlackId.ChannelId, t: SlackTs, m: SlackMessage)(using Frame)   = Slack.run(config)(Slack.edit(c, t, m))
        def openView(t: SlackId.TriggerId, v: SlackView)(using Frame)              = Slack.run(config)(Slack.openView(t, v))
        def updateView(i: SlackId.ViewId, v: SlackView)(using Frame)               = Slack.run(config)(Slack.updateView(i, v))
        def publishView(u: SlackId.UserId, v: SlackView)(using Frame)              = Slack.run(config)(Slack.publishView(u, v))
        def custom[In: Schema, Out: Schema](method: String, body: In)(using Frame) =
            Slack.run(config)(Slack.custom[In, Out](methodOf(method), body))
        def connectionsOpen(using Frame)                                      = Slack.run(config)(Env.get[Slack].map(Slack.openEngine(_)))
        def respondEphemeral(u: SlackResponseUrl, r: SlackReply)(using Frame) = Slack.run(config)(Slack.respondEphemeral(u, r))
        def respondInChannel(u: SlackResponseUrl, r: SlackReply, t: Maybe[SlackTs] = Absent)(using Frame) =
            Slack.run(config)(Slack.respondInChannel(u, r, t))
        def replaceOriginal(u: SlackResponseUrl, r: SlackReply)(using Frame) = Slack.run(config)(Slack.replaceOriginal(u, r))
        def deleteOriginal(u: SlackResponseUrl)(using Frame)                 = Slack.run(config)(Slack.deleteOriginal(u))
    end Verbs

    /** A local Slack whose every method answers the current `Reply`, and the verbs on it. */
    private def withScriptedSlack[A](test: (AtomicRef[Reply], Verbs) => A < (Async & Abort[SlackException | HttpException] & Scope))(
        using Frame
    ): A < (Async & Abort[SlackException | HttpException] & Scope) =
        AtomicRef.init(Reply(HttpStatus.OK, """{"ok":true}""")).map { reply =>
            val routes = methods.map { m =>
                HttpRoute.postRaw(m).response(_.bodyText).handler { _ =>
                    reply.get.map { r =>
                        val response = HttpResponse(r.status).addField("body", r.body)
                        r.retryAfter match
                            case Present(v) => response.addHeader("Retry-After", v)
                            case Absent     => response
                    }
                }
            }
            HttpServer.init(0, "127.0.0.1")(routes*).map(server => test(reply, Verbs(configAt(s"http://127.0.0.1:${server.port}"))))
        }

    /** The verbs on the default config, for the `response_url` operations, which never reach the base url. */
    private def withSlack[A](test: Verbs => A)(using Frame): A = test(Verbs(slackConfig))

    /** The verbs on the local Slack at `base`. */
    private def withSlackAt[A](base: String, config: SlackConfig = slackConfig)(test: Verbs => A)(using Frame): A =
        test(Verbs(configAt(base, config)))

    /** Each operation by the method name its failures carry, as a call returning nothing. */
    private def operations(slack: Verbs)(using Frame): Chunk[(String, Unit < (Async & Abort[SlackException]))] =
        Chunk(
            "apps.connections.open" -> slack.connectionsOpen.unit,
            "auth.test"             -> slack.identity.unit,
            "chat.postMessage"      -> slack.send(message).unit,
            "chat.postEphemeral"    -> slack.sendEphemeral(message, SlackId.UserId("U1")).unit,
            "chat.update"           -> slack.edit(SlackId.ChannelId("C1"), SlackTs("1.0"), message).unit,
            "views.open"            -> slack.openView(SlackId.TriggerId("T1"), view).unit,
            "views.update"          -> slack.updateView(SlackId.ViewId("V1"), view).unit,
            "views.publish"         -> slack.publishView(SlackId.UserId("U1"), view).unit,
            "users.list"            -> slack.custom[NoArgs, Members]("users.list", NoArgs()).unit
        )

    private def call(op: Unit < (Async & Abort[SlackException]))(using Frame): Result[SlackException, Unit] < Async =
        Abort.run[SlackException](op)

    // The codes each operation maps to a leaf of its own, from each method's Slack reference page. A code
    // outside an operation's list is SlackOtherApiException. Both rate-limit codes name the rate-limit leaf, which is on every row.

    private val credential =
        Chunk(
            "invalid_auth",
            "not_authed",
            "token_revoked",
            "token_expired",
            "account_inactive",
            "not_allowed_token_type",
            "missing_scope",
            "ratelimited",
            "rate_limited"
        )

    private val tables: Map[String, Chunk[String]] = Map(
        "apps.connections.open" -> credential,
        "auth.test"             -> credential,
        "users.list"            -> (credential :+ "invalid_arguments"),
        "chat.postMessage"      ->
            (credential ++ Chunk(
                "invalid_arguments",
                "channel_not_found",
                "not_in_channel",
                "is_archived",
                "no_text",
                "invalid_blocks",
                "invalid_blocks_format",
                "cannot_reply_to_message",
                "msg_blocks_too_long"
            )),
        "chat.postEphemeral" ->
            (credential ++ Chunk(
                "invalid_arguments",
                "channel_not_found",
                "not_in_channel",
                "is_archived",
                "user_not_in_channel",
                "no_text",
                "msg_too_long",
                "invalid_blocks",
                "invalid_blocks_format",
                "cannot_reply_to_message"
            )),
        "chat.update" ->
            (credential ++ Chunk(
                "invalid_arguments",
                "channel_not_found",
                "no_text",
                "msg_too_long",
                "invalid_blocks",
                "invalid_blocks_format",
                "message_not_found",
                "cant_update_message"
            )),
        "views.open" ->
            (credential ++ Chunk(
                "invalid_arguments",
                "expired_trigger_id",
                "exchanged_trigger_id",
                "invalid_trigger_id",
                "view_too_large"
            )),
        "views.update"  -> (credential ++ Chunk("invalid_arguments", "view_too_large", "not_found")),
        "views.publish" -> (credential ++ Chunk("invalid_arguments", "view_too_large"))
    )

    /** Codes an operation does not list, each of which must fall to `SlackOtherApiException`. */
    private val offTable: Map[String, Chunk[String]] = Map(
        "apps.connections.open" -> Chunk("missing_args", "invalid_arguments", "channel_not_found"),
        "auth.test"             -> Chunk("missing_args", "invalid_arguments", "fatal_error"),
        "users.list"            -> Chunk("missing_args", "channel_not_found", "team_not_found"),
        "chat.postMessage"      -> Chunk("missing_args", "msg_too_long", "user_not_in_channel"),
        "chat.postEphemeral"    -> Chunk("missing_args", "msg_blocks_too_long", "fatal_error"),
        "chat.update"           -> Chunk("missing_args", "not_in_channel", "is_archived"),
        "views.open"            -> Chunk("missing_args", "not_found", "channel_not_found"),
        "views.update"          -> Chunk("missing_args", "expired_trigger_id", "invalid_trigger_id"),
        "views.publish"         -> Chunk("missing_args", "not_found", "expired_trigger_id")
    )

    private val slackMessages = Chunk("[ERROR] first", "[ERROR] second")

    private def failureBody(code: String): String =
        s"""{"ok":false,"error":"$code","needed":"chat:write","provided":"channels:read, users:read","response_metadata":{"messages":["[ERROR] first","[ERROR] second"]}}"""

    private def leafFor(method: String, code: String)(using Frame): SlackException =
        val m = slackMessages
        code match
            case "ratelimited" | "rate_limited" => SlackRateLimitException(method, Present(7.seconds))
            case "invalid_auth"                 => SlackInvalidAuthException(method, m)
            case "not_authed"                   => SlackNotAuthedException(method, m)
            case "token_revoked"                => SlackTokenRevokedException(method, m)
            case "token_expired"                => SlackTokenExpiredException(method, m)
            case "account_inactive"             => SlackAccountInactiveException(method, m)
            case "not_allowed_token_type"       => SlackNotAllowedTokenTypeException(method, m)
            case "missing_scope"         => SlackMissingScopeException(method, Chunk("chat:write"), Chunk("channels:read", "users:read"), m)
            case "invalid_arguments"     => SlackInvalidArgumentsException(method, m)
            case "channel_not_found"     => SlackChannelNotFoundException(method, m)
            case "not_in_channel"        => SlackNotInChannelException(method, m)
            case "is_archived"           => SlackIsArchivedException(method, m)
            case "user_not_in_channel"   => SlackUserNotInChannelException(method, m)
            case "no_text"               => SlackNoTextException(method, m)
            case "msg_too_long"          => SlackMsgTooLongException(method, m)
            case "msg_blocks_too_long"   => SlackMsgBlocksTooLongException(method, m)
            case "invalid_blocks"        => SlackInvalidBlocksException(method, m)
            case "invalid_blocks_format" => SlackInvalidBlocksFormatException(method, m)
            case "cannot_reply_to_message" => SlackCannotReplyToMessageException(method, m)
            case "message_not_found"       => SlackMessageNotFoundException(method, m)
            case "cant_update_message"     => SlackCantUpdateMessageException(method, m)
            case "expired_trigger_id"      => SlackExpiredTriggerIdException(method, m)
            case "exchanged_trigger_id"    => SlackExchangedTriggerIdException(method, m)
            case "invalid_trigger_id"      => SlackInvalidTriggerIdException(method, m)
            case "view_too_large"          => SlackViewTooLargeException(method, m)
            case "not_found"               => SlackNotFoundException(method, m)
            case other                     => SlackOtherApiException(method, other, m)
        end match
    end leafFor

    "every code in an operation's table maps to its own leaf, with Slack's messages and scopes" in {
        withScriptedSlack { (reply, slack) =>
            val cases = operations(slack).flatMap { case (method, op) => tables(method).map(code => (method, code, op)) }
            Kyo.foreach(cases) { case (method, code, op) =>
                reply.set(Reply(HttpStatus.OK, failureBody(code), Present("7"))).andThen(call(op))
            }.map { results =>
                assert(results == cases.map { case (method, code, _) => Result.fail(leafFor(method, code)) })
            }
        }
    }

    "a code outside an operation's table is SlackOtherApiException carrying the code" in {
        withScriptedSlack { (reply, slack) =>
            val cases = operations(slack).flatMap { case (method, op) => offTable(method).map(code => (method, code, op)) }
            Kyo.foreach(cases) { case (method, code, op) =>
                reply.set(Reply(HttpStatus.OK, failureBody(code))).andThen(call(op))
            }.map { results =>
                assert(results == cases.map { case (method, code, _) =>
                    Result.fail(SlackOtherApiException(method, code, slackMessages))
                })
            }
        }
    }

    "an ok:false answer with no scope lists or messages carries empty chunks" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.OK, """{"ok":false,"error":"missing_scope"}""")).andThen(call(slack.send(message).unit))
                .map { result =>
                    assert(result == Result.fail(SlackMissingScopeException("chat.postMessage", Chunk.empty, Chunk.empty, Chunk.empty)))
                }
        }
    }

    "an ok:false answer on a non-2xx status is still Slack's code, not an unexpected status" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.InternalServerError, """{"ok":false,"error":"fatal_error"}""")).andThen(
                call(slack.send(message).unit)
            ).map { result =>
                assert(result == Result.fail(SlackOtherApiException("chat.postMessage", "fatal_error", Chunk.empty)))
            }
        }
    }

    "HTTP 429 is a rate limit carrying Slack's Retry-After, or Absent when Slack sent none it could mean" in {
        withScriptedSlack { (reply, slack) =>
            val retryAfters =
                Chunk(Present("30"), Present("0"), Absent, Present("soon"), Present("-5"), Present("Wed, 21 Oct 2026 07:28:00 GMT"))
            Kyo.foreach(retryAfters) { header =>
                reply.set(Reply(HttpStatus.TooManyRequests, """{"ok":false,"error":"ratelimited"}""", header))
                    .andThen(call(slack.send(message).unit))
            }.map { results =>
                assert(results == Chunk(Present(30.seconds), Present(Duration.Zero), Absent, Absent, Absent, Absent).map(d =>
                    Result.fail(SlackRateLimitException("chat.postMessage", d))
                ))
            }
        }
    }

    "HTTP 429 with a non-Slack body is still a rate limit" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.TooManyRequests, "<html>slow down</html>", Present("3"))).andThen(call(slack.identity.unit)).map {
                result =>
                    assert(result == Result.fail(SlackRateLimitException("auth.test", Present(3.seconds))))
            }
        }
    }

    "HTTP 429 with no Retry-After header reports no delay rather than inventing one" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.TooManyRequests, """{"ok":false,"error":"ratelimited"}""")).andThen(
                call(slack.send(message).unit)
            ).map { result =>
                assert(result == Result.fail(SlackRateLimitException("chat.postMessage", Absent)))
            }
        }
    }

    "a 2xx body with no ok field is a decode failure, not an invented error code" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.OK, """{"channel":"C1"}""")).andThen(call(slack.send(message).unit)).map { result =>
                assert(result == Result.fail(decode(
                    "chat.postMessage",
                    SlackDecodeException.Part.Envelope,
                    SlackDecodeException.Failure.MissingField,
                    Chunk("ok")
                )))
            }
        }
    }

    "a decode failure holds no byte of Slack's answer in its message, toString or cause chain" in {
        val plantedBodySecret = "PLANTED-BODY-SECRET"
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.OK, s"""{"ok":true,"channel":$plantedBodySecret}""")).andThen(
                call(slack.send(message).unit)
            ).map { result =>
                assert(result == Result.fail(decode(
                    "chat.postMessage",
                    SlackDecodeException.Part.Envelope,
                    SlackDecodeException.Failure.Parse,
                    position = Present(21)
                )))
                val rendered = result.failure.toList.flatMap { e =>
                    Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).flatMap(t => Seq(t.getMessage, t.toString)).toList
                }
                assert(rendered.forall(!_.contains(plantedBodySecret)))
            }
        }
    }

    "an ok:false body with no error field is a decode failure, not the code unknown_error" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.OK, """{"ok":false}""")).andThen(call(slack.send(message).unit)).map { result =>
                assert(result == Result.fail(decode(
                    "chat.postMessage",
                    SlackDecodeException.Part.Envelope,
                    SlackDecodeException.Failure.MissingField,
                    Chunk("error")
                )))
            }
        }
    }

    "a 2xx body that is not JSON, or is empty, is an envelope decode failure" in {
        withScriptedSlack { (reply, slack) =>
            Kyo.foreach(Chunk("not json", ""))(body => reply.set(Reply(HttpStatus.OK, body)).andThen(call(slack.identity.unit))).map {
                results =>
                    assert(
                        results == Chunk(
                            Result.fail(decode(
                                "auth.test",
                                SlackDecodeException.Part.Envelope,
                                SlackDecodeException.Failure.Parse,
                                position = Present(0)
                            )),
                            Result.fail(decode(
                                "auth.test",
                                SlackDecodeException.Part.Envelope,
                                SlackDecodeException.Failure.TruncatedInput,
                                position = Absent
                            ))
                        ),
                        results.toString
                    )
            }
        }
    }

    Chunk("user_id", "team_id", "bot_id", "url").foreach { field =>
        s"an auth.test answer without $field is a payload decode failure naming that field" in {
            val fields = Chunk("user_id" -> "U1", "team_id" -> "T1", "bot_id" -> "B1", "url" -> "https://x.slack.com")
            val body   = fields.filter(_._1 != field).map((k, v) => s""""$k":"$v"""").mkString("""{"ok":true,""", ",", "}")
            withScriptedSlack { (reply, slack) =>
                reply.set(Reply(HttpStatus.OK, body)).andThen(call(slack.identity.unit)).map { result =>
                    assert(result == Result.fail(decode(
                        "auth.test",
                        SlackDecodeException.Part.Payload,
                        SlackDecodeException.Failure.MissingField,
                        Chunk(field)
                    )))
                }
            }
        }
    }

    Chunk("user_id", "team_id", "bot_id").foreach { field =>
        s"an auth.test answer with an empty $field is a payload decode failure at that field" in {
            val fields = Chunk("user_id" -> "U1", "team_id" -> "T1", "bot_id" -> "B1", "url" -> "https://x.slack.com")
            val body   = fields.map((k, v) => s""""$k":"${if k == field then "" else v}"""").mkString("""{"ok":true,""", ",", "}")
            withScriptedSlack { (reply, slack) =>
                reply.set(Reply(HttpStatus.OK, body)).andThen(call(slack.identity.unit)).map { result =>
                    assert(result == Result.fail(SlackDecodeException(
                        "auth.test",
                        SlackDecodeException.Part.Payload,
                        SlackDecodeException.Failure.ConstructorRejected,
                        Chunk(field),
                        Absent
                    )))
                }
            }
        }
    }

    "an auth.test answer whose url is not an absolute http or https url is a payload decode failure naming url" in {
        val urls = Chunk("", "not a url", "/relative", "ftp://x.slack.com", "http+unix://%2Ftmp%2Fs/")
        withScriptedSlack { (reply, slack) =>
            Kyo.foreach(urls) { url =>
                val body = s"""{"ok":true,"user_id":"U1","team_id":"T1","bot_id":"B1","url":"$url"}"""
                reply.set(Reply(HttpStatus.OK, body)).andThen(call(slack.identity.unit))
            }.map { results =>
                val rejected =
                    decode("auth.test", SlackDecodeException.Part.Payload, SlackDecodeException.Failure.ConstructorRejected, Chunk("url"))
                assert(results == Chunk.fill(urls.size)(Result.fail(rejected)), results.toString)
            }
        }
    }

    "an ok:true body whose result does not decode is a payload decode failure naming kyo-schema's leaf and the field" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.OK, """{"ok":true,"unexpected":"shape"}""")).andThen(
                call(slack.custom[NoArgs, Members]("users.list", NoArgs()).unit)
            ).map { result =>
                assert(result == Result.fail(decode(
                    "users.list",
                    SlackDecodeException.Part.Payload,
                    SlackDecodeException.Failure.MissingField,
                    Chunk("members")
                )))
            }
        }
    }

    "chat.postEphemeral answers message_ts, not ts, and decodes it" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.OK, """{"ok":true,"message_ts":"1.55"}""")).andThen(
                slack.sendEphemeral(message, SlackId.UserId("U1"))
            ).map { ts =>
                assert(ts == SlackTs("1.55"))
            }
        }
    }

    "a non-2xx response with no Slack body is an unexpected status, not a Slack API error" in {
        withScriptedSlack { (reply, slack) =>
            val answers = Chunk(
                Reply(HttpStatus.ServiceUnavailable, "<html>upstream unavailable</html>"),
                Reply(HttpStatus.BadGateway, """{"ok":false}"""),
                Reply(HttpStatus.NotFound, "")
            )
            Kyo.foreach(answers)(a => reply.set(a).andThen(call(slack.send(message).unit))).map { results =>
                assert(results == Chunk(
                    Result.fail(SlackUnexpectedStatusException("chat.postMessage", HttpStatus.ServiceUnavailable)),
                    Result.fail(SlackUnexpectedStatusException("chat.postMessage", HttpStatus.BadGateway)),
                    Result.fail(SlackUnexpectedStatusException("chat.postMessage", HttpStatus.NotFound))
                ))
            }
        }
    }

    // RFC 9110 section 15.3: only a 2xx status means the request succeeded; Slack's Web API
    // documentation says nothing about an `ok:true` body on another status.
    "a non-2xx response with an ok:true body is an unexpected status, not a success" in {
        withScriptedSlack { (reply, slack) =>
            val answers = Chunk(
                Reply(HttpStatus.InternalServerError, """{"ok":true,"ts":"1.0"}"""),
                Reply(HttpStatus.NotFound, """{"ok":true}""")
            )
            Kyo.foreach(answers)(a => reply.set(a).andThen(call(slack.send(message).unit))).map { results =>
                assert(
                    results == Chunk(
                        Result.fail(SlackUnexpectedStatusException("chat.postMessage", HttpStatus.InternalServerError)),
                        Result.fail(SlackUnexpectedStatusException("chat.postMessage", HttpStatus.NotFound))
                    ),
                    results.toString
                )
            }
        }
    }

    // RFC 9110 section 10.2.3: `delay-seconds = 1*DIGIT`, and section 5.6.1's DIGIT is ASCII 0-9.
    // RFC 9110 section 5.5: a field value carries no leading or trailing whitespace, and RFC 9112
    // section 5 lets OWS (SP and HTAB) surround it on the wire, so only SP and HTAB are stripped.
    "Retry-After is whole ASCII seconds, possibly surrounded by SP or HTAB" in {
        val parsed = Chunk(
            "0",
            "30",
            " 30\t",
            "+5",
            "-5",
            "٣٠",
            "\u000b30",
            "3 0",
            "",
            "9223372036",
            "9223372037",
            "9223372036854775807",
            "99999999999999999999999"
        ).map(WebApi.parseRetryAfter)
        assert(
            parsed == Chunk(
                Present(Duration.Zero),
                Present(30.seconds),
                Present(30.seconds),
                Absent,
                Absent,
                Absent,
                Absent,
                Absent,
                Absent,
                Present(9223372036L.seconds),
                Present(Duration.Infinity),
                Present(Duration.Infinity),
                Present(Duration.Infinity)
            ),
            parsed.toString
        )
    }

    "apps.connections.open answering ok without a url is a payload decode failure at url" in {
        withScriptedSlack { (reply, slack) =>
            reply.set(Reply(HttpStatus.OK, """{"ok":true}""")).andThen(call(slack.connectionsOpen.unit)).map { result =>
                assert(result == Result.fail(SlackDecodeException(
                    "apps.connections.open",
                    SlackDecodeException.Part.Payload,
                    SlackDecodeException.Failure.MissingField,
                    Chunk("url"),
                    Absent
                )))
            }
        }
    }

    "ok:true answers decode each operation's result" in {
        withScriptedSlack { (reply, slack) =>
            for
                identity <- reply.set(Reply(
                    HttpStatus.OK,
                    """{"ok":true,"user_id":"U1","team_id":"T1","bot_id":"B1","url":"https://x.slack.com"}"""
                )).andThen(slack.identity)
                posted  <- reply.set(Reply(HttpStatus.OK, """{"ok":true,"ts":"1.99"}""")).andThen(slack.send(message))
                updated <- reply.set(Reply(HttpStatus.OK, """{"ok":true,"ts":"2.0"}""")).andThen(
                    slack.edit(SlackId.ChannelId("C1"), SlackTs("1.99"), message)
                )
                opened <- reply.set(Reply(HttpStatus.OK, """{"ok":true,"view":{"id":"V1"}}""")).andThen(slack.openView(
                    SlackId.TriggerId("T1"),
                    view
                ))
                members <- reply.set(Reply(HttpStatus.OK, """{"ok":true,"members":["U1","U2"]}""")).andThen(
                    slack.custom[NoArgs, Members]("users.list", NoArgs())
                )
            yield
                assert(identity == Slack.Identity(
                    SlackId.UserId("U1"),
                    SlackId.TeamId("T1"),
                    SlackId.BotId("B1"),
                    urlOf("https://x.slack.com")
                ))
                assert(posted == SlackTs("1.99"))
                assert(updated == SlackTs("2.0"))
                assert(opened == SlackId.ViewId("V1"))
                assert(members == Members(Chunk("U1", "U2")))
            end for
        }
    }

    "a transport failure on a Web API call is the transport leaf naming the method, the host and port, and the kyo-net cause" in {
        withSlackAt("http://127.0.0.1:1/api") { slack =>
            call(slack.send(message).unit).map { result =>
                assert(result == Result.fail(refused("chat.postMessage")))
                val cause = result.failure.collect { case t: SlackTransportException => t.cause.map(_.getClass.getSimpleName) }
                assert(cause == Present(Present("NetConnectException")), cause.toString)
            }
        }
    }

    "a transport failure on apps.connections.open is the transport leaf naming that method" in {
        withSlackAt("http://127.0.0.1:1/api") { slack =>
            call(slack.connectionsOpen.unit).map(result => assert(result == Result.fail(refused("apps.connections.open"))))
        }
    }

    "a client that cannot reach Slack is never built: init fails with the connection's transport leaf" in {
        call(Scope.run(Slack.init(configAt("http://127.0.0.1:1/api")).unit)).map { result =>
            assert(result == Result.fail(refused("apps.connections.open")))
        }
    }

    "the caller's kyo-http client and config do not reach a request: a closed caller client and a caller filter are not used" in {
        for
            seen <- AtomicInt.init(0)
            spy = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2, S](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt]) =
                    seen.incrementAndGet.andThen(next(request))
            route = HttpRoute.postRaw("chat.postMessage").response(_.bodyText).handler { _ =>
                HttpResponse(HttpStatus.OK).addField("body", """{"ok":true,"ts":"1.0"}""")
            }
            server <- HttpServer.init(0, "127.0.0.1")(route)
            base = s"http://127.0.0.1:${server.port}"
            callers <- HttpClient.initUnscoped()
            _       <- callers.closeNow
            // The probe can fail: the same request through the caller's closed client does.
            direct      <- Abort.run[HttpException](HttpClient.let(callers)(HttpClient.postText(s"$base/chat.postMessage", "{}")))
            unreachable <- Abort.get(HttpClientConfig.BaseUrl.init("http://127.0.0.1:1"))
            posted      <- HttpClient.let(callers) {
                HttpClient.withConfig(_.filter(spy).baseUrl(unreachable).followRedirects(true)) {
                    withSlackAt(base)(slack => Abort.run[SlackException](slack.send(message)))
                }
            }
            count <- seen.get
        yield
            assert(direct.isFailure || direct.isPanic, s"a request through the closed caller client did not fail: $direct")
            assert(posted == Result.succeed(SlackTs("1.0")), s"got: $posted")
            assert(count == 0, s"the caller's filter saw $count requests")
        end for
    }

    "a caller's connection opened under trustAll TLS is not reused: the module connects afresh and refuses the certificate" in {
        val trustAll = HttpTlsConfig(trustAll = true)
        val route    = HttpRoute.postRaw("chat.postMessage").response(_.bodyText).handler { _ =>
            HttpResponse(HttpStatus.OK).addField("body", """{"ok":true,"ts":"1.0"}""")
        }
        for
            server <- HttpServer.init(HttpServerConfig.default.port(0).host("localhost").tls(
                kyo.internal.HttpTestPlatformBackend.serverTlsConfig
            ))(route)
            base = s"https://localhost:${server.port}"
            callers <- HttpClient.init(defaultTlsConfig = trustAll)
            direct  <- HttpClient.let(callers)(HttpClient.withConfig(_.tls(trustAll))(HttpClient.postText(s"$base/chat.postMessage", "{}")))
            posted  <- HttpClient.let(callers) {
                HttpClient.withConfig(_.tls(trustAll))(withSlackAt(base)(slack =>
                    Abort.run[SlackException](slack.send(message))
                ))
            }
        yield
            assert(direct == """{"ok":true,"ts":"1.0"}""", "the caller's own trustAll request reached the server")
            posted match
                case Result.Failure(t: SlackTransportException) =>
                    assert(t == SlackTransportException(
                        "chat.postMessage",
                        SlackTransportException.Kind.Tls,
                        "localhost",
                        server.port,
                        Absent
                    )(Absent))
                    assert(t.cause.exists(_.isInstanceOf[kyo.net.NetTlsException]), s"cause: ${t.cause}")
                case other => fail(s"expected the Tls transport leaf, got: $other")
            end match
        end for
    }

    "send sends the real Slack keys and a native blocks array" in {
        AtomicRef.init(Maybe.empty[String]).map { bodyRef =>
            val route = HttpRoute.postRaw("chat.postMessage").request(_.bodyText).response(_.bodyText).handler { req =>
                bodyRef.set(Present(req.fields.body)).andThen(HttpResponse(HttpStatus.OK).addField("body", """{"ok":true,"ts":"1.99"}"""))
            }
            HttpServer.init(0, "127.0.0.1")(route).map { server =>
                withSlackAt(s"http://127.0.0.1:${server.port}") { slack =>
                    val msg = SlackMessage(
                        SlackId.ChannelId("C1"),
                        "hi",
                        threadTs = Present(SlackTs("1.0")),
                        blocks = Chunk(SlackBlock.Section(SlackBlock.Text.Markdown("x")))
                    )
                    slack.send(msg).map { ts =>
                        bodyRef.get.map { sent =>
                            assert(ts == SlackTs("1.99"))
                            assert(sent == Present(
                                """{"channel":"C1","text":"hi","blocks":[{"type":"section","text":{"type":"mrkdwn","text":"x"}}],"thread_ts":"1.0"}"""
                            ))
                        }
                    }
                }
            }
        }
    }

    "views, ephemeral and update bodies encode the real Slack keys" in {
        val v = SlackView(
            SlackView.Type.Modal,
            callbackId = Present("cb1"),
            title = Present("T"),
            blocks = Chunk(SlackBlock.Divider())
        )
        val msg = SlackMessage(SlackId.ChannelId("C1"), "hi", threadTs = Present(SlackTs("1.0")))
        assert(
            Json.encode(Slack.ViewsOpenBody(SlackId.TriggerId("T1"), Slack.encodeView(v))) ==
                """{"trigger_id":"T1","view":{"type":"modal","callback_id":"cb1","blocks":[{"type":"divider"}],"title":{"type":"plain_text","text":"T"}}}"""
        )
        assert(
            Json.encode(Slack.EphemeralBody(msg.channel, SlackId.UserId("U1"), msg.text, Slack.messageBlocks(msg), msg.threadTs)) ==
                """{"channel":"C1","user":"U1","text":"hi","thread_ts":"1.0"}"""
        )
        assert(
            Json.encode(Slack.UpdateBody(msg.channel, SlackTs("1.0"), msg.text, Slack.messageBlocks(msg))) ==
                """{"channel":"C1","ts":"1.0","text":"hi"}"""
        )
    }

    "a caller's filter does not reach a response_url POST: a panicking one never runs" in {
        val defect          = new IllegalStateException("filter defect")
        val panickingFilter = new HttpFilter.Passthrough[Nothing]:
            def apply[In, Out, E2, S](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt]) =
                Abort.panic(defect)
        withSlack { slack =>
            HttpClient.withFilter(panickingFilter) {
                Abort.run[SlackReplaceOriginalFailure](slack.replaceOriginal(
                    SlackResponseUrl("http://127.0.0.1:1/hooks/x"),
                    SlackReply("r")
                ))
            }.map(result => assert(result == Result.fail(refused("response_url")), s"got: $result"))
        }
    }

    "each response_url operation sends its own body, with no Authorization header" in withSlack { slack =>
        for
            seen <- AtomicRef.init(Chunk.empty[(String, Maybe[String])])
            route = HttpRoute.postRaw("hook").request(_.bodyText).response(_.bodyText).handler { req =>
                seen.updateAndGet(_ :+ (req.fields.body, req.headers.get("Authorization")))
                    .andThen(HttpResponse(HttpStatus.OK).addField("body", """{"ok":true}"""))
            }
            server <- HttpServer.init(0, "127.0.0.1")(route)
            url    = SlackResponseUrl(s"http://127.0.0.1:${server.port}/hook")
            blocks = Chunk(SlackBlock.Section(SlackBlock.Text.Markdown("x")))
            _ <- slack.respondEphemeral(url, SlackReply("a", blocks))
                .andThen(slack.respondInChannel(url, SlackReply("b"), Present(SlackTs("1.5"))))
                .andThen(slack.replaceOriginal(url, SlackReply("c", blocks)))
                .andThen(slack.deleteOriginal(url))
            received <- seen.get
        yield assert(received == Chunk(
            """{"response_type":"ephemeral","replace_original":false,"text":"a","blocks":[{"type":"section","text":{"type":"mrkdwn","text":"x"}}]}""",
            """{"response_type":"in_channel","replace_original":false,"text":"b","thread_ts":"1.5"}""",
            """{"replace_original":true,"text":"c","blocks":[{"type":"section","text":{"type":"mrkdwn","text":"x"}}]}""",
            """{"delete_original":true}"""
        ).map(_ -> Absent))
        end for
    }

    "a response_url answer is a success unless it is a 429, an ok:false body, or another non-2xx" in withSlack { slack =>
        val answers = Chunk(
            "ok-true"        -> HttpResponse(HttpStatus.OK).addField("body", """{"ok":true}"""),
            "empty"          -> HttpResponse(HttpStatus.OK).addField("body", ""),
            "plain-ok"       -> HttpResponse(HttpStatus.OK).addField("body", "ok"),
            "no-ok-field"    -> HttpResponse(HttpStatus.OK).addField("body", """{"accepted":1}"""),
            "limited"        -> HttpResponse(HttpStatus.TooManyRequests).addField("body", "").addHeader("Retry-After", "3"),
            "code-ratelimit" -> HttpResponse(HttpStatus.OK).addField("body", """{"ok":false,"error":"ratelimited"}"""),
            "expired"        -> HttpResponse(HttpStatus.NotFound).addField("body", """{"ok":false,"error":"expired_url"}"""),
            "web-api-code"   -> HttpResponse(HttpStatus.OK).addField("body", """{"ok":false,"error":"invalid_auth"}"""),
            "no-code"        -> HttpResponse(HttpStatus.OK).addField("body", """{"ok":false}"""),
            "html"           -> HttpResponse(HttpStatus.InternalServerError).addField("body", "<html>oops</html>"),
            "redirect"       -> HttpResponse(HttpStatus.Found).addField("body", "").addHeader("Location", "/ok-true")
        )
        val routes = answers.map((name, answer) => HttpRoute.postRaw(name).response(_.bodyText).handler(_ => answer))
        HttpServer.init(0, "127.0.0.1")(routes*).map { server =>
            Kyo.foreach(answers) { (name, _) =>
                Abort.run[SlackException](slack.replaceOriginal(
                    SlackResponseUrl(s"http://127.0.0.1:${server.port}/$name"),
                    SlackReply("r")
                ))
                    .map(name -> _)
            }.map { results =>
                assert(results == Chunk(
                    "ok-true"        -> Result.unit,
                    "empty"          -> Result.unit,
                    "plain-ok"       -> Result.unit,
                    "no-ok-field"    -> Result.unit,
                    "limited"        -> Result.fail(SlackRateLimitException("response_url", Present(3.seconds))),
                    "code-ratelimit" -> Result.fail(SlackRateLimitException("response_url", Absent)),
                    "expired"        -> Result.fail(SlackOtherApiException("response_url", "expired_url", Chunk.empty)),
                    "web-api-code"   -> Result.fail(SlackOtherApiException("response_url", "invalid_auth", Chunk.empty)),
                    "no-code"        -> Result.fail(decode(
                        "response_url",
                        SlackDecodeException.Part.Envelope,
                        SlackDecodeException.Failure.MissingField,
                        Chunk("error")
                    )),
                    "html"     -> Result.fail(SlackUnexpectedStatusException("response_url", HttpStatus.InternalServerError)),
                    "redirect" -> Result.fail(SlackUnexpectedStatusException("response_url", HttpStatus.Found))
                ))
            }
        }
    }

    "an unreachable response_url fails with the transport leaf, holding the url's host and port and the kyo-net cause" in withSlack {
        slack =>
            Abort.run[SlackException](slack.deleteOriginal(SlackResponseUrl("http://127.0.0.1:1/hooks/x"))).map { unreachable =>
                assert(unreachable == Result.fail(refused("response_url")))
                assert(unreachable.failure.collect { case t: SlackTransportException => t.cause.map(_.getClass.getSimpleName) } ==
                    Present(Present("NetConnectException")))
            }
    }

    "a response_url that is not an absolute http or https url is refused before anything is sent" in withSlack { slack =>
        for
            reached <- AtomicInt.init(0)
            route = HttpRoute.postRaw("hooks/x").response(_.bodyText).handler { _ =>
                reached.incrementAndGet.andThen(HttpResponse(HttpStatus.OK).addField("body", """{"ok":true}"""))
            }
            server <- HttpServer.init(0, "127.0.0.1")(route)
            urls = Chunk(
                "hooks/x",
                "/hooks/x",
                "ftp://127.0.0.1/hooks/x",
                s"ws://127.0.0.1:${server.port}/hooks/x",
                "http+unix://%2Ftmp%2Fs/hooks/x"
            )
            local   <- Abort.get(HttpClientConfig.BaseUrl.init(s"http://127.0.0.1:${server.port}"))
            results <- HttpClient.withConfig(_.baseUrl(local)) {
                Kyo.foreach(urls)(u => Abort.run[SlackException](slack.deleteOriginal(SlackResponseUrl(u))))
            }
            sent <- reached.get
        yield
            assert(results == Chunk.fill(urls.size)(Result.fail(SlackRefusedUrlException("response_url"))))
            assert(sent == 0)
            assert(!results.flatMap(_.failure.toChunk).exists(e => renderChain(e).contains("hooks/x")), "a refusal renders the url")
        end for
    }

    "a response_url outside printable ASCII, which kyo-http's parser accepts and its client refuses to send, is refused" in withSlack {
        slack =>
            val urls = Chunk("http://hóoks.slack.test/x", "http://127.0.0.1:1/hooks/é", "http://127.0.0.1:1/hooks x")
            Kyo.foreach(urls)(u => Abort.run[SlackException](slack.deleteOriginal(SlackResponseUrl(u)))).map { results =>
                assert(urls.take(2).forall(u => HttpUrl.parse(u).isSuccess), "kyo-http's parser refuses a non-ASCII url")
                assert(results == Chunk.fill(urls.size)(Result.fail(SlackRefusedUrlException("response_url"))), s"got: $results")
            }
    }

    "each kyo-http failure maps to its kind, keeping a kyo-net cause only for a connection that could not be made" in {
        import SlackTransportException.Kind
        val url = s"https://hooks.slack.com/actions/$responseUrlSecret"
        HttpUrl.parse(url) match
            case Result.Success(target) =>
                val connect = kyo.net.NetConnectException("hooks.slack.com", 443)
                val tls     = kyo.net.NetTlsHandshakeException("hooks.slack.com", 443)
                val dns     = kyo.net.NetDnsResolutionException("hooks.slack.com")
                val cases   = Chunk[(HttpException, (Kind, Maybe[Duration], Maybe[Throwable]))](
                    HttpConnectException("hooks.slack.com", 443, connect)            -> (Kind.Connect, Absent, Present(connect)),
                    HttpConnectException("hooks.slack.com", 443, tls)                -> (Kind.Tls, Absent, Present(tls)),
                    HttpConnectException("hooks.slack.com", 443, new Exception("x")) -> (Kind.Connect, Absent, Absent),
                    HttpDnsResolutionException("hooks.slack.com", dns)               -> (Kind.Dns, Absent, Present(dns)),
                    HttpConnectTimeoutException("hooks.slack.com", 443, 3.seconds)   -> (Kind.ConnectTimeout, Present(3.seconds), Absent),
                    HttpPoolExhaustedException("hooks.slack.com", 443, 4, summon[Frame]) -> (Kind.PoolExhausted(4), Absent, Absent),
                    HttpTimeoutException(5.seconds, "POST", url)                         -> (Kind.Timeout, Present(5.seconds), Absent),
                    HttpMalformedBodyException("bad chunk")                              -> (Kind.Protocol, Absent, Absent),
                    HttpProtocolException("bad status line")                             -> (Kind.Protocol, Absent, Absent),
                    HttpPayloadTooLargeException(10.bytes, 5.bytes) -> (Kind.PayloadTooLarge(10.bytes, 5.bytes), Absent, Absent),
                    HttpConnectionClosedException(HttpConnectionClosedException.Phase.BeforeHead) ->
                        (Kind.ConnectionClosed, Absent, Absent),
                    HttpConnectionClosedException(HttpConnectionClosedException.Phase.BodyTruncated) ->
                        (Kind.ConnectionClosed, Absent, Absent),
                    HttpConnectionClosedException(HttpConnectionClosedException.Phase.TlsTruncated) ->
                        (Kind.ConnectionClosed, Absent, Absent),
                    HttpWebSocketHandshakeException(url, 200) -> (Kind.WebSocketHandshake, Absent, Absent)
                )
                val mapped = cases.map((e, _) => WebApi.transportFailure("response_url", target, e))
                assert(mapped.map(l => (l.kind, l.timeout, l.cause: Maybe[Throwable])) == cases.map(_._2))
                assert(mapped.forall(l => l.method == "response_url" && l.host == "hooks.slack.com" && l.port == 443))
                assert(!mapped.exists(renderChain(_).contains(responseUrlSecret)))
                val defect =
                    Result.catching[Throwable](WebApi.transportFailure("response_url", target, HttpNonAsciiException("the request path")))
                assert(defect.failure.map(_.getMessage).exists(_.contains("BUG HttpNonAsciiException reached a Slack call")))
            case other => fail(s"the url did not parse: $other")
        end match
    }

    // No failure renders a secret. Secrets are built from parts so their full text appears nowhere in
    // this file's source, which a KyoException message can quote.

    private val botSecret = Seq("xoxb", "33", "B0WEBAPITEST", "c5Jh").mkString("-")

    private val responseUrlSecret = Seq("T0HOOK", "4242", "q9Zr", "SECRETPATH").mkString("")

    /** Every message and `toString` along the cause chain, so a secret hidden in a wrapped kyo-http
      * exception is caught too.
      */
    private def renderChain(t: Throwable): String =
        Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).take(8)
            .map(e => s"${e.toString}\n${e.getMessage}").mkString("\n")

    private def requestWith(token: SlackToken.Bot, base: String)(using Frame) =
        withSlackAt(base, slackConfig.bot(token))(slack => call(slack.send(message).unit))

    "request failures render no bot token: unreachable host, ok:false, 429, undecodable body" in {
        val route = HttpRoute.postRaw("chat.postMessage").response(_.bodyText).handler { _ =>
            HttpResponse(HttpStatus.OK).addField("body", """{"ok":false,"error":"invalid_auth"}""")
        }
        val rateRoute = HttpRoute.postRaw("r/chat.postMessage").response(_.bodyText).handler { _ =>
            HttpResponse(HttpStatus.TooManyRequests).addField("body", """{"ok":false}""").addHeader("Retry-After", "7")
        }
        val garbageRoute = HttpRoute.postRaw("g/chat.postMessage").response(_.bodyText).handler { _ =>
            HttpResponse(HttpStatus.OK).addField("body", """{"ok":true,"unexpected":1}""")
        }
        val token = botOf(botSecret)
        HttpServer.init(0, "127.0.0.1")(route, rateRoute, garbageRoute).map { server =>
            val base = s"http://127.0.0.1:${server.port}"
            for
                unreachable <- requestWith(token, "http://127.0.0.1:1/api")
                okFalse     <- requestWith(token, base)
                limited     <- requestWith(token, s"$base/r")
                undecodable <- requestWith(token, s"$base/g")
            yield
                val results = Chunk(unreachable, okFalse, limited, undecodable)
                assert(results == Chunk(
                    Result.fail(refused("chat.postMessage")),
                    Result.fail(SlackInvalidAuthException("chat.postMessage", Chunk.empty)),
                    Result.fail(SlackRateLimitException("chat.postMessage", Present(7.seconds))),
                    Result.fail(decode(
                        "chat.postMessage",
                        SlackDecodeException.Part.Payload,
                        SlackDecodeException.Failure.MissingField,
                        Chunk("ts")
                    ))
                ))
                assert(results.flatMap(_.failure.toChunk).filter(renderChain(_).contains(botSecret)) == Chunk.empty)
            end for
        }
    }

    /** Runs `call` against a raw listener that reads the request, writes `reply` (possibly nothing) and closes, and returns the
      * listener's port with the call's result. kyo-http's `HttpServer` always completes a response, so only a raw listener closes
      * before the head or while the declared body is still owed.
      */
    private def againstRawPeer[A](reply: String)(call: Int => A < (Async & Abort[SlackException]))(using
        Frame
    ): (Int, Result[SlackException, A]) < (Async & Scope & Abort[kyo.net.NetException]) =
        // Unsafe: the listener and its accepted connection are kyo-net's unsafe tier, bridged here for a test peer.
        import AllowUnsafe.embrace.danger
        val accepted  = Promise.Unsafe.init[kyo.net.Connection, Any]()
        val listening = kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16)(conn => accepted.completeDiscard(Result.succeed(conn)))
        listening.safe.get.map { listener =>
            val peer = accepted.safe.get.map { conn =>
                Abort.run[Closed](conn.inbound.safe.take.andThen(
                    if reply.isEmpty then Kyo.unit else conn.outbound.safe.put(Utf8.encode(reply))
                )).andThen(Sync.Unsafe.defer(conn.close()))
            }
            Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen(Fiber.init(peer)).andThen {
                Abort.run[SlackException](call(listener.port)).map(result => (listener.port, result))
            }
        }
    end againstRawPeer

    private def transportLeaf(method: String, kind: SlackTransportException.Kind, port: Int)(using Frame): SlackException =
        SlackTransportException(method, kind, "127.0.0.1", port, Absent)(Absent)

    "a peer that closes before any response head is the transport leaf of kind ConnectionClosed, on the Web API and a response_url" in {
        import SlackTransportException.Kind
        for
            api  <- againstRawPeer("")(port => withSlackAt(s"http://127.0.0.1:$port")(_.identity))
            hook <- againstRawPeer("")(port =>
                withSlack(_.respondEphemeral(SlackResponseUrl(s"http://127.0.0.1:$port/hook/$responseUrlSecret"), SlackReply("hi")))
            )
        yield
            assert(api._2 == Result.fail(transportLeaf("auth.test", Kind.ConnectionClosed, api._1)), s"got: ${api._2}")
            assert(hook._2 == Result.fail(transportLeaf("response_url", Kind.ConnectionClosed, hook._1)), s"got: ${hook._2}")
        end for
    }

    "a response head with a status outside 100 to 599 is the transport leaf of kind Protocol, on the Web API and a response_url" in {
        import SlackTransportException.Kind
        val head = "HTTP/1.1 999 Odd\r\nContent-Length: 0\r\n\r\n"
        for
            api  <- againstRawPeer(head)(port => withSlackAt(s"http://127.0.0.1:$port")(_.identity))
            hook <- againstRawPeer(head)(port =>
                withSlack(_.respondEphemeral(SlackResponseUrl(s"http://127.0.0.1:$port/hook/$responseUrlSecret"), SlackReply("hi")))
            )
        yield
            assert(api._2 == Result.fail(transportLeaf("auth.test", Kind.Protocol, api._1)), s"got: ${api._2}")
            assert(hook._2 == Result.fail(transportLeaf("response_url", Kind.Protocol, hook._1)), s"got: ${hook._2}")
        end for
    }

    "a peer that closes while the declared body is still owed is the transport leaf of kind ConnectionClosed" in {
        againstRawPeer("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"ok\":")(port =>
            withSlackAt(s"http://127.0.0.1:$port")(_.identity)
        ).map { (port, result) =>
            assert(result == Result.fail(transportLeaf("auth.test", SlackTransportException.Kind.ConnectionClosed, port)), s"got: $result")
        }
    }

    "a chunked body with a bad size line is the transport leaf of kind Protocol" in {
        againstRawPeer("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n{}\r\n0\r\n\r\n")(
            port =>
                withSlackAt(s"http://127.0.0.1:$port")(_.identity)
        ).map { (port, result) =>
            assert(result == Result.fail(transportLeaf("auth.test", SlackTransportException.Kind.Protocol, port)), s"got: $result")
        }
    }

    "an answer larger than maxResponseLength is the transport leaf of kind PayloadTooLarge, naming both sizes" in {
        val route = HttpRoute.postRaw("auth.test").response(_.bodyText).handler(_ => HttpResponse.ok("x" * 4096))
        HttpServer.init(0, "127.0.0.1")(route).map { server =>
            val config = slackConfig.maxResponseLength(1024.bytes)
            withSlackAt(s"http://127.0.0.1:${server.port}", config)(slack => call(slack.identity.unit)).map { result =>
                assert(result == Result.fail(
                    transportLeaf("auth.test", SlackTransportException.Kind.PayloadTooLarge(4096.bytes, 1024.bytes), server.port)
                ))
            }
        }
    }

    "Slack text that echoes a token, raw or percent-encoded, holds <redacted> in its place in every leaf field" in {
        val echoed             = Seq("xoxb", "44", "B0ECHO|x", "k9").mkString("-")
        val encoded            = echoed.replace("|", "%7C")
        val lower              = echoed.replace("|", "%7c")
        def echo(code: String) =
            s"""{"ok":false,"error":"$code","needed":"chat:write,$echoed","provided":"$encoded","response_metadata":{"messages":["bad $echoed","bad $lower"]}}"""
        val codeRoute  = HttpRoute.postRaw("c/chat.postMessage").response(_.bodyText).handler(_ => HttpResponse.ok(echo(echoed)))
        val scopeRoute = HttpRoute.postRaw("s/chat.postMessage").response(_.bodyText).handler(_ => HttpResponse.ok(echo("missing_scope")))
        val hookRoute  = HttpRoute.postRaw(s"hook/$responseUrlSecret").response(_.bodyText).handler(_ => HttpResponse.ok(echo(echoed)))
        val messages   = Chunk("bad <redacted>", "bad <redacted>")
        HttpServer.init(0, "127.0.0.1")(codeRoute, scopeRoute, hookRoute).map { server =>
            val base   = s"http://127.0.0.1:${server.port}"
            val config = slackConfig.bot(botOf(echoed))
            for
                code  <- withSlackAt(s"$base/c", config)(slack => call(slack.send(message).unit))
                scope <- withSlackAt(s"$base/s", config)(slack => call(slack.send(message).unit))
                hook  <- withSlackAt(base, config)(slack =>
                    call(slack.respondEphemeral(SlackResponseUrl(s"$base/hook/$responseUrlSecret"), SlackReply("hi")))
                )
            yield
                assert(code == Result.fail(SlackOtherApiException("chat.postMessage", "<redacted>", messages)))
                assert(scope == Result.fail(
                    SlackMissingScopeException("chat.postMessage", Chunk("chat:write", "<redacted>"), Chunk("<redacted>"), messages)
                ))
                assert(hook == Result.fail(SlackOtherApiException("response_url", "<redacted>", messages)))
            end for
        }
    }

    "a response_url failure renders neither the response_url nor the bot token" in {

        /** A route at `<prefix>/<secret>` answering a status, a body, and optionally a `Location`, each built from its own path. */
        def answering(prefix: String)(answer: String => (HttpStatus, String, Maybe[String])) =
            HttpRoute.postRaw(s"$prefix/$responseUrlSecret").response(_.bodyText).handler { _ =>
                val (status, body, location) = answer(s"/$prefix/$responseUrlSecret")
                val response                 = HttpResponse(status).addField("body", body)
                location.fold(response)(response.addHeader("Location", _))
            }
        val routes = Chunk(
            answering("html")(_ => (HttpStatus.InternalServerError, "<html>oops</html>", Absent)),
            answering("loop")(path => (HttpStatus.Found, "", Present(path))),
            answering("echo") { path =>
                (
                    HttpStatus.OK,
                    s"""{"ok":false,"error":"invalid $path","response_metadata":{"messages":["see http://127.0.0.1$path", "at $path"]}}""",
                    Absent
                )
            }
        )
        HttpServer.init(0, "127.0.0.1")(routes*).map { server =>
            def at(prefix: String, port: Int = server.port) =
                SlackResponseUrl(s"http://127.0.0.1:$port/$prefix/$responseUrlSecret")
            withSlackAt(s"http://127.0.0.1:${server.port}", slackConfig.bot(botOf(botSecret))) { slack =>
                for
                    html        <- Abort.run[SlackException](slack.respondEphemeral(at("html"), SlackReply("r")))
                    loop        <- Abort.run[SlackException](slack.respondInChannel(at("loop"), SlackReply("r")))
                    echo        <- Abort.run[SlackException](slack.replaceOriginal(at("echo"), SlackReply("r")))
                    unreachable <- Abort.run[SlackException](slack.deleteOriginal(at("html", 1)))
                yield
                    val results  = Chunk(html, loop, echo, unreachable)
                    val rendered = results.flatMap(_.failure.toChunk).map(renderChain).mkString("\n")
                    assert(!rendered.contains(responseUrlSecret), rendered)
                    assert(!rendered.contains(botSecret), rendered)
                    assert(results == Chunk(
                        Result.fail(SlackUnexpectedStatusException("response_url", HttpStatus.InternalServerError)),
                        Result.fail(SlackUnexpectedStatusException("response_url", HttpStatus.Found)),
                        Result.fail(SlackOtherApiException(
                            "response_url",
                            "invalid <response_url>",
                            Chunk("see http://127.0.0.1<response_url>", "at <response_url>")
                        )),
                        Result.fail(refused("response_url"))
                    ))
                end for
            }
        }
    }

end WebApiTest
