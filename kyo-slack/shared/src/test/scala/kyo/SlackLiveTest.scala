package kyo

import SlackLiveTest.*
import kyo.internal.Platform

/** The module against a Slack: a real workspace when its three environment variables are set, a [[SlackLiveServer]] container when
  * none is. The variables are read through `kyo.System.env` so the suite runs on JVM, Scala.js, Scala Native and Wasm:
  *   - `SLACK_APP_TOKEN`: the app-level token (`xapp-`, scope `connections:write`) that opens Socket Mode;
  *   - `SLACK_BOT_TOKEN`: the bot token (`xoxb-`, scopes `chat:write`, `channels:history` and `commands`);
  *   - `SLACK_CHANNEL_ID`: a public channel the bot is a member of.
  *
  * With only some of them set, every leaf is cancelled naming the ones missing. With none, CI's case, every leaf starts a container of
  * its own, on every platform but Windows, whose container daemon cannot serve the Linux image. What the workspace and the app need is
  * in the module's CONTRIBUTING. The suite causes the events it receives, and it deletes every message it posts.
  *
  * The leaves after the `Interactive` separator need a person: one who runs the `/kyo-live` slash command, presses a button or submits
  * a modal when the suite asks in the channel. On a real workspace they run only when `SLACK_INTERACTIVE` is set; on the container the
  * suite does what the person would through the emulator's control API.
  *
  * On the container the Socket Mode connection runs over `TransportTest.plainLocal`, without TLS, which the real workspace covers. A
  * leaf whose behaviour the emulator does not reproduce asserts Slack's, and is cancelled on the container with the difference.
  *
  * The scenarios of the first leaves live in the companion object, so `SlackTest` runs each one against a local server standing in for
  * Slack.
  */
