package kyo

import kyo.internal.slack.Methods
import kyo.internal.slack.Reconnect
import kyo.internal.slack.SocketEngine
import kyo.internal.slack.Transport
import kyo.internal.slack.WebApi

/** The client of a Slack app, the value every verb requires: `Env[Slack]` is on each verb's row, so a
  * verb used outside a region that provides a client does not compile.
  *
  * Only the module builds one, from a [[kyo.SlackConfig]], and each builder releases what it holds when
  * its region ends: `Slack.run(config)(handler)` runs the receive loop, `Slack.let(config)(v)` provides a
  * client to `v`, and `Slack.init(config)` opens a Socket Mode connection the caller holds in a `Scope`,
  * provided to a computation by `Slack.use`. A client holds the config, its own kyo-http
  * `HttpClient` (never the caller's, so no caller filter, base url or relaxed TLS reaches a request that
  * carries a token), and, when built by `init` or `run`, its Socket Mode connection.
  *
  * The verbs are functions on the companion. A client is never a receiver of calls: it carries nothing a
  * caller reads, and it cannot be constructed outside the module.
  *
  * @see
  *   [[kyo.SlackConfig]] the config
  * @see
  *   [[kyo.SlackEnvelope]] what arrives, indexed by the answer each envelope requires
  * @see
  *   [[kyo.SlackAck]] the answer to an acknowledgeable envelope
  * @see
  *   [[kyo.SlackException]] the failures
  */
final class Slack private[kyo] (
    private[kyo] val config: SlackConfig,
    private[kyo] val http: HttpClient,
    private[kyo] val transport: Transport,
    // The controller, not an engine: its active ref follows every rotation, so each receive reads, and close closes, the
    // connection current at that moment rather than the first one.
    private[kyo] val connection: Maybe[Reconnect.Controller]
)

