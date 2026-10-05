package kyo

import SlackExceptionMembershipTest.*
import SlackLiterals.*
import SlackLiveTest.answer
import kyo.internal.slack.TransportTest

/** Which leaf each public operation can fail with. One table, `leaves`, states it, with one row per
  * leaf an operation fails with, and every pair the table admits is produced through the operation's
  * real path against a local server playing Slack.
  */
class SlackExceptionMembershipTest extends kyo.test.Test[Any]:

    private val bot     = botOf("xoxb-failure-test")
    private val message = SlackMessage(SlackId.ChannelId("C1"), "hi")
    private val view    = SlackView(SlackView.Type.Modal, title = Present("T"))

    private case class Members(members: Chunk[String]) derives Schema, CanEqual

    private case class NoArgs() derives Schema

    private val connectionsOpen = "apps.connections.open"

    private val methodOf: Map[String, String] = Map(
        "identity"      -> "auth.test",
        "send"          -> "chat.postMessage",
        "sendEphemeral" -> "chat.postEphemeral",
        "edit"          -> "chat.update",
        "openView"      -> "views.open",
        "updateView"    -> "views.update",
        "publishView"   -> "views.publish",
        "custom"        -> "users.list"
    )

    private def webCall(op: String)(using Frame): Unit < (Async & Abort[SlackException] & Env[Slack]) =
        op match
            case "identity"      => Slack.identity.unit
            case "send"          => Slack.send(message).unit
            case "sendEphemeral" => Slack.sendEphemeral(message, SlackId.UserId("U1")).unit
            case "edit"          => Slack.edit(SlackId.ChannelId("C1"), SlackTs("1.0"), message).unit
            case "openView"      => Slack.openView(SlackId.TriggerId("T1"), view).unit
            case "updateView"    => Slack.updateView(SlackId.ViewId("V1"), view).unit
            case "publishView"   => Slack.publishView(SlackId.UserId("U1"), view).unit
            case _               => Slack.custom[NoArgs, Members](SlackLiterals.methodOf("users.list"), NoArgs()).unit

    private val responsePath = "hooks/respond"
    private val slackReply   = SlackReply("hi")

    private def respondCall(op: String, url: SlackResponseUrl)(using Frame): Unit < (Async & Abort[SlackException] & Env[Slack]) =
        op match
            case "respondEphemeral" => Slack.respondEphemeral(url, slackReply)
            case "respondInChannel" => Slack.respondInChannel(url, slackReply)
            case "replaceOriginal"  => Slack.replaceOriginal(url, slackReply)
            case _                  => Slack.deleteOriginal(url)

    private def methodName(op: String): String =
        if respond.contains(op) then "response_url" else methodOf.getOrElse(op, connectionsOpen)

    private case class Reply(status: HttpStatus, body: String, retryAfter: Maybe[String] = Absent)

    private val helloFrame    = """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private val warningFrame  = """{"type":"disconnect","reason":"warning"}"""
    private val disabledFrame = """{"type":"disconnect","reason":"link_disabled"}"""

    /** A local Slack: every Web API method and `apps.connections.open` answer the next queued reply
      * (the last one repeats), and the Socket Mode endpoint sends the scripted frames, then stays open.
      */
    private class LocalSlack(val replies: AtomicRef[Chunk[Reply]], val frames: AtomicRef[Chunk[String]], val base: String):
        val wss: String       = base.replace("http://", "wss://") + "/ws/slack"
        def live: Reply       = Reply(HttpStatus.OK, s"""{"ok":true,"url":"$wss"}""")
        def deadSocket: Reply = Reply(HttpStatus.OK, """{"ok":true,"url":"wss://127.0.0.1:1/ws/slack"}""")
        def httpSocket: Reply = Reply(HttpStatus.OK, s"""{"ok":true,"url":"${base}/ws/slack"}""")
    end LocalSlack

    private def withLocalSlack[A](test: LocalSlack => A < (Async & Abort[SlackException | HttpException] & Scope))(using
        Frame
    ): A < (Async & Abort[SlackException | HttpException] & Scope) =
        for
            replies <- AtomicRef.init(Chunk.empty[Reply])
            frames  <- AtomicRef.init(Chunk.empty[String])
            next   = replies.getAndUpdate(q => if q.size > 1 then q.drop(1) else q).map(_.head)
            routes = (methodOf.values.toSeq :+ connectionsOpen :+ responsePath).map { m =>
                HttpRoute.postRaw(m).response(_.bodyText).handler { _ =>
                    next.map { r =>
                        val response = HttpResponse(r.status).addField("body", r.body)
                        r.retryAfter match
                            case Present(v) => response.addHeader("Retry-After", v)
                            case Absent     => response
                    }
                }
            }
            socket = HttpHandler.webSocket("ws/slack") { (_, ws) =>
                frames.get.map(fs => Kyo.foreachDiscard(fs)(f => ws.put(HttpWebSocket.Payload.Text(f)))).andThen(ws.stream.foreach(_ =>
                    Kyo.unit
                ))
            }
            server <- HttpServer.init(0, "127.0.0.1")((routes :+ socket)*)
            result <- test(LocalSlack(replies, frames, s"http://127.0.0.1:${server.port}"))
        yield result

    private def replyFor(slack: LocalSlack, scenario: Scenario): Reply =
        scenario match
            case Scenario.Code(c)      => Reply(HttpStatus.OK, s"""{"ok":false,"error":"$c"}""")
            case Scenario.Unavailable  => Reply(HttpStatus.ServiceUnavailable, "<html>unavailable</html>")
            case Scenario.Limited      => Reply(HttpStatus.TooManyRequests, """{"ok":false,"error":"ratelimited"}""", Present("7"))
            case Scenario.NoErrorCode  => Reply(HttpStatus.OK, """{"ok":false}""")
            case Scenario.Unreachable  => slack.deadSocket
            case Scenario.Refused      => slack.httpSocket
            case Scenario.LinkDisabled => slack.live

    private val ack: [A] => SlackEnvelope[A] => A < Async = [A] => (e: SlackEnvelope[A]) => answer[A](e)

    private val slackConfig = configOf(appLevelOf("xapp-failure-test"), bot)

    private def configAt(base: String)(using Frame): SlackConfig =
        valid(slackConfig.baseUrl(urlOf(base)))

    /** `Slack.init` over the transport that reaches the local Socket Mode server. */
    private def initLocal(config: SlackConfig)(using Frame): Slack < (Async & Abort[SlackInitFailure] & Scope) =
        Scope.acquireRelease(Slack.initUnscopedOver(TransportTest.plainLocal(_, config))(config))(Slack.close)

    /** Drive `op` through its real path with the local server set up for `scenario`. */
    private def produce(slack: LocalSlack, op: String, scenario: Scenario)(using
        Frame
    ): Result[SlackException, Unit] < (Async & Abort[HttpException]) =
        val reply  = replyFor(slack, scenario)
        val config = configAt(slack.base)
        op match
            case "init" =>
                slack.replies.set(Chunk(reply)).andThen(slack.frames.set(Chunk(helloFrame))).andThen {
                    Abort.run[SlackException](Scope.run(initLocal(config).unit))
                }
            case "runReceive" =>
                // A client built by `run(config)` has no connection, so the receive loop opens the first one.
                val frames = if scenario == Scenario.LinkDisabled then Chunk(helloFrame, disabledFrame) else Chunk(helloFrame)
                slack.replies.set(Chunk(reply)).andThen(slack.frames.set(frames)).andThen {
                    Abort.run[SlackException](Slack.runOver(TransportTest.plainLocal(_, config))(config)(Slack.receive(ack)))
                }
            case "receive" =>
                // The rotation a routine disconnect starts meets the scenario's reply; link_disabled needs no rotation.
                val frames =
                    if scenario == Scenario.LinkDisabled then Chunk(helloFrame, disabledFrame) else Chunk(helloFrame, warningFrame)
                slack.replies.set(Chunk(slack.live, reply)).andThen(slack.frames.set(frames)).andThen {
                    Abort.run[SlackException](Scope.run(initLocal(config).map(client => Slack.run(client)(Slack.receive(ack)))))
                }
            case posted if respond.contains(posted) =>
                val url = scenario match
                    case Scenario.Unreachable => SlackResponseUrl(s"http://127.0.0.1:1/$responsePath")
                    case Scenario.Refused     => SlackResponseUrl(responsePath)
                    case _                    => SlackResponseUrl(s"${slack.base}/$responsePath")
                slack.replies.set(Chunk(reply)).andThen {
                    Abort.run[SlackException](Slack.run(config)(respondCall(posted, url)))
                }
            case web =>
                val at = if scenario == Scenario.Unreachable then configAt("http://127.0.0.1:1/api") else config
                slack.replies.set(Chunk(reply)).andThen {
                    Abort.run[SlackException](Slack.run(at)(webCall(web)))
                }
        end match
    end produce

    /** The rows an operation admits. */
    private def rowsOf(op: String): Chunk[Leaf] =
        leaves.filter(_.ops.contains(op))

    private def producesEveryLeafOf(op: String)(using Frame, kyo.test.AssertScope) =
        withLocalSlack { slack =>
            val method     = methodName(op)
            val connecting = conn.contains(op)
            val rows       = rowsOf(op)
            Kyo.foreach(rows)(l => produce(slack, op, l.scenario).map(r => l.kind -> r)).map { results =>
                assert(results == rows.map(l => l.kind -> Result.fail(l.expected(method, connecting))), results.toString)
            }
        }

    "init produces every leaf of its row" in producesEveryLeafOf("init")
    "runReceive produces every leaf of its row" in producesEveryLeafOf("runReceive")
    "receive produces every leaf of its row" in producesEveryLeafOf("receive")
    "identity produces every leaf of its row" in producesEveryLeafOf("identity")
    "send produces every leaf of its row" in producesEveryLeafOf("send")
    "sendEphemeral produces every leaf of its row" in producesEveryLeafOf("sendEphemeral")
    "edit produces every leaf of its row" in producesEveryLeafOf("edit")
    "openView produces every leaf of its row" in producesEveryLeafOf("openView")
    "updateView produces every leaf of its row" in producesEveryLeafOf("updateView")
    "publishView produces every leaf of its row" in producesEveryLeafOf("publishView")
    "custom produces every leaf of its row" in producesEveryLeafOf("custom")
    "respondEphemeral produces every leaf of its row" in producesEveryLeafOf("respondEphemeral")
    "respondInChannel produces every leaf of its row" in producesEveryLeafOf("respondInChannel")
    "replaceOriginal produces every leaf of its row" in producesEveryLeafOf("replaceOriginal")
    "deleteOriginal produces every leaf of its row" in producesEveryLeafOf("deleteOriginal")

    "a code whose leaf is outside an operation's row is SlackOtherApiException there" in {
        withLocalSlack { slack =>
            val outside =
                for
                    op        <- Chunk.from(every.toSeq.sorted)
                    (leaf, c) <- codeLeaves.filter((l, _) => !l.ops.contains(op))
                yield (op, leaf, c)
            Kyo.foreach(outside) { (op, leaf, c) =>
                produce(slack, op, Scenario.Code(c)).map(r => (op, leaf.kind) -> r)
            }.map { results =>
                val expected = outside.map { (op, leaf, c) =>
                    (op, leaf.kind) -> Result.fail(SlackOtherApiException(methodName(op), c, Chunk.empty))
                }
                assert(results == expected, results.toString)
            }
        }
    }

end SlackExceptionMembershipTest

private object SlackExceptionMembershipTest:

    enum Kind derives CanEqual:
        case Transport, RefusedUrl, UnexpectedStatus, Decode, RateLimit, OtherApi
        case InvalidAuth, NotAuthed, TokenRevoked, TokenExpired, AccountInactive, NotAllowedTokenType, MissingScope
        case InvalidArguments, ChannelNotFound, NotInChannel, IsArchived, UserNotInChannel, NoText, MsgTooLong, MsgBlocksTooLong
        case InvalidBlocks, InvalidBlocksFormat, CannotReplyToMessage, MessageNotFound, CantUpdateMessage
        case ExpiredTriggerId, ExchangedTriggerId, InvalidTriggerId, ViewTooLarge, NotFound
        case LinkDisabled
    end Kind

    // --- How the local server produces a leaf ---

    enum Scenario derives CanEqual:
        case Code(code: String)
        case Unavailable
        case Limited
        case NoErrorCode
        case Unreachable
        case Refused
        case LinkDisabled
    end Scenario

    /** One leaf: the operations it belongs to, how a real path produces it, and the value produced for
      * a method name (`apps.connections.open` when a connection is opened).
      */
    case class Leaf(
        kind: Kind,
        ops: Set[String],
        scenario: Scenario,
        expected: (String, Boolean) => Frame ?=> SlackException
    )

    def code(kind: Kind, ops: Set[String], c: String)(expected: (String, Chunk[String]) => Frame ?=> SlackException): Leaf =
        Leaf(kind, ops, Scenario.Code(c), (m, _) => expected(m, Chunk.empty))

    // --- The table ---

    val conn = Set("init", "runReceive", "receive")
    val loop = Set("runReceive", "receive")
    val web  =
        Set("identity", "send", "sendEphemeral", "edit", "openView", "updateView", "publishView", "custom")
    val all     = conn ++ web
    val chat    = Set("send", "sendEphemeral", "edit")
    val views   = Set("openView", "updateView", "publishView")
    val respond = Set("respondEphemeral", "respondInChannel", "replaceOriginal", "deleteOriginal")
    val every   = all ++ respond

    val leaves: Chunk[Leaf] = Chunk(
        // Every Unreachable scenario points at 127.0.0.1:1: the socket url for a connection, the base url for a Web API
        // call, the url itself for a response_url POST.
        Leaf(
            Kind.Transport,
            every,
            Scenario.Unreachable,
            (m, connecting) =>
                SlackTransportException(
                    if connecting then "socket-connect" else m,
                    SlackTransportException.Kind.Connect,
                    "127.0.0.1",
                    1,
                    Absent
                )(
                    Absent
                )
        ),
        Leaf(
            Kind.RefusedUrl,
            conn ++ respond,
            Scenario.Refused,
            (m, connecting) => SlackRefusedUrlException(if connecting then "socket-connect" else m)
        ),
        Leaf(
            Kind.UnexpectedStatus,
            every,
            Scenario.Unavailable,
            (m, _) => SlackUnexpectedStatusException(m, HttpStatus.ServiceUnavailable)
        ),
        Leaf(
            Kind.Decode,
            every,
            Scenario.NoErrorCode,
            (m, _) =>
                SlackDecodeException(
                    m,
                    SlackDecodeException.Part.Envelope,
                    SlackDecodeException.Failure.MissingField,
                    Chunk("error"),
                    Absent
                )
        ),
        Leaf(Kind.RateLimit, every, Scenario.Limited, (m, _) => SlackRateLimitException(m, Present(7.seconds))),
        code(Kind.OtherApi, every, "fatal_error")((m, msgs) => SlackOtherApiException(m, "fatal_error", msgs)),
        code(Kind.InvalidAuth, all, "invalid_auth")(SlackInvalidAuthException(_, _)),
        code(Kind.NotAuthed, all, "not_authed")(SlackNotAuthedException(_, _)),
        code(Kind.TokenRevoked, all, "token_revoked")(SlackTokenRevokedException(_, _)),
        code(Kind.TokenExpired, all, "token_expired")(SlackTokenExpiredException(_, _)),
        code(Kind.AccountInactive, all, "account_inactive")(SlackAccountInactiveException(_, _)),
        code(Kind.NotAllowedTokenType, all, "not_allowed_token_type")(SlackNotAllowedTokenTypeException(_, _)),
        code(Kind.MissingScope, all, "missing_scope")((m, msgs) => SlackMissingScopeException(m, Chunk.empty, Chunk.empty, msgs)),
        code(Kind.InvalidArguments, chat ++ views + "custom", "invalid_arguments")(SlackInvalidArgumentsException(_, _)),
        code(Kind.ChannelNotFound, chat, "channel_not_found")(SlackChannelNotFoundException(_, _)),
        code(Kind.NotInChannel, Set("send", "sendEphemeral"), "not_in_channel")(SlackNotInChannelException(_, _)),
        code(Kind.IsArchived, Set("send", "sendEphemeral"), "is_archived")(SlackIsArchivedException(_, _)),
        code(Kind.UserNotInChannel, Set("sendEphemeral"), "user_not_in_channel")(SlackUserNotInChannelException(_, _)),
        code(Kind.NoText, chat, "no_text")(SlackNoTextException(_, _)),
        code(Kind.MsgTooLong, Set("sendEphemeral", "edit"), "msg_too_long")(SlackMsgTooLongException(_, _)),
        code(Kind.MsgBlocksTooLong, Set("send"), "msg_blocks_too_long")(SlackMsgBlocksTooLongException(_, _)),
        code(Kind.InvalidBlocks, chat, "invalid_blocks")(SlackInvalidBlocksException(_, _)),
        code(Kind.InvalidBlocksFormat, chat, "invalid_blocks_format")(SlackInvalidBlocksFormatException(_, _)),
        code(Kind.CannotReplyToMessage, Set("send", "sendEphemeral"), "cannot_reply_to_message")(
            SlackCannotReplyToMessageException(_, _)
        ),
        code(Kind.MessageNotFound, Set("edit"), "message_not_found")(SlackMessageNotFoundException(_, _)),
        code(Kind.CantUpdateMessage, Set("edit"), "cant_update_message")(SlackCantUpdateMessageException(_, _)),
        code(Kind.ExpiredTriggerId, Set("openView"), "expired_trigger_id")(SlackExpiredTriggerIdException(_, _)),
        code(Kind.ExchangedTriggerId, Set("openView"), "exchanged_trigger_id")(SlackExchangedTriggerIdException(_, _)),
        code(Kind.InvalidTriggerId, Set("openView"), "invalid_trigger_id")(SlackInvalidTriggerIdException(_, _)),
        code(Kind.ViewTooLarge, views, "view_too_large")(SlackViewTooLargeException(_, _)),
        code(Kind.NotFound, Set("updateView"), "not_found")(SlackNotFoundException(_, _)),
        Leaf(Kind.LinkDisabled, loop, Scenario.LinkDisabled, (_, _) => SlackLinkDisabledException())
    )

    /** The code rows, each with the code the local server answers for it. */
    val codeLeaves: Chunk[(Leaf, String)] =
        leaves.collect { case l @ Leaf(_, _, Scenario.Code(c), _) => (l, c) }

end SlackExceptionMembershipTest
