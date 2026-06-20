package kyo

import kyo.internal.SlackRawJson
import kyo.internal.SlackSocketEngine
import kyo.internal.SlackTransport
import kyo.internal.SlackWebApi
import kyo.internal.SlackWire

/** A live Socket Mode connection: open the socket with `Slack.run` (scope-managed)
  * or `Slack.init` (manually-managed), then drive it with `receive` and tear it down
  * with `close`. Opaque so a caller cannot fabricate one; the representation wraps the
  * internal engine handle, which carries the live socket engine, the config, and the
  * reconnect-controller slot `receive` populates and `close` reads.
  */
opaque type Slack = kyo.internal.SlackSocketHandle

/** The public entry object for kyo-slack Socket Mode. Contains the bot identity
  * model, the connection entry points, and the Web API methods that send messages
  * and manage views from within a connected handler.
  */
object Slack:

    /** The `auth.test` result: the bot's user/team ids, the bot id, and the
      * workspace url.
      */
    case class Identity(
        userId: SlackId.UserId,
        teamId: SlackId.TeamId,
        botId: SlackId.BotId,
        url: String
    ) derives Schema, CanEqual

    /** Advanced manually-managed entry: open the socket and return a live `Slack` handle
      * the caller drives with `receive` and tears down with `close`. The bot-token
      * ambient is bound by `Slack.receive` around the loop body (where the handler runs),
      * so the handler's Web API calls resolve the token on the init + receive path
      * exactly as they do under the scoped `run`. The handle carries `config` (with
      * `config.bot`) so `receive` can bind it.
      */
    def init(config: SlackConfig)(using Frame): Slack < (Async & Abort[SlackException]) =
        openEngine(config).map { engine =>
            kyo.internal.SlackSocketHandle.fromEngine(engine, config).map(fromHandle)
        }

    /** Scope-managed Socket Mode connection: open the socket, run the receive loop
      * under the reconnect policy, and tear everything down on scope exit. The
      * handler returns one `SlackAck` per envelope and the framework emits exactly one
      * wire ack from it (structural acking); a routine disconnect rotates transparently
      * per `config.reconnect`; `link_disabled` ends with `SlackTerminalException`.
      *
      * The bot-token ambient is bound around the loop body, so a
      * `Slack.chatPostMessage`/etc. call from inside the handler resolves the token. The
      * scope finalizer closes whatever engine is active at exit, including one a rotation
      * swapped in, so no socket or background fiber leaks.
      */
    def run[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(config: SlackConfig)(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException])
    )(using Frame): Unit < (S & Async & Abort[SlackException] & Scope) =
        SlackWebApi.local.let(Present(config.bot)) {
            Scope.acquireRelease(
                kyo.internal.SlackReconnect.open(() => openEngine(config), config)
            )(_.closeActive).map { controller =>
                controller.start(handler)
            }
        }

    def authTest(using Frame): Slack.Identity < (Async & Abort[SlackException]) =
        SlackWebApi.request[EmptyBody, AuthTestResp]("auth.test", EmptyBody()).map { r =>
            Slack.Identity(
                SlackId.UserId(r.user_id.getOrElse("")),
                SlackId.TeamId(r.team_id.getOrElse("")),
                SlackId.BotId(r.bot_id.getOrElse("")),
                r.url.getOrElse("")
            )
        }

    def chatPostMessage(message: SlackMessage)(using Frame): SlackTs < (Async & Abort[SlackException]) =
        messageBlocks(message).map { blocks =>
            SlackWebApi.request[PostMessageBody, TsResp](
                "chat.postMessage",
                PostMessageBody(message.channel, message.text, blocks, message.threadTs)
            ).map(r => SlackTs(r.ts))
        }

    def chatPostEphemeral(message: SlackMessage, user: SlackId.UserId)(using Frame): SlackTs < (Async & Abort[SlackException]) =
        messageBlocks(message).map { blocks =>
            SlackWebApi.request[EphemeralBody, EphemeralResp](
                "chat.postEphemeral",
                EphemeralBody(message.channel, user, message.text, blocks, message.threadTs)
            ).map(r => SlackTs(r.message_ts))
        }

    def chatUpdate(channel: SlackId.ChannelId, ts: SlackTs, message: SlackMessage)(using Frame): SlackTs < (Async & Abort[SlackException]) =
        messageBlocks(message).map { blocks =>
            SlackWebApi.request[UpdateBody, TsResp](
                "chat.update",
                UpdateBody(channel, ts, message.text, blocks)
            ).map(r => SlackTs(r.ts))
        }

    def viewsOpen(triggerId: SlackId.TriggerId, view: SlackView)(using Frame): SlackId.ViewId < (Async & Abort[SlackException]) =
        encodeView(view).map { v =>
            SlackWebApi.request[ViewsOpenBody, ViewResp]("views.open", ViewsOpenBody(triggerId, v)).map(r => SlackId.ViewId(r.view.id))
        }

    def viewsUpdate(viewId: SlackId.ViewId, view: SlackView)(using Frame): SlackId.ViewId < (Async & Abort[SlackException]) =
        encodeView(view).map { v =>
            SlackWebApi.request[ViewsUpdateBody, ViewResp]("views.update", ViewsUpdateBody(viewId, v)).map(r => SlackId.ViewId(r.view.id))
        }

    def viewsPublish(user: SlackId.UserId, view: SlackView)(using Frame): SlackId.ViewId < (Async & Abort[SlackException]) =
        encodeView(view).map { v =>
            SlackWebApi.request[ViewsPublishBody, ViewResp]("views.publish", ViewsPublishBody(user, v)).map(r => SlackId.ViewId(r.view.id))
        }

    def custom[In: Schema, Out: Schema](method: String, body: In)(using Frame): Out < (Async & Abort[SlackException]) =
        SlackWebApi.request[In, Out](method, body)

    extension (self: Slack)
        /** Run the receive loop on this manually-managed `Slack`: ack on handler return exactly
          * once per envelope; a routine disconnect rotates transparently per the config's
          * reconnect policy; ends on `link_disabled` with `SlackTerminalException`. The bot-token
          * ambient is bound around the loop body, so a `Slack.chatPostMessage`/etc. call from
          * inside the handler resolves the token on this manual path exactly as under the scoped
          * `run`. The already-opened engine is the controller's first active engine (no duplicate
          * open); the controller is recorded on the handle so `close` tears down the
          * currently-active engine.
          */
        def receive[S](using
            Isolate[S, Abort[SlackException] & Async, S]
        )(
            handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException])
        )(using Frame): Unit < (S & Async & Abort[SlackException]) =
            val h = self.handle
            kyo.internal.SlackReconnect.controllerFrom(h.engine, () => openEngine(h.config), h.config).map { controller =>
                h.controller.set(Present(controller)).andThen {
                    SlackWebApi.local.let(Present(h.config.bot)) {
                        controller.start(handler)
                    }
                }
            }
        end receive

        /** Observable teardown of socket and background fibers. Idempotent and total (never
          * aborts), mirroring `HttpWebSocket.close`. Closes the currently-active engine (via the
          * controller while `receive` is running, else the initial engine).
          */
        def close(using Frame): Unit < Async =
            val h = self.handle
            h.controller.use {
                case Present(controller) => controller.closeActive
                case Absent              => h.engine.closeNow
            }
        end close
    end extension

    private[kyo] def fromHandle(h: kyo.internal.SlackSocketHandle): Slack        = h
    extension (c: Slack) private[kyo] def handle: kyo.internal.SlackSocketHandle = c

    /** Open the wss url via apps.connections.open (app-level token) and initialize
      * an engine over the configured transport. Shared by init and run.
      */
    private[kyo] def openEngine(config: SlackConfig)(using Frame): SlackSocketEngine < (Async & Abort[SlackException]) =
        SlackWebApi.baseUrl.use { base =>
            openEngine(config, base)
        }

    private def openEngine(config: SlackConfig, base: String)(using Frame): SlackSocketEngine < (Async & Abort[SlackException]) =
        Abort.recover[HttpException] { (ex: HttpException) =>
            Abort.fail(new SlackHandshakeException(s"apps.connections.open transport failure: ${ex.getMessage}", ex))
        } {
            // Read the FULL response body as text: kyo-http leaves `rawBody` Absent on a 2xx,
            // and the connections.open success IS a 2xx carrying the wss url, so a JSON-typed
            // response would drop the body. The text body is decoded by decodeConnectionsOpen.
            HttpClient.postTextResponse(
                s"$base/apps.connections.open",
                SlackWire.encodeConnectionsOpenBody,
                headers = Seq(
                    "Authorization" -> s"Bearer ${config.appLevel.value}",
                    "Content-Type"  -> "application/json"
                ),
                failOnError = false
            ).map { response =>
                SlackWire.decodeConnectionsOpen(response.fields.body) match
                    case Result.Success(url) => SlackTransport.transport.use(t => SlackSocketEngine.initUnscoped(t, url, config))
                    case Result.Failure(ex)  => Abort.fail(ex)
                    case Result.Panic(ex) => Abort.fail(new SlackHandshakeException(
                            s"apps.connections.open decode panicked: ${ex.getMessage}",
                            ex
                        ))
            }
        }

    // private[kyo] request/response body models (not part of the public API).
    // These internal DTOs carry the Slack Web API wire keys (channel, text, blocks,
    // thread_ts, trigger_id, view_id, user, view, ...) so the derived Schema encodes
    // the request body exactly as the Slack API expects. The typed `SlackBlock` layout
    // renders to a `SlackRawJson` (a native Structure.Value), so `blocks` splices as a
    // real JSON array rather than a quoted string.
    // TODO: `blocks` is carried as `SlackRawJson` rather than a typed `Chunk[SlackBlock]` field
    // because kyo-schema's derived sum/enum encoding emits a tagged shape, not Slack's untagged
    // Block Kit JSON (see SlackBlock). Replace with the typed field once kyo-schema supports
    // untagged discriminated-union encoding.

    final private[kyo] case class EmptyBody() derives Schema

    // auth.test response: the Slack API returns user_id/team_id/bot_id/url. Mapped to
    // the camelCase public Slack.Identity inside authTest.
    final private[kyo] case class AuthTestResp(
        user_id: Maybe[String] = Absent,
        team_id: Maybe[String] = Absent,
        bot_id: Maybe[String] = Absent,
        url: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class TsResp(ts: String) derives Schema

    // chat.postEphemeral returns `message_ts` (not `ts`), so it has its own response type.
    final private[kyo] case class EphemeralResp(message_ts: String) derives Schema

    final private[kyo] case class ViewIdInner(id: String) derives Schema

    final private[kyo] case class ViewResp(view: ViewIdInner) derives Schema

    // chat.postMessage request body. `blocks` carries a parsed Block Kit AST that the
    // SlackRawJson Schema emits as a native JSON array (not a quoted string).
    final private[kyo] case class PostMessageBody(
        channel: SlackId.ChannelId,
        text: String,
        blocks: Maybe[SlackRawJson],
        thread_ts: Maybe[SlackTs]
    ) derives Schema

    // chat.postEphemeral request body.
    final private[kyo] case class EphemeralBody(
        channel: SlackId.ChannelId,
        user: SlackId.UserId,
        text: String,
        blocks: Maybe[SlackRawJson],
        thread_ts: Maybe[SlackTs]
    ) derives Schema

    // chat.update request body.
    final private[kyo] case class UpdateBody(
        channel: SlackId.ChannelId,
        ts: SlackTs,
        text: String,
        blocks: Maybe[SlackRawJson]
    ) derives Schema

    // The `view` object the views.* methods carry: the Slack API wire shape for a
    // modal/home view. The public SlackView (camelCase, raw blocks string) maps here.
    final private[kyo] case class ViewBody(
        `type`: SlackView.Type,
        callback_id: Maybe[String],
        blocks: SlackRawJson,
        title: Maybe[SlackRawJson],
        submit: Maybe[SlackRawJson],
        close: Maybe[SlackRawJson],
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

    /** Render a message's typed `blocks` to the native JSON-array carrier, or `Absent` when the
      * message carries no blocks. A malformed `SlackBlock.Raw` surfaces as a typed
      * `SlackDecodeException` rather than an invalid body on the wire.
      */
    private[kyo] def messageBlocks(message: SlackMessage)(using Frame): Maybe[SlackRawJson] < Abort[SlackException] =
        if message.blocks.isEmpty then Absent
        else SlackBlock.encode(message.blocks).map(Present(_))

    /** Map the public `SlackView` to the wire `ViewBody`: render the typed `blocks`, wrap the
      * `title`/`submit`/`close` labels as `plain_text` objects, and carry `private_metadata`
      * and `notify_on_close`. A malformed `SlackBlock.Raw` surfaces as a typed
      * `SlackDecodeException`.
      */
    private[kyo] def encodeView(view: SlackView)(using Frame): ViewBody < Abort[SlackException] =
        SlackBlock.encode(view.blocks).map { blocks =>
            ViewBody(
                view.`type`,
                view.callbackId,
                blocks,
                view.title.map(SlackBlock.plainText),
                view.submit.map(SlackBlock.plainText),
                view.close.map(SlackBlock.plainText),
                view.privateMetadata,
                Maybe.when(view.notifyOnClose)(true)
            )
        }

end Slack