class SlackLiveTest extends kyo.test.Test[Any]:

    // The real target shares one workspace and channel, and container leaves contend on one daemon, so leaves run one at a time across
    // every suite of the process.
    override def config = super.config.sequential.globallySequential(true)

    // deviation: a leaf waits on Slack over the network, a container starting (the first one builds the emulator's image), or a person
    // acting, which no virtual clock can stand in for. No assertion reads elapsed time.
    override def timeout: Duration = 10.minutes

    private enum Target derives CanEqual:
        case Real(credentials: Credentials)
        case Emulator(server: SlackLiveServer)

        def credentials(using Frame): Credentials =
            this match
                case Real(credentials) => credentials
                case Emulator(server)  => server.credentials
    end Target

    /** What starting the container target can fail with. */
    private type Setup = ContainerException | FileSystemException | HttpException

    /** The Slack a leaf runs against. `realOnly` is why the emulator cannot stand in for this leaf, which is then cancelled before a
      * container starts. A leaf that ends in error prints the container's log before it is removed.
      */
    private def target(realOnly: Maybe[String])(using Frame): Target < (Async & Scope & Abort[Setup]) =
        for
            app     <- System.env[String](AppTokenVariable)
            bot     <- System.env[String](BotTokenVariable)
            channel <- System.env[String](ChannelVariable)
            target  <- resolve(realOnly, app, bot, channel)
        yield target

    private def resolve(realOnly: Maybe[String], app: Maybe[String], bot: Maybe[String], channel: Maybe[String])(using
        Frame
    ): Target < (Async & Scope & Abort[Setup]) =
        (app, bot, channel) match
            case (Present(a), Present(b), Present(c)) =>
                import SlackLiterals.*
                Target.Real(Credentials(configOf(appLevelOf(a), botOf(b)), SlackId.ChannelId(c)))
            case (Absent, Absent, Absent) =>
                realOnly match
                    case Present(reason) =>
                        cancel(s"slack-simulator differs from Slack: $reason; set the SLACK_ variables to run it on a workspace")
                    case Absent if Platform.isWindows =>
                        cancel("slack-simulator does not run on Windows: its container daemon cannot serve the Linux image")
                    case Absent =>
                        SlackLiveServer.init.map { server =>
                            Scope.ensure {
                                case Present(error) =>
                                    server.postMortem.map(log =>
                                        Console.printLineErr(s"the leaf ended with $error; slack-simulator log:\n$log")
                                    )
                                case Absent => Kyo.unit
                            }.andThen(Target.Emulator(server))
                        }
            case _ =>
                val missing = Chunk(AppTokenVariable -> app, BotTokenVariable -> bot, ChannelVariable -> channel)
                    .collect { case (name, Absent) => name }
                cancel(s"${missing.mkString(", ")} not set: the live Slack suite needs a workspace, see kyo-slack/CONTRIBUTING.md")
    end resolve

    /** Runs `v` against the leaf's Slack. */
    private def live[A](realOnly: Maybe[String] = Absent)(v: (Target, Credentials) => A < (Async & Scope & Abort[Any]))(using
        Frame
    ): A < (Async & Scope & Abort[Any]) =
        target(realOnly).map(t => v(t, t.credentials))

    "connecting opens Socket Mode and delivers hello first" in {
        live(realOnly = Present(NoConnectionInfo))((_, c) => connectAndReceiveFirst(c)).map { first =>
            // Slack chooses the connection count and the app id: the case and a count of at least one are compared.
            val connections = first match
                case hello: SlackEnvelope.Hello => Present(hello.numConnections)
                case _                          => Absent
            assert(connections.exists(_ >= 1), s"the first envelope is hello, got: $first")
        }
    }

    "posting a message answers its timestamp" in {
        live()((_, c) => postAndDelete(c)).map { case (ts, deleted) =>
            // Slack chooses the timestamp: its shape, seconds and a fraction, is compared.
            assert(ts.value.matches("[0-9]+\\.[0-9]+"), s"got: $ts")
            assert(deleted == Result.succeed(()), s"the posted message is deleted, got: $deleted")
        }
    }

    "a message the bot posts to its channel arrives as an event" in {
        // deviation: real Slack delivery latency. The event arrives over Slack's network, so the wait is bounded by
        // EventWait on the real clock, and an expiry reports the missing event as a failure.
        live(realOnly = Present("its event for the bot's own post is a bot_message carrying bot_id and no user"))((_, c) =>
            receiveOwnPost(c, Present(EventWait))
        ).map { outcome =>
            assert(outcome.event == Present(outcome.expected), s"got: ${outcome.event}")
            assert(outcome.deleted == Result.succeed(()), s"the posted message is deleted, got: ${outcome.deleted}")
        }
    }

    "a Web API error is the operation's leaf" in {
        live(realOnly = Present("chat.postMessage accepts any channel id"))((_, c) => postToMissingChannel(c)).map { result =>
            // Slack chooses whether to send response_metadata messages: the leaf and its method are compared.
            assert(
                result.failure.collect { case e: SlackChannelNotFoundException => e.method } == Present("chat.postMessage"),
                s"got: $result"
            )
        }
    }

    // chat.postMessage's reference page does not list msg_too_long. Passing confirms the page; failing with
    // SlackOtherApiException(code = msg_too_long) means SlackMsgTooLongException belongs on SlackSendFailure.
    "a text over 40,000 characters is accepted by chat.postMessage, not answered with msg_too_long" in {
        live(realOnly = Present("it keeps a text of any length; the leaf checks Slack's documented limit"))((_, c) => postLongText(c)).map {
            case (posted, deleted) =>
                assert(posted.isSuccess, s"got: $posted")
                assert(deleted == Result.succeed(()), s"the posted message is deleted, got: $deleted")
        }
    }

    "identity answers the user the bot posts as" in {
        live() { (_, c) =>
            marker.map { text =>
                Slack.run(c.config) {
                    for
                        me      <- Slack.identity
                        ts      <- Slack.send(SlackMessage(c.channel, text))
                        found   <- history(c).map(h => Maybe.fromOption(h.find(_.ts == ts.value)))
                        deleted <- delete(c.channel, ts)
                    yield
                        assert(found.flatMap(_.user) == Present(me.userId.value), s"got: $found")
                        assert(deleted == Result.succeed(()), s"the posted message is deleted, got: $deleted")
                }
            }
        }
    }

    "edit replaces the text the channel's history shows for the message" in {
        live() { (_, c) =>
            marker.map { text =>
                Slack.run(c.config) {
                    for
                        ts      <- Slack.send(SlackMessage(c.channel, s"$text before"))
                        edited  <- Slack.edit(c.channel, ts, SlackMessage(c.channel, s"$text after"))
                        found   <- history(c).map(h => Maybe.fromOption(h.find(_.ts == ts.value)))
                        deleted <- delete(c.channel, ts)
                    yield
                        assert((edited, found.flatMap(_.text)) == (ts, Present(s"$text after")), s"got: $edited, $found")
                        assert(deleted == Result.succeed(()), s"the edited message is deleted, got: $deleted")
                }
            }
        }
    }

    // --- Interactive: a person acts in the channel, and receive delivers what they did ---

    "a slash command arrives with its command and channel, and its trigger opens a modal that updateView replaces" in {
        live() { (t, c) =>
            interactive(t).andThen {
                receiving(t, c, bare) { (client, received) =>
                    for
                        ran     <- command(t, c, received, "view")
                        opened  <- Slack.run(client)(Slack.openView(ran.triggerId, modal("opened")))
                        updated <- Slack.run(client)(Slack.updateView(opened, modal("updated")))
                    yield assert((ran.command, ran.channel, updated) == (SlashCommand, c.channel, opened))
                }
            }
        }
    }

    "a slash command's in-channel response, carried by its ack, is posted in the channel" in {
        live() { (t, c) =>
            interactive(t).andThen {
                marker.map { text =>
                    val reply: [X] => SlackEnvelope[X] => X = [X] =>
                        (env: SlackEnvelope[X]) =>
                            env match
                                case _: SlackEnvelope.SlashCommand =>
                                    SlackAck.CommandResponse(SlackAck.CommandResponse.Visibility.InChannel, text)
                                case other => answer(other)
                    receiving(t, c, reply) { (client, received) =>
                        for
                            _      <- command(t, c, received, "respond")
                            posted <- Slack.run(client)(postedWith(c, text))
                        yield assert(posted.flatMap(_.text) == Present(text), s"got: $posted")
                    }
                }
            }
        }
    }

    "respondInChannel answers through a slash command's response_url, in the channel" in {
        live() { (t, c) =>
            interactive(t).andThen {
                marker.map { text =>
                    receiving(t, c, bare) { (client, received) =>
                        for
                            ran    <- command(t, c, received, "url")
                            url    <- Abort.get(ran.responseUrl)
                            _      <- Slack.run(client)(Slack.respondInChannel(url, SlackReply(text)))
                            posted <- Slack.run(client)(postedWith(c, text))
                        yield assert(posted.flatMap(_.text) == Present(text), s"got: $posted")
                    }
                }
            }
        }
    }

    "sendEphemeral to the person who ran a slash command answers the message's timestamp" in {
        live() { (t, c) =>
            interactive(t).andThen {
                receiving(t, c, bare) { (client, received) =>
                    for
                        ran <- command(t, c, received, "ephemeral")
                        ts  <- Slack.run(client)(Slack.sendEphemeral(SlackMessage(c.channel, "kyo-slack live suite: only you"), ran.user))
                    yield assert(ts.value.matches("[0-9]+\\.[0-9]+"), s"got: $ts")
                }
            }
        }
    }

    "an ephemeral message is not in the channel's history" in {
        live(realOnly = Present("chat.postEphemeral stores its message in the channel's history")) { (t, c) =>
            interactive(t).andThen {
                marker.map { text =>
                    receiving(t, c, bare) { (client, received) =>
                        for
                            ran <- command(t, c, received, "ephemeral")
                            ts  <- Slack.run(client)(Slack.sendEphemeral(SlackMessage(c.channel, text), ran.user))
                            h   <- Slack.run(client)(history(c))
                        yield assert(!h.exists(m => m.ts == ts.value || m.text == Present(text)), s"got: $h")
                    }
                }
            }
        }
    }

    "a button press arrives as BlockActions with its action and the message it was in" in {
        live() { (t, c) =>
            interactive(t).andThen {
                receiving(t, c, bare) { (client, received) =>
                    for
                        ts    <- Slack.run(client)(Slack.send(buttonMessage(c)))
                        acted <- act(t, c, "press the Press button above")(_.presses(ts, PressBlock, PressAction, PressValue))
                        press <- answered(received, acted) {
                            case SlackEnvelope.Interactive(_, b: SlackInteraction.BlockActions, _, _, _) if b.messageTs == Present(ts) => b
                        }
                        deleted <- Slack.run(client)(delete(c.channel, ts))
                    yield
                        assert(
                            (press.actions, press.channel) ==
                                (Chunk(pressed), Present(c.channel)),
                            s"got: $press"
                        )
                        assert(deleted == Result.succeed(()), s"the button message is deleted, got: $deleted")
                }
            }
        }
    }

    "replaceOriginal through a button press's response_url replaces the message" in {
        live(realOnly = Present("its block_actions carry no response_url")) { (t, c) =>
            interactive(t).andThen {
                marker.map { text =>
                    receiving(t, c, bare) { (client, received) =>
                        for
                            ts    <- Slack.run(client)(Slack.send(buttonMessage(c)))
                            acted <- act(t, c, "press the Press button above")(_.presses(ts, PressBlock, PressAction, PressValue))
                            press <- answered(received, acted) {
                                case SlackEnvelope.Interactive(_, b: SlackInteraction.BlockActions, _, _, _)
                                    if b.messageTs == Present(ts) => b
                            }
                            url      <- Abort.get(press.responseUrl)
                            _        <- Slack.run(client)(Slack.replaceOriginal(url, SlackReply(text)))
                            replaced <- Slack.run(client)(postedWith(c, text))
                            _        <- Slack.run(client)(delete(c.channel, ts))
                        yield assert(replaced.map(_.ts) == Present(ts.value), s"got: $replaced")
                    }
                }
            }
        }
    }

    "submitting a modal arrives as ViewSubmission with the view's id, and is acked with Clear" in {
        val reply: [X] => SlackEnvelope[X] => X = [X] =>
            (env: SlackEnvelope[X]) =>
                env match
                    case SlackEnvelope.Interactive(_, _: SlackInteraction.ViewSubmission, _, _, _) =>
                        SlackAck.ViewResponse(SlackAck.ViewAction.Clear)
                    case other => answer(other)
        live() { (t, c) =>
            interactive(t).andThen {
                receiving(t, c, reply) { (client, received) =>
                    for
                        ran    <- command(t, c, received, "submit")
                        opened <- Slack.run(client)(Slack.openView(ran.triggerId, modal("submit me")))
                        acted  <- act(t, c, "submit the kyo-slack live dialog")(_.submits(opened))
                        view   <- answered(received, acted) {
                            case SlackEnvelope.Interactive(_, s: SlackInteraction.ViewSubmission, _, _, _) if s.view.id == opened => s.view
                        }
                    yield assert(view.id == opened)
                }
            }
        }
    }

    // --- The person and the channel ---

    /** On a real workspace an interactive leaf waits for a person, so it runs only when one is there. */
    private def interactive(t: Target)(using Frame): Unit < Sync =
        t match
            case Target.Emulator(_) => ()
            case Target.Real(_)     =>
                System.env[String](InteractiveVariable).map {
                    case Present(_) => ()
                    case Absent     => cancel(s"$InteractiveVariable not set: this leaf waits for a person in the channel")
                }

    /** The person does what `instruction` asks. On a real workspace the suite posts it in the channel, removed when the leaf ends, and
      * the person acts. On the container `emulated` does it through the control API, on a fiber: the emulator answers only once the bot
      * has acknowledged the envelope, so the leaf joins the fiber after it has received the envelope.
      */
    private def act(t: Target, c: Credentials, instruction: String)(emulated: SlackLiveServer => Unit < (Async & Abort[HttpException]))(
        using Frame
    ): Fiber[Unit, Abort[HttpException]] < (Async & Scope & Abort[SlackException]) =
        t match
            case Target.Emulator(server) => Fiber.init(emulated(server))
            case Target.Real(_)          =>
                Slack.run(c.config)(Slack.send(SlackMessage(c.channel, s"kyo-slack live suite: $instruction"))).map { ts =>
                    Scope.ensure(Slack.run(c.config)(delete(c.channel, ts)).unit)
                        .andThen(Fiber.init(Kyo.unit))
                }

    /** The envelope `pick` takes first, once the person's action is done; an action that fails before the envelope arrives fails the
      * leaf instead of leaving it waiting. The race is the action's only join: the branch that loses is interrupted with its join.
      */
    private def answered[A](received: Channel[SlackEnvelope[?]], acted: Fiber[Unit, Abort[HttpException]])(
        pick: PartialFunction[SlackEnvelope[?], A]
    )(using Frame): A < (Async & Abort[Closed | HttpException]) =
        Async.raceFirst(untilFirst(received)(pick).map(a => acted.get.andThen(a)), acted.get.andThen(Async.never[A]))

    /** The person runs the slash command with `text`, answering the command once its envelope has arrived and the action is done. */
    private def command(t: Target, c: Credentials, received: Channel[SlackEnvelope[?]], text: String)(using
        Frame
    ): SlackCommand < (Async & Scope & Abort[SlackException | Closed | HttpException]) =
        act(t, c, s"run $SlashCommand $text in this channel")(_.runs(SlashCommand, text)).map { acted =>
            answered(received, acted) {
                case SlackEnvelope.SlashCommand(_, ran, _, _, _) if ran.command == SlashCommand && ran.text == text => ran
            }
        }

    /** Runs `f` with a client on `c` whose receive loop records every envelope, answers it with `reply`, and has delivered hello. */
    private def receiving[A](t: Target, c: Credentials, reply: [X] => SlackEnvelope[X] => X)(
        f: (Slack, Channel[SlackEnvelope[?]]) => A < (Async & Scope & Abort[Any])
    )(using Frame): A < (Async & Scope & Abort[Any]) =
        // hello marks the connection as up before the leaf asks the person to act; the emulator's hello lacks `connection_info`, so
        // the module delivers it as an unknown frame, and on the container that frame is the same mark.
        val connected: PartialFunction[SlackEnvelope[?], Unit] = t match
            case Target.Real(_)     => { case _: SlackEnvelope.Hello => () }
            case Target.Emulator(_) => {
                case _: SlackEnvelope.Hello                               => ()
                case f: SlackEnvelope.UnknownFrame if f.`type` == "hello" => ()
            }
        for
            received <- Channel.init[SlackEnvelope[?]](64)
            client   <- c.init
            _        <- Fiber.init(Abort.run[SlackException](Slack.run(client)(Slack.receive([Y] =>
                (env: SlackEnvelope[Y]) => Abort.run[Closed](received.put(env)).andThen(reply[Y](env))
            ))))
            _ <- untilFirst(received)(connected)
            a <- f(client, received)
        yield a
        end for
    end receiving

    private val NoConnectionInfo = "its hello carries no connection_info, so the module delivers it as an unknown frame"

    /** The bare answer of every envelope. */
    private val bare: [X] => SlackEnvelope[X] => X = [X] => (env: SlackEnvelope[X]) => answer(env)

    private def marker(using Frame): String < Sync =
        Random.nextStringAlphanumeric(16).map(s => s"kyo-slack live suite: $s")

    private def modal(text: String): SlackView =
        import SlackBlock.dsl.*
        SlackView(
            SlackView.Type.Modal,
            blocks = blocks(section(text)),
            callbackId = Present("kyo-live"),
            title = Present("kyo-slack live"),
            submit = Present("Submit")
        )
    end modal

    private def buttonMessage(c: Credentials): SlackMessage =
        SlackMessage(
            c.channel,
            "kyo-slack live suite: a button",
            blocks = Chunk(SlackBlock.Actions(
                Chunk(SlackBlock.Element.Button("Press", SlackId.ActionId(PressAction), value = Present(PressValue))),
                blockId = Present(SlackId.BlockId(PressBlock))
            ))
        )

    private val pressed: SlackInteraction.Action =
        SlackInteraction.Action(SlackId.ActionId(PressAction), SlackId.BlockId(PressBlock), Present(PressValue))

