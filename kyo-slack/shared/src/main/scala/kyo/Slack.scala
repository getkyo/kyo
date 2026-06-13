package kyo

/** The public entry object for kyo-slack Socket Mode. Contains the bot identity
  * model, the manually-managed connection entry point, and the Web API methods that
  * send messages and manage views from within a connected handler.
  */
object Slack:

    import kyo.internal.SlackRawJson
    import kyo.internal.SlackSocketEngine
    import kyo.internal.SlackTransport
    import kyo.internal.SlackWebApi
    import kyo.internal.SlackWire

    /** The `auth.test` result: the bot's user/team ids, the bot id, and the
      * workspace url. Aliased top-level as `SlackIdentity`.
      */
    case class Identity(
        userId: SlackId.UserId,
        teamId: SlackId.TeamId,
        botId: String,
        url: String
    ) derives Schema, CanEqual

    /** Advanced manually-managed entry: open the socket and return a live handle the
      * caller drives with `receive` and tears down with `close`. The bot-token ambient
      * is bound by `SlackConnection.receive` around the loop body (where the handler
      * runs), so the handler's Web API calls resolve the token on the
      * connectUnscoped + receive path exactly as they do under the scoped `connect`.
      * The handle carries `config` (with `config.bot`) so `receive` can bind it.
      */
    def connectUnscoped(config: SlackConfig)(using Frame): SlackConnection < (Async & Abort[SlackException]) =
        openEngine(config).map { engine =>
            kyo.internal.SlackSocketHandle.fromEngine(engine, config).map(SlackConnection.fromHandle)
        }

    /** Scope-managed Socket Mode connection: open the socket, run the receive loop
      * under the reconnect policy, and tear everything down on scope exit. The
      * handler returns one `SlackAck` per envelope and the framework emits exactly one
      * wire ack from it (structural acking); a routine disconnect rotates transparently
      * per `config.reconnect`; `link_disabled` ends with `SlackTerminalException`.
      *
      * The bot-token ambient is bound around the loop body, so a
      * `Slack.chatPostMessage`/etc. call from inside the handler resolves the token.
      * The scope finalizer closes whatever engine is active at exit, including one a
      * rotation swapped in, so no socket or background fiber leaks.
      */
    def connect[S](
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

    /** Open the wss url via apps.connections.open (app-level token) and initialize
      * an engine over the configured transport. Shared by connectUnscoped and connect.
      */
    private[kyo] def openEngine(config: SlackConfig)(using Frame): SlackSocketEngine < (Async & Abort[SlackException]) =
        SlackWebApi.baseUrl.use { base =>
            openEngine(config, base)
        }

    private def openEngine(config: SlackConfig, base: String)(using Frame): SlackSocketEngine < (Async & Abort[SlackException]) =
        Abort.recover[HttpException] { (ex: HttpException) =>
            Abort.fail(new SlackException.SlackHandshakeException(s"apps.connections.open transport failure: ${ex.getMessage}", ex))
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
                    case Result.Panic(ex) => Abort.fail(new SlackException.SlackHandshakeException(
                            s"apps.connections.open decode panicked: ${ex.getMessage}",
                            ex
                        ))
            }
        }

    def authTest(using Frame): Slack.Identity < (Async & Abort[SlackException]) =
        SlackWebApi.request[EmptyBody, AuthTestResp]("auth.test", EmptyBody()).map { r =>
            Slack.Identity(
                SlackId.UserId(r.user_id.getOrElse("")),
                SlackId.TeamId(r.team_id.getOrElse("")),
                r.bot_id.getOrElse(""),
                r.url.getOrElse("")
            )
        }

    def chatPostMessage(message: SlackMessage)(using Frame): SlackTs < (Async & Abort[SlackException]) =
        parseBlocks(message.blocksJson).map { blocks =>
            SlackWebApi.request[PostMessageBody, TsResp](
                "chat.postMessage",
                PostMessageBody(message.channel, message.text, blocks, message.threadTs)
            ).map(r => SlackId.Ts(r.ts))
        }

    def chatPostEphemeral(message: SlackMessage, user: SlackId.UserId)(using Frame): SlackTs < (Async & Abort[SlackException]) =
        parseBlocks(message.blocksJson).map { blocks =>
            SlackWebApi.request[EphemeralBody, TsResp](
                "chat.postEphemeral",
                EphemeralBody(message.channel, user, message.text, blocks, message.threadTs)
            ).map(r => SlackId.Ts(r.ts))
        }

    def chatUpdate(channel: SlackId.ChannelId, ts: SlackTs, message: SlackMessage)(using Frame): SlackTs < (Async & Abort[SlackException]) =
        parseBlocks(message.blocksJson).map { blocks =>
            SlackWebApi.request[UpdateBody, TsResp](
                "chat.update",
                UpdateBody(channel, ts, message.text, blocks)
            ).map(r => SlackId.Ts(r.ts))
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

    // private[kyo] request/response body models (not part of the public API).
    // The public SlackMessage/SlackView use camelCase; these internal DTOs carry
    // the Slack Web API wire keys (channel, text, blocks,
    // thread_ts, trigger_id, view_id, user, view, ...) so the derived Schema encodes
    // the request body exactly as the Slack API expects. The raw-JSON `blocksJson`
    // string maps to a parsed `blocks` JSON array via Structure.Value, so it splices
    // as a real array rather than a quoted string.

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
        title: Maybe[SlackRawJson]
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

    /** Parse an optional raw Block Kit JSON string into a structural value so it
      * encodes as a JSON array on the wire. A malformed blocks string surfaces as a
      * typed `SlackDecodeException` rather than being sent as an invalid body.
      */
    private[kyo] def parseBlocks(raw: Maybe[String])(using Frame): Maybe[SlackRawJson] < Abort[SlackException] =
        raw match
            case Absent        => Absent
            case Present(json) => SlackRawJson.parse(json, "Block Kit blocks").map(Present(_))

    /** Map the public `SlackView` (camelCase, raw JSON blocks/title) to the wire
      * `ViewBody`. The raw blocks/title strings parse to structural values; malformed
      * JSON surfaces as a typed `SlackDecodeException`.
      */
    private[kyo] def encodeView(view: SlackView)(using Frame): ViewBody < Abort[SlackException] =
        SlackRawJson.parse(view.blocksJson, "view blocks").map { blocks =>
            parseBlocks(view.titleJson).map { title =>
                ViewBody(view.`type`, view.callbackId, blocks, title)
            }
        }

end Slack

/** Short top-level name for the `auth.test` result. */
type SlackIdentity = Slack.Identity