/** The entry points and verbs of kyo-slack. */
object Slack:

    /** The `auth.test` result: the bot's user/team ids, the bot id, and the
      * workspace url. It has no `Schema`: the module decodes Slack's answer through its own
      * wire type, and the companion's given makes `summon[Schema[Identity]]` a compile error.
      */
    final case class Identity(
        userId: SlackId.UserId,
        teamId: SlackId.TeamId,
        botId: SlackId.BotId,
        url: HttpUrl
    ) derives CanEqual

    object Identity:
        inline given noSchema: Schema[Identity] = compiletime.error(
            "Slack.Identity has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
        )
    end Identity

    // --- Entry points ---

    /** Opens a Socket Mode connection on `config` and runs the receive loop until it ends: acks each envelope that
      * takes an answer, exactly once, with the `SlackAck` the handler returns; rotates the connection on a routine
      * disconnect per `config.reconnect`; ends on `link_disabled` with `SlackLinkDisabledException`, and cleanly on a
      * routine disconnect under `Reconnect.Off`. The client and every connection it opened are closed when the loop ends.
      *
      * The handler answers each envelope with what the envelope requires (see [[kyo.SlackEnvelope]]) and runs with the
      * client provided, so it calls the verbs directly. `E` is what it can fail with and `S` its other effects, inferred
      * from it. A handler failure ends the loop with that failure and leaves its envelope unacknowledged. A handler panic
      * is logged at error with the envelope's type and id and leaves the envelope unacknowledged, so Slack redelivers
      * it, and the loop goes on. The handler is bounded by `config.ackDeadline`: past it the bare ack goes out and the
      * handler is interrupted, so long work belongs on a fiber the handler forks.
      */
    def run[E, S](using
        Isolate[S, Abort[E] & Async, S]
    )(config: SlackConfig)(
        handler: [A] => SlackEnvelope[A] => A < (Async & Abort[E] & Env[Slack] & S)
    )(using Frame): Unit < (Async & Abort[SlackRunFailure | E] & S) =
        runOver(Transport.live(_, config))(config)(handler)

    private[kyo] def runOver[E, S](using
        Isolate[S, Abort[E] & Async, S]
    )(transport: HttpClient => Transport)(config: SlackConfig)(
        handler: [A] => SlackEnvelope[A] => A < (Async & Abort[E] & Env[Slack] & S)
    )(using Frame): Unit < (Async & Abort[SlackRunFailure | E] & S) =
        Scope.run(Scope.acquireRelease(open(config, transport))(close).map(client => loop(client, handler)))

    /** Builds a client on `config` for the duration of `v`, releasing it afterwards. The client opens no Socket Mode
      * connection, so building it cannot fail: it is the form for the Web API and `response_url` verbs. A `receive`
      * inside `v` opens a connection for its own duration.
      */
    def let[A, S](config: SlackConfig)(v: A < (S & Env[Slack]))(using Frame): A < (S & Async) =
        letOver(Transport.live(_, config))(config)(v)

    private[kyo] def letOver[A, S](transport: HttpClient => Transport)(config: SlackConfig)(v: A < (S & Env[Slack]))(using
        Frame
    ): A < (S & Async) =
        Scope.run(Scope.acquireRelease(build(config, transport))(close).map(client => use(client)(v)))

    /** A client on `config` with its Socket Mode connection open, closed when the enclosing `Scope` ends, so the
      * socket, its background fibers and the client's `HttpClient` never outlive it. `use` provides it to a computation;
      * `receive` there runs the loop on the held connection.
      */
    def init(config: SlackConfig)(using Frame): Slack < (Async & Abort[SlackInitFailure] & Scope) =
        Scope.acquireRelease(initUnscoped(config))(close)

    /** A client on `config` with its Socket Mode connection open, which nothing closes but the caller's `close`: the
      * form for a client whose lifetime no `Scope` describes. Prefer `init`.
      */
    def initUnscoped(config: SlackConfig)(using Frame): Slack < (Async & Abort[SlackInitFailure]) =
        open(config, Transport.live(_, config))

    private[kyo] def initUnscopedOver(transport: HttpClient => Transport)(config: SlackConfig)(using
        Frame
    ): Slack < (Async & Abort[SlackInitFailure]) =
        open(config, transport)

    /** Closes `client`: its connection (the one active now, after any rotation) and then its `HttpClient`, without
      * waiting for requests in flight. For an `initUnscoped` client, or to close an `init` one before its scope ends.
      * Idempotent and total.
      */
    def close(client: Slack)(using Frame): Unit < Async =
        client.connection.fold(Kyo.unit)(_.closeActive).andThen(client.http.closeNow)

    /** Provides `client` to `v`. */
    def use[A, S](client: Slack)(v: A < (S & Env[Slack]))(using Frame): A < S =
        Env.run(client)(v)

    /** Runs the receive loop on the provided client, as `run` does. On a client built by `init` it runs on the held
      * connection, which stays the caller's to close; on one built by `let` it opens a connection and closes it when the
      * loop ends.
      */
    def receive[E, S](using
        Isolate[S, Abort[E] & Async, S]
    )(
        handler: [A] => SlackEnvelope[A] => A < (Async & Abort[E] & Env[Slack] & S)
    )(using Frame): Unit < (Async & Abort[SlackReceiveFailure | E] & Env[Slack] & S) =
        Env.get[Slack].map(client => loop(client, handler))

    private def loop[E, S](using
        Isolate[S, Abort[E] & Async, S]
    )(
        client: Slack,
        handler: [A] => SlackEnvelope[A] => A < (Async & Abort[E] & Env[Slack] & S)
    )(using Frame): Unit < (Async & Abort[SlackException.Connect | SlackLinkDisabledException | E] & S) =
        val answer = answering(client, handler)
        val reopen = () => openEngine(client)
        client.connection match
            case Present(controller) => controller.start(answer)
            case Absent              =>
                Scope.run(Scope.acquireRelease(Reconnect.open(reopen, client.config))(_.closeActive).map(_.start(answer)))
        end match
    end loop

    /** The handler as the engine calls it: one `SlackAck` per envelope, with the client provided. Matching each case
      * fixes the answer's type. `Hello`, `Disconnect` and `UnknownFrame` carry no envelope id, so the engine never acks
      * them and the bare ack returned for them is unused.
      */
    private def answering[E, S](
        client: Slack,
        handler: [A] => SlackEnvelope[A] => A < (Async & Abort[E] & Env[Slack] & S)
    )(using Frame): SlackEnvelope[?] => SlackAck < (Async & Abort[E] & S) =
        env =>
            use(client) {
                env match
                    case e: SlackEnvelope.EventsApi    => handler(e)
                    case e: SlackEnvelope.Interactive  => handler(e)
                    case e: SlackEnvelope.SlashCommand => handler(e)
                    case e: SlackEnvelope.Unknown      => handler(e)
                    case e: SlackEnvelope.Hello        => handler(e).andThen(SlackAck.Ack)
                    case e: SlackEnvelope.Disconnect   => handler(e).andThen(SlackAck.Ack)
                    case e: SlackEnvelope.UnknownFrame => handler(e).andThen(SlackAck.Ack)
            }

    /** A client with no connection: its own `HttpClient` and the transport over it. */
    private def build(config: SlackConfig, transport: HttpClient => Transport)(using Frame): Slack < Sync =
        HttpClient.initUnscoped().map(http => new Slack(config, http, transport(http), Absent))

    /** A client with its first connection open. A failed or interrupted open closes the `HttpClient`, since nothing
      * else holds it yet.
      */
    private def open(config: SlackConfig, transport: HttpClient => Transport)(using
        Frame
    ): Slack < (Async & Abort[SlackException.Connect]) =
        build(config, transport).map { bare =>
            AtomicBoolean.init(false).map { built =>
                Scope.run {
                    Scope.ensure(built.get.map(done => if done then Kyo.unit else bare.http.closeNow)).andThen {
                        openEngine(bare).map(engine => Reconnect.controllerFrom(engine, () => openEngine(bare), config)).map {
                            controller => built.set(true).andThen(new Slack(config, bare.http, bare.transport, Present(controller)))
                        }
                    }
                }
            }
        }

    private inline val ConnectionsOpen = "apps.connections.open"

    /** Obtain a Socket Mode url with `apps.connections.open` (app-level token) and open an engine on it over the
      * client's transport. Used for the first connection and for every rotation.
      */
    private[kyo] def openEngine(client: Slack)(using Frame): SocketEngine < (Async & Abort[SlackException.Connect]) =
        WebApi.send[Methods.AppsConnectionsOpenAnswer, SlackException.Connect](
            client,
            ConnectionsOpen,
            client.config.appLevel.value,
            Json.encode(Methods.AppsConnectionsOpen())
        ).map { resp =>
            socketUrl(resp.url) match
                case Present(target) => SocketEngine.initUnscoped(client.transport, target, client.config)
                case Absent          => Abort.fail(SlackRefusedUrlException(Transport.SocketConnect))
        }

    /** The Socket Mode url when it is an absolute wss url on a host in printable ASCII. The url is the connection's ticket, so
      * it goes nowhere else, and never over a plain `ws` connection, which would send it in clear; Slack answers `wss` only.
      */
    private def socketUrl(url: String)(using Frame): Maybe[HttpUrl] =
        def asciiLower(s: String): String = s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)
        HttpUrl.parse(url) match
            case Result.Success(target)
                if target.scheme.map(asciiLower).contains("wss") &&
                    target.host.nonEmpty && target.unixSocket.isEmpty && target.full.forall(c => c >= '!' && c <= '~') =>
                Present(target)
            case _ => Absent
        end match
    end socketUrl

    // --- Web API ---

    /** The bot's identity, which also checks that the bot token works. */
    def authTest(using Frame): Identity < (Async & Abort[SlackAuthTestFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.request[Methods.AuthTest, Methods.AuthTestAnswer, SlackAuthTestFailure](client, "auth.test", Methods.AuthTest()).map {
                r =>
                    def rejected(field: String) =
                        Abort.fail(SlackDecodeException(
                            "auth.test",
                            SlackDecodeException.Part.Payload,
                            SlackDecodeException.Failure.ConstructorRejected,
                            Chunk(field),
                            Absent
                        ))
                    if r.user_id.value.isEmpty then rejected("user_id")
                    else if r.team_id.value.isEmpty then rejected("team_id")
                    else if r.bot_id.value.isEmpty then rejected("bot_id")
                    else
                        HttpUrl.parse(r.url) match
                            case Result.Success(url) if SlackConfig.absoluteProblemOf(url).isEmpty =>
                                Identity(r.user_id, r.team_id, r.bot_id, url)
                            case _ => rejected("url")
                    end if
            }
        }

    /** Posts `message` and answers its timestamp. */
    def chatPostMessage(message: SlackMessage)(using Frame): SlackTs < (Async & Abort[SlackChatPostMessageFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.request[PostMessageBody, Methods.ChatPostMessageAnswer, SlackChatPostMessageFailure](
                client,
                "chat.postMessage",
                PostMessageBody(message.channel, message.text, messageBlocks(message), message.threadTs)
            ).map(_.ts)
        }

    /** Posts `message` so only `user` sees it, and answers its timestamp. */
    def chatPostEphemeral(message: SlackMessage, user: SlackId.UserId)(using
        Frame
    ): SlackTs < (Async & Abort[SlackChatPostEphemeralFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.request[EphemeralBody, Methods.ChatPostEphemeralAnswer, SlackChatPostEphemeralFailure](
                client,
                "chat.postEphemeral",
                EphemeralBody(message.channel, user, message.text, messageBlocks(message), message.threadTs)
            ).map(_.message_ts)
        }

    /** Replaces the text and blocks of the message at `ts` in `channel`. */
    def chatUpdate(channel: SlackId.ChannelId, ts: SlackTs, message: SlackMessage)(using
        Frame
    ): SlackTs < (Async & Abort[SlackChatUpdateFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.request[UpdateBody, Methods.ChatUpdateAnswer, SlackChatUpdateFailure](
                client,
                "chat.update",
                UpdateBody(channel, ts, message.text, messageBlocks(message))
            ).map(_.ts)
        }

    /** Opens `view` as a modal, keyed by the trigger of the interaction that asked for it. */
    def viewsOpen(triggerId: SlackId.TriggerId, view: SlackView)(using
        Frame
    ): SlackId.ViewId < (Async & Abort[SlackViewsOpenFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.request[ViewsOpenBody, Methods.ViewsOpenAnswer, SlackViewsOpenFailure](
                client,
                "views.open",
                ViewsOpenBody(triggerId, encodeView(view))
            ).map(_.view.id)
        }

    /** Replaces the content of the open view `viewId`. */
    def viewsUpdate(viewId: SlackId.ViewId, view: SlackView)(using
        Frame
    ): SlackId.ViewId < (Async & Abort[SlackViewsUpdateFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.request[ViewsUpdateBody, Methods.ViewsUpdateAnswer, SlackViewsUpdateFailure](
                client,
                "views.update",
                ViewsUpdateBody(viewId, encodeView(view))
            ).map(_.view.id)
        }

    /** Publishes `view` as `user`'s App Home. */
    def viewsPublish(user: SlackId.UserId, view: SlackView)(using
        Frame
    ): SlackId.ViewId < (Async & Abort[SlackViewsPublishFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.request[ViewsPublishBody, Methods.ViewsPublishAnswer, SlackViewsPublishFailure](
                client,
                "views.publish",
                ViewsPublishBody(user, encodeView(view))
            ).map(_.view.id)
        }

    /** Calls a Web API method the module does not model: `payload` is encoded as the JSON request, and the answer
      * decoded as `Out`.
      */
    def custom[In: Schema, Out: Schema](method: SlackMethod, payload: In)(using
        Frame
    ): Out < (Async & Abort[SlackCustomFailure] & Env[Slack]) =
        Env.get[Slack].map(client => WebApi.request[In, Out, SlackCustomFailure](client, method.value, payload))

    // --- Answers through a response_url ---

    /** Posts `reply` through `url`, visible only to the person who acted. */
    def respondEphemeral(url: SlackResponseUrl, reply: SlackReply)(using
        Frame
    ): Unit < (Async & Abort[SlackRespondEphemeralFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.respond[SlackRespondEphemeralFailure](
                client,
                url,
                Json.encode(EphemeralReplyBody("ephemeral", false, reply.text, replyBlocks(reply)))
            )
        }

    /** Posts `reply` through `url` for everyone in the conversation, in the thread `threadTs` when it is present. */
    def respondInChannel(url: SlackResponseUrl, reply: SlackReply, threadTs: Maybe[SlackTs] = Absent)(using
        Frame
    ): Unit < (Async & Abort[SlackRespondInChannelFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.respond[SlackRespondInChannelFailure](
                client,
                url,
                Json.encode(InChannelReplyBody("in_channel", false, reply.text, replyBlocks(reply), threadTs))
            )
        }

    /** Puts `reply` in place of the message the interaction behind `url` came from. */
    def replaceOriginal(url: SlackResponseUrl, reply: SlackReply)(using
        Frame
    ): Unit < (Async & Abort[SlackReplaceOriginalFailure] & Env[Slack]) =
        Env.get[Slack].map { client =>
            WebApi.respond[SlackReplaceOriginalFailure](client, url, Json.encode(ReplaceReplyBody(true, reply.text, replyBlocks(reply))))
        }

    /** Deletes the message the interaction behind `url` came from. */
    def deleteOriginal(url: SlackResponseUrl)(using Frame): Unit < (Async & Abort[SlackDeleteOriginalFailure] & Env[Slack]) =
        Env.get[Slack].map(client => WebApi.respond[SlackDeleteOriginalFailure](client, url, Json.encode(DeleteReplyBody(true))))

    // Request bodies carry `blocks` as the rendered Block Kit value (`SlackBlock.encode`), not as
    // `Chunk[SlackBlock]`: the public blocks are not Slack's wire shape, so the body holds their rendering.

    final private[kyo] case class PostMessageBody(
        channel: SlackId.ChannelId,
        text: String,
        blocks: Maybe[Structure.Value],
        thread_ts: Maybe[SlackTs]
    ) derives Schema

    // chat.postEphemeral request body.
    final private[kyo] case class EphemeralBody(
        channel: SlackId.ChannelId,
        user: SlackId.UserId,
        text: String,
        blocks: Maybe[Structure.Value],
        thread_ts: Maybe[SlackTs]
    ) derives Schema

    // chat.update request body.
    final private[kyo] case class UpdateBody(
        channel: SlackId.ChannelId,
        ts: SlackTs,
        text: String,
        blocks: Maybe[Structure.Value]
    ) derives Schema

    // The `view` object the views.* methods carry: the Slack API wire shape for a
    // modal/home view. The public SlackView (camelCase, raw blocks string) maps here.
    final private[kyo] case class ViewBody(
        `type`: SlackView.Type,
        callback_id: Maybe[String],
        blocks: Structure.Value,
        title: Maybe[Structure.Value],
        submit: Maybe[Structure.Value],
        close: Maybe[Structure.Value],
        private_metadata: Maybe[String],
        // Emitted only when true: `notify_on_close` is a modal-only field, and a `home` view
        // (or any view sent to views.publish) is rejected with invalid_arguments if it is present.
        notify_on_close: Maybe[Boolean]
    ) derives Schema

    final private[kyo] case class ViewsOpenBody(
        trigger_id: SlackId.TriggerId,
        view: ViewBody
    ) derives Schema

    final private[kyo] case class ViewsUpdateBody(
        view_id: SlackId.ViewId,
        view: ViewBody
    ) derives Schema

    final private[kyo] case class ViewsPublishBody(
        user_id: SlackId.UserId,
        view: ViewBody
    ) derives Schema

    final private[kyo] case class EphemeralReplyBody(
        response_type: String,
        replace_original: Boolean,
        text: String,
        blocks: Maybe[Structure.Value]
    ) derives Schema

    final private[kyo] case class InChannelReplyBody(
        response_type: String,
        replace_original: Boolean,
        text: String,
        blocks: Maybe[Structure.Value],
        thread_ts: Maybe[SlackTs]
    ) derives Schema

    final private[kyo] case class ReplaceReplyBody(replace_original: Boolean, text: String, blocks: Maybe[Structure.Value])
        derives Schema

    final private[kyo] case class DeleteReplyBody(delete_original: Boolean) derives Schema

    /** Render a message's typed `blocks` to their Block Kit value, or `Absent` when the
      * message carries no blocks.
      */
    private[kyo] def messageBlocks(message: SlackMessage): Maybe[Structure.Value] =
        blocksOf(message.blocks)

    private[kyo] def replyBlocks(reply: SlackReply): Maybe[Structure.Value] =
        blocksOf(reply.blocks)

    private[kyo] def blocksOf(blocks: Chunk[SlackBlock]): Maybe[Structure.Value] =
        if blocks.isEmpty then Absent
        else Present(SlackBlock.encode(blocks))

    /** Map the public `SlackView` to the wire `ViewBody`: render the typed `blocks`, wrap the
      * `title`/`submit`/`close` labels as `plain_text` objects, and carry `private_metadata`
      * and `notify_on_close`.
      */
    private[kyo] def encodeView(view: SlackView): ViewBody =
        ViewBody(
            view.`type`,
            view.callbackId,
            SlackBlock.encode(view.blocks),
            view.title.map(SlackBlock.plainText),
            view.submit.map(SlackBlock.plainText),
            view.close.map(SlackBlock.plainText),
            view.privateMetadata,
            Maybe.when(view.notifyOnClose)(true)
        )

end Slack