end SlackLiveTest

object SlackLiveTest:

    val AppTokenVariable = "SLACK_APP_TOKEN"
    val BotTokenVariable = "SLACK_BOT_TOKEN"
    val ChannelVariable  = "SLACK_CHANNEL_ID"

    val InteractiveVariable = "SLACK_INTERACTIVE"

    /** The slash command the interactive leaves ask the person to run: the app on a real workspace declares it. */
    val SlashCommand = "/kyo-live"

    val PressBlock  = "kyo-live-block"
    val PressAction = "kyo-live-press"
    val PressValue  = "pressed"

    case class HistoryBody(channel: SlackId.ChannelId, limit: Int) derives Schema
    case class HistoryMessage(ts: String, text: Maybe[String] = Absent, user: Maybe[String] = Absent) derives CanEqual, Schema
    case class History(messages: Chunk[HistoryMessage]) derives Schema

    /** The channel's latest messages, newest first. */
    def history(c: Credentials)(using Frame): Chunk[HistoryMessage] < (Async & Abort[SlackException] & Env[Slack]) =
        Slack.custom[HistoryBody, History](SlackLiterals.methodOf("conversations.history"), HistoryBody(c.channel, 50)).map(_.messages)

    /** The message whose text is `text`, once the channel's history shows it, deleted after it is read.
      *
      * deviation: Slack posts a command's response and a `response_url`'s message after answering, so the history is read on the real
      * clock every `HistoryPoll`, up to `HistoryPolls` times; an expiry answers `Absent`, which the leaf reports as a failure.
      */
    def postedWith(c: Credentials, text: String)(using Frame): Maybe[HistoryMessage] < (Async & Abort[SlackException] & Env[Slack]) =
        Loop(1) { attempt =>
            history(c).map(h => Maybe.fromOption(h.find(_.text == Present(text)))).map {
                case Present(m) =>
                    delete(c.channel, SlackTs(m.ts)).andThen(Loop.done(Present(m)))
                case Absent if attempt >= HistoryPolls => Loop.done(Absent)
                case Absent                            => Async.sleep(HistoryPoll).andThen(Loop.continue(attempt + 1))
            }
        }

    val HistoryPoll: Duration = 250.millis
    val HistoryPolls: Int     = 120

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
        Abort.run[SlackException](Slack.custom[DeleteBody, Ok](SlackLiterals.methodOf("chat.delete"), DeleteBody(channel, ts)).unit)

    /** Open a connection, and answer the first envelope it delivers. */
    def connectAndReceiveFirst(c: Credentials)(using Frame): SlackEnvelope[?] < (Async & Abort[SlackException | Closed]) =
        Scope.run {
            for
                received <- Channel.init[SlackEnvelope[?]](16)
                client   <- c.init
                loop     <- Fiber.initUnscoped(Abort.run[SlackException](Slack.run(client)(Slack.receive(recordInto(received)))))
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
        Slack.run(c.config) {
            for
                ts      <- Slack.send(SlackMessage(c.channel, "kyo-slack live suite: posting"))
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
                identity <- Slack.run(client)(Slack.identity)
                loop     <- Fiber.initUnscoped(Abort.run[SlackException](Slack.run(client)(Slack.receive(recordInto(received)))))
                _        <- untilFirst(received) { case h: SlackEnvelope.Hello => h }
                ts       <- Slack.run(client)(Slack.send(SlackMessage(c.channel, marker)))
                ownPost = untilFirst(received) {
                    case SlackEnvelope.EventsApi(_, SlackEnvelope.EventsApi.Payload(_, m: SlackEvent.Message), _, _, _)
                        if m.text == marker => m
                }
                event <- eventWait match
                    case Present(wait) => Abort.run[Timeout](Async.timeout(wait)(ownPost)).map(_.toMaybe)
                    case Absent        => ownPost.map(Present(_))
                deleted <- Slack.run(client)(delete(c.channel, ts))
                _       <- Slack.close(client)
                _       <- loop.get
            yield OwnPost(SlackEvent.Message(c.channel, identity.userId, marker, ts), event, deleted)
        }

    /** Post to a channel that does not exist, and answer the outcome. */
    def postToMissingChannel(c: Credentials)(using Frame): Result[SlackException, SlackTs] < Async =
        Slack.run(c.config) {
            Abort.run[SlackException](Slack.send(SlackMessage(MissingChannel, "kyo-slack live suite: missing channel")))
        }

    /** Post a text longer than `chat.postMessage` keeps, delete it if it was posted, and answer both outcomes. */
    def postLongText(c: Credentials)(using Frame): (Result[SlackException, SlackTs], Result[SlackException, Unit]) < Async =
        Slack.run(c.config) {
            Abort.run[SlackException](Slack.send(SlackMessage(c.channel, "x" * LongTextLength))).map { posted =>
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
