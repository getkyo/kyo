package kyo

import SlackLiveTest.*

/** The module against a real Slack workspace. Every leaf needs three environment variables, read
  * through `kyo.System.env` so the suite runs on JVM, Scala.js, Scala Native and Wasm:
  *   - `SLACK_APP_TOKEN`: the app-level token (`xapp-`, scope `connections:write`) that opens Socket Mode;
  *   - `SLACK_BOT_TOKEN`: the bot token (`xoxb-`, scopes `chat:write` and `channels:history`);
  *   - `SLACK_CHANNEL_ID`: a public channel the bot is a member of.
  *
  * Without them every leaf is cancelled with a message naming the variables that are missing. What
  * the workspace and the app need is in the module's CONTRIBUTING. The suite causes the event it
  * receives by posting to the channel it listens on, and it deletes every message it posts.
  *
  * The scenarios live in the companion object, so `SlackTest` runs each one against a local
  * server standing in for Slack.
  */
class SlackLiveTest extends kyo.test.Test[Any]:

    // Socket-only opt-out: this suite runs an HttpClient on the NIO transport, whose closed-channel fd
    // close is deferred to the idle selector's next select() (an opaque socket:[inode] no allowlist matches), the
    // same transport-deferred reason as BaseHttpTest. Thread, fiber, and file-descriptor detection stay on.
    override def config = super.config.leakCheckSockets(false)

    private def live(using Frame): Credentials < Sync =
        for
            app     <- System.env[String](AppTokenVariable)
            bot     <- System.env[String](BotTokenVariable)
            channel <- System.env[String](ChannelVariable)
        yield (app, bot, channel) match
            case (Present(a), Present(b), Present(c)) =>
                Credentials(SlackConfig(SlackToken.AppLevel(a), SlackToken.Bot(b)), SlackId.ChannelId(c))
            case _ =>
                val missing = Chunk(AppTokenVariable -> app, BotTokenVariable -> bot, ChannelVariable -> channel)
                    .collect { case (name, Absent) => name }
                cancel(s"${missing.mkString(", ")} not set: the live Slack suite needs a workspace, see kyo-slack/CONTRIBUTING.md")

    "connecting opens Socket Mode and delivers hello first" in {
        live.map(connectAndReceiveFirst).map { first =>
            // Slack chooses the connection count and the app id: the case and a count of at least one are compared.
            val connections = first match
                case hello: SlackEnvelope.Hello => Present(hello.numConnections)
                case _                          => Absent
            assert(connections.exists(_ >= 1), s"the first envelope is hello, got: $first")
        }
    }

    "posting a message answers its timestamp" in {
        live.map(postAndDelete).map { case (ts, deleted) =>
            // Slack chooses the timestamp: its shape, seconds and a fraction, is compared.
            assert(ts.value.matches("[0-9]+\\.[0-9]+"), s"got: $ts")
            assert(deleted == Result.succeed(()), s"the posted message is deleted, got: $deleted")
        }
    }

    "a message the bot posts to its channel arrives as an event" in {
        // deviation: real Slack delivery latency. The event arrives over Slack's network, so the wait is bounded by
        // EventWait on the real clock, and an expiry reports the missing event as a failure.
        live.map(receiveOwnPost(_, Present(EventWait))).map { outcome =>
            assert(outcome.event == Present(outcome.expected), s"got: ${outcome.event}")
            assert(outcome.deleted == Result.succeed(()), s"the posted message is deleted, got: ${outcome.deleted}")
        }
    }

    "a Web API error is the operation's leaf" in {
        live.map(postToMissingChannel).map { result =>
            // Slack chooses whether to send response_metadata messages: the leaf and its method are compared.
            assert(
                result.failure.collect { case e: SlackChannelNotFoundException => e.method } == Present("chat.postMessage"),
                s"got: $result"
            )
        }
    }

    // chat.postMessage's reference page does not list msg_too_long. Passing confirms the page; failing with
    // SlackOtherApiException(code = msg_too_long) means SlackMsgTooLongException belongs on SlackChatPostMessageFailure.
    "a text over 40,000 characters is accepted by chat.postMessage, not answered with msg_too_long" in {
        live.map(postLongText).map { case (posted, deleted) =>
            assert(posted.isSuccess, s"got: $posted")
            assert(deleted == Result.succeed(()), s"the posted message is deleted, got: $deleted")
        }
    }

end SlackLiveTest

object SlackLiveTest:

    val AppTokenVariable = "SLACK_APP_TOKEN"
    val BotTokenVariable = "SLACK_BOT_TOKEN"
    val ChannelVariable  = "SLACK_CHANNEL_ID"

    /** A channel id Slack never assigns, so `chat.postMessage` answers `channel_not_found`. */
    val MissingChannel = SlackId.ChannelId("C0000000000")

    /** Past the 40,000 characters `chat.postMessage` keeps of a text. */
    val LongTextLength = 40001

    /** The event wait bounds a live round trip that normally takes well under a second. */
    val EventWait = 30.seconds

    /** A workspace to run the scenarios against. `transport` is the socket transport: the live one for a real Slack, and
      * `TransportTest.plainLocal` for a local Slack without a certificate.
      */
    case class Credentials(
        config: SlackConfig,
        channel: SlackId.ChannelId,
        transport: (HttpClient, SlackConfig) => kyo.internal.slack.Transport = kyo.internal.slack.Transport.live
    ):
        def init(using Frame): Slack < (Async & Abort[SlackInitFailure] & Scope) =
            Scope.acquireRelease(Slack.initUnscopedOver(transport(_, config))(config))(Slack.close)
    end Credentials

    case class DeleteBody(channel: SlackId.ChannelId, ts: SlackTs) derives Schema
    case class Ok(ok: Boolean) derives Schema

    case class OwnPost(expected: SlackEvent.Message, event: Maybe[SlackEvent.Message], deleted: Result[SlackException, Unit])
        derives CanEqual

    private def delete(channel: SlackId.ChannelId, ts: SlackTs)(using Frame): Result[SlackException, Unit] < (Async & Env[Slack]) =
        Abort.run[SlackException](Slack.custom[DeleteBody, Ok](SlackMethod("chat.delete"), DeleteBody(channel, ts)).unit)

    /** Open a connection, and answer the first envelope it delivers. */
    def connectAndReceiveFirst(c: Credentials)(using Frame): SlackEnvelope[?] < (Async & Abort[SlackException | Closed]) =
        Scope.run {
            for
                received <- Channel.init[SlackEnvelope[?]](16)
                client   <- c.init
                loop     <- Fiber.initUnscoped(Abort.run[SlackException](Slack.use(client)(Slack.receive(recordInto(received)))))
                first    <- received.take
                _        <- Slack.close(client)
                _        <- loop.get
            yield first
        }

    /** The bare answer an envelope requires: the plain ack, or nothing. */
    def answer[A](e: SlackEnvelope[A]): A =
        e match
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => ()

    /** A handler that records every envelope and gives each the bare answer it requires. */
    def recordInto(received: Channel[SlackEnvelope[?]])(using
        Frame
    ): [A] => SlackEnvelope[A] => A < Async =
        [A] => (env: SlackEnvelope[A]) => Abort.run[Closed](received.put(env)).andThen(answer[A](env))

    /** Post a message and delete it; answer the timestamp and the deletion's outcome. */
    def postAndDelete(c: Credentials)(using Frame): (SlackTs, Result[SlackException, Unit]) < (Async & Abort[SlackException]) =
        Slack.let(c.config) {
            for
                ts      <- Slack.chatPostMessage(SlackMessage(c.channel, "kyo-slack live suite: posting"))
                deleted <- delete(c.channel, ts)
            yield (ts, deleted)
        }

    /** Connect, wait for hello, post a unique marker to the channel, and answer the message event that
      * carries it, next to the event the post should produce. With `eventWait` present the wait is
      * bounded on the real clock, and the event is `Absent` if none arrived in time; with it absent the
      * wait is unbounded, for a local Slack whose delivery the test controls.
      */
    def receiveOwnPost(c: Credentials, eventWait: Maybe[Duration])(using Frame): OwnPost < (Async & Abort[SlackException | Closed]) =
        Scope.run {
            for
                marker   <- Random.nextStringAlphanumeric(16).map(s => s"kyo-slack live suite: $s")
                received <- Channel.init[SlackEnvelope[?]](64)
                client   <- c.init
                identity <- Slack.use(client)(Slack.authTest)
                loop     <- Fiber.initUnscoped(Abort.run[SlackException](Slack.use(client)(Slack.receive(recordInto(received)))))
                _        <- untilFirst(received) { case h: SlackEnvelope.Hello => h }
                ts       <- Slack.use(client)(Slack.chatPostMessage(SlackMessage(c.channel, marker)))
                ownPost = untilFirst(received) {
                    case SlackEnvelope.EventsApi(_, m: SlackEvent.Message, _) if m.text == marker => m
                }
                event <- eventWait match
                    case Present(wait) => Abort.run[Timeout](Async.timeout(wait)(ownPost)).map(_.toMaybe)
                    case Absent        => ownPost.map(Present(_))
                deleted <- Slack.use(client)(delete(c.channel, ts))
                _       <- Slack.close(client)
                _       <- loop.get
            yield OwnPost(SlackEvent.Message(c.channel, identity.userId, marker, ts), event, deleted)
        }

    /** Post to a channel that does not exist, and answer the outcome. */
    def postToMissingChannel(c: Credentials)(using Frame): Result[SlackException, SlackTs] < Async =
        Slack.let(c.config) {
            Abort.run[SlackException](Slack.chatPostMessage(SlackMessage(MissingChannel, "kyo-slack live suite: missing channel")))
        }

    /** Post a text longer than `chat.postMessage` keeps, delete it if it was posted, and answer both outcomes. */
    def postLongText(c: Credentials)(using Frame): (Result[SlackException, SlackTs], Result[SlackException, Unit]) < Async =
        Slack.let(c.config) {
            Abort.run[SlackException](Slack.chatPostMessage(SlackMessage(c.channel, "x" * LongTextLength))).map { posted =>
                posted match
                    case Result.Success(ts) => delete(c.channel, ts).map(deleted => (posted, deleted))
                    case _                  => (posted, Result.succeed(()))
            }
        }

    private def untilFirst[A](received: Channel[SlackEnvelope[?]])(pick: PartialFunction[SlackEnvelope[?], A])(using
        Frame
    ): A < (Async & Abort[Closed]) =
        Loop.foreach {
            received.take.map { env =>
                if pick.isDefinedAt(env) then Loop.done(pick(env)) else Loop.continue
            }
        }

end SlackLiveTest
