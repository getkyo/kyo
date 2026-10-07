package kyo

import kyo.internal.teams.Connector
import kyo.internal.teams.Jwt
import kyo.internal.teams.KeyCache
import kyo.internal.teams.ServiceUrls
import kyo.internal.teams.TokenCache
import kyo.internal.teams.Wire
import kyo.schema.Transformer
import kyo.schema.alias
import kyo.schema.catchAll
import kyo.schema.discriminator
import kyo.schema.rename
import kyo.schema.tagOnly
import kyo.schema.transform

/** The client of the Bot Framework's REST connector that the module builds from a [[kyo.TeamsConfig]]: the config, the module's own
  * HTTP client, the cached outbound token and the cached signing keys. A caller never calls a method on one; the verbs on the companion
  * require it as `Env[Teams]`. `Teams.run(config)` builds one for a region, closing its HTTP client and dropping its caches when the
  * region ends; `Teams.init` builds one for the enclosing `Scope` and `Teams.run(client)` provides it. `Teams.Webhook.handler` requires
  * it too, and serves every delivery with that same client.
  *
  * The companion also holds the module's types: the ids (`Teams.ConversationId`, ...), the credentials (`Teams.ClientSecret`, ...),
  * the service URL a conversation is reached at (`Teams.ServiceUrl`), and the path of `custom` and of the webhook.
  *
  * IMPORTANT: the outbound token is sent only to a service URL whose origin `TeamsConfig.serviceHosts` lists, checked before every
  * request. Anyone holding the token may act as the bot, so a stored or forged reference cannot send it elsewhere.
  *
  * @see
  *   [[kyo.TeamsConfig]] the config
  * @see
  *   [[kyo.TeamsException]] the failures
  */
final class Teams private[kyo] (
    private[kyo] val config: TeamsConfig,
    private[kyo] val http: HttpClient,
    private[kyo] val tokens: TokenCache,
    private[kyo] val keys: KeyCache
)

object Teams:

    /** Builds a client from `config` for the duration of `v`, closing its HTTP client and dropping its cached token afterwards. Nothing
      * is fetched until a verb needs it.
      */
    def run[A, S](config: TeamsConfig)(v: A < (S & Env[Teams]))(using Frame): A < (S & Async) =
        Scope.run(init(config).map(run(_)(v)))

    /** Provides `client` to `v`, which stays the caller's to close. */
    def run[A, S](client: Teams)(v: A < (S & Env[Teams]))(using Frame): A < S =
        Env.run(client)(v)

    /** A client on `config`, closed when the enclosing `Scope` ends, so its HTTP client never outlives it. `run(client)` provides it to
      * a computation. Nothing is fetched until a verb needs it.
      */
    def init(config: TeamsConfig)(using Frame): Teams < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(config))(close)

    /** A client on `config` that nothing closes but the caller's `close`: the form for a client whose lifetime no `Scope` describes.
      * Prefer `init`.
      */
    def initUnscoped(config: TeamsConfig)(using Frame): Teams < Sync =
        // kyo-http's pool defaults stay: 100 connections to one service host is more concurrency than a bot's calls reach unless it
        // forks that many at once, and the 60-second idle close matches the idle timeout proxies and load balancers default to.
        HttpClient.initUnscoped(defaultTlsConfig = config.tls, transportConfig = config.transport).map { http =>
            TokenCache.init(config, http).map(tokens => KeyCache.init(config, http).map(keys => new Teams(config, http, tokens, keys)))
        }

    /** Closes `client`'s HTTP client without waiting for requests in flight; its cached token and keys go with it. For an
      * `initUnscoped` client, or to close an `init` one before its scope ends. Idempotent and total.
      */
    def close(client: Teams)(using Frame): Unit < Async =
        client.http.closeNow

    /** Sends `message` to the conversation `ref` names, at its end, and answers the id Teams gave it. */
    def send(ref: ConversationReference, message: Message.Create)(using
        Frame
    ): ActivityId < (Async & Abort[TeamsSendFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            Connector.call[TeamsSendFailure](
                teams,
                Routes.Send,
                Method.Post,
                ref.serviceUrl,
                Chunk("v3", "conversations", ref.conversation.id.value, "activities"),
                body = Present(Json.encode(message))
            ).map(body => decoded[Wire.ResourceResponse](Routes.Send, body).map(_.id))
        }

    /** Sends `message` as a reply to the activity `to` in the conversation `ref` names, and answers the id Teams gave it. Where the
      * conversation has no threads, Teams appends it as `send` does.
      */
    def reply(ref: ConversationReference, to: ActivityId, message: Message.Create)(using
        Frame
    ): ActivityId < (Async & Abort[TeamsReplyFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            Connector.call[TeamsReplyFailure](
                teams,
                Routes.Reply,
                Method.Post,
                ref.serviceUrl,
                Chunk("v3", "conversations", ref.conversation.id.value, "activities", to.value),
                body = Present(Json.encode(message))
            ).map(body => decoded[Wire.ResourceResponse](Routes.Reply, body).map(_.id))
        }

    /** Replies to `activity` in its own conversation. */
    def reply(activity: Activity.Addressed, message: Message.Create)(using
        Frame
    ): ActivityId < (Async & Abort[TeamsReplyFailure] & Env[Teams]) =
        reply(activity.reference, activity.common.id, message)

    /** Replaces the bot's message `activity` with `message`, and answers its id. */
    def edit(ref: ConversationReference, activity: ActivityId, message: Message.Create)(using
        Frame
    ): ActivityId < (Async & Abort[TeamsEditFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            Connector.call[TeamsEditFailure](
                teams,
                Routes.Edit,
                Method.Put,
                ref.serviceUrl,
                Chunk("v3", "conversations", ref.conversation.id.value, "activities", activity.value),
                body = Present(Json.encode(message))
            ).map(body => decoded[Wire.ResourceResponse](Routes.Edit, body).map(_.id))
        }

    /** Deletes the bot's message `activity`. */
    def delete(ref: ConversationReference, activity: ActivityId)(using
        Frame
    ): Unit < (Async & Abort[TeamsDeleteFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            Connector.call[TeamsDeleteFailure](
                teams,
                Routes.Delete,
                Method.Delete,
                ref.serviceUrl,
                Chunk("v3", "conversations", ref.conversation.id.value, "activities", activity.value)
            ).unit
        }

    /** Shows the bot as typing in the conversation `ref` names, until its next message or a few seconds pass. */
    def typing(ref: ConversationReference)(using
        Frame
    ): Unit < (Async & Abort[TeamsTypingFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            Connector.call[TeamsTypingFailure](
                teams,
                Routes.Typing,
                Method.Post,
                ref.serviceUrl,
                Chunk("v3", "conversations", ref.conversation.id.value, "activities"),
                body = Present(Json.encode(Wire.TypingActivity()))
            ).unit
        }

    /** Starts a conversation at `serviceUrl` (a one-on-one chat with a user, or a thread in a channel) and answers its reference,
      * ready for `send`.
      */
    def createConversation(serviceUrl: ServiceUrl, create: Conversation.Create)(using
        Frame
    ): ConversationReference < (Async & Abort[TeamsCreateConversationFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            Connector.call[TeamsCreateConversationFailure](
                teams,
                Routes.CreateConversation,
                Method.Post,
                serviceUrl,
                Chunk("v3", "conversations"),
                body = Present(Json.encode(create))
            ).map { body =>
                decoded[Wire.ConversationResourceResponse](Routes.CreateConversation, body).map { created =>
                    ConversationReference(
                        serviceUrl = created.serviceUrl.getOrElse(serviceUrl),
                        conversation = ConversationAccount(created.id, tenantId = create.tenantId),
                        bot = create.bot,
                        activityId = created.activityId
                    )
                }
            }
        }

    /** One page of the members of the conversation `ref` names; the next page is `Member.Page.init(size, paged.continuationToken)`. */
    def members(ref: ConversationReference, page: Member.Page)(using
        Frame
    ): Member.Paged < (Async & Abort[TeamsMembersFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            Connector.call[TeamsMembersFailure](
                teams,
                Routes.Members,
                Method.Get,
                ref.serviceUrl,
                Chunk("v3", "conversations", ref.conversation.id.value, "pagedmembers"),
                query = page.continuation.fold(HttpQueryParams.init("pageSize", page.size.toString))(c =>
                    HttpQueryParams.init("pageSize", page.size.toString).add("continuationToken", c)
                )
            ).map(body => decoded[Member.Paged](Routes.Members, body))
        }

    /** The first page of 200 members of the conversation `ref` names. */
    def members(ref: ConversationReference)(using
        Frame
    ): Member.Paged < (Async & Abort[TeamsMembersFailure] & Env[Teams]) =
        members(ref, Member.Page.first)

    /** The member `user` of the conversation `ref` names. */
    def member(ref: ConversationReference, user: UserId)(using
        Frame
    ): Account < (Async & Abort[TeamsMemberFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            Connector.call[TeamsMemberFailure](
                teams,
                Routes.Member,
                Method.Get,
                ref.serviceUrl,
                Chunk("v3", "conversations", ref.conversation.id.value, "members", user.value)
            ).map(body => decoded[Account](Routes.Member, body))
        }

    /** Calls any route the module does not model: `method` to `path` under `serviceUrl`, with `query` and `body` encoded as JSON, the
      * answer decoded as `Out` (an empty answer reads as JSON `null`).
      */
    def custom[In: Schema, Out: Schema](
        serviceUrl: ServiceUrl,
        method: Method,
        path: Path,
        query: HttpQueryParams = HttpQueryParams.empty,
        body: Maybe[In] = Absent
    )(using Frame): Out < (Async & Abort[TeamsCustomFailure] & Env[Teams]) =
        Env.use[Teams] { teams =>
            val route = s"${method.name} custom"
            Connector.call[TeamsCustomFailure](teams, route, method, serviceUrl, path.segments, query, body.map(Json.encode(_)))
                .map(answer => decoded[Out](route, if answer.trim.isEmpty then "null" else answer))
        }

    /** The HTTP methods `custom` sends. */
    enum Method derives CanEqual:
        case Get, Post, Put, Patch, Delete

        private[kyo] def name: String =
            this match
                case Get    => "GET"
                case Post   => "POST"
                case Put    => "PUT"
                case Patch  => "PATCH"
                case Delete => "DELETE"
    end Method

    private def decoded[A: Schema](route: String, body: String)(using Frame): A < Abort[TeamsDecodeException] =
        Json.decode[A](body) match
            case Result.Success(a)  => a
            case Result.Failure(ex) => Abort.fail(TeamsDecodeException.of(route, TeamsDecodeException.Part.Response, ex))
            case Result.Panic(ex)   => Abort.panic(ex)

    /** The endpoint the Bot Connector posts Activities to: `handler` mounts it on a kyo-http server, and `verify` and `decode` are its
      * two steps for a caller serving the route itself.
      *
      * A delivery is accepted only when its `Authorization` token verifies (Microsoft: "Implementers shouldn't expose a way to disable
      * validation"), so there is no option to skip `verify`. The body is bounded by the server's `maxContentLength` before any of this
      * runs.
      */
    object Webhook:

        /** Verifies a delivery's `authorization` header against the Bot Framework's signing keys, fetching them when the key cache says
          * so, and checks the two fields of `body` the token binds: the Activity's `channelId`, which the signing key must endorse and
          * which must be `msteams`, and its `serviceUrl`, which must be the token's `serviceUrl` claim.
          */
        def verify(authorization: Maybe[String], body: Span[Byte])(using
            Frame
        ): Unit < (Async & Abort[TeamsWebhookVerifyFailure] & Env[Teams]) =
            Env.use[Teams](Jwt.verify(_, authorization, body))

        /** The Activity `body` holds. Call it only on a body `verify` accepted. */
        def decode(body: Span[Byte])(using Frame): Activity[Activity.Answer] < Abort[TeamsWebhookDecodeFailure] =
            Json.decodeBytes[Activity[Activity.Answer]](body) match
                case Result.Success(activity) => activity
                case Result.Failure(ex)       => Abort.fail(TeamsWebhookDecodeException.of(ex))
                case Result.Panic(ex)         => Abort.panic(ex)

        /** A `POST` route at `webhook`'s path that verifies each delivery with the caller's `Teams`, decodes it, runs `f` with that same `Teams` as its
          * `Env[Teams]`, and answers with what `f` returns: a bot served inside `Teams.run` has one client and one token cache.
          *
          * The replies: 401 for a token that does not verify, 403 for a signing key that does not endorse the channel (Microsoft's
          * answer for it), 503 when the signing keys cannot be fetched (the delivery may be genuine, so the Bot Connector may send it
          * again), 400 for a body that is not an Activity, and 500 for `f`'s failure or a panic. An invoke is answered `200` with the
          * answer `f` returns as its JSON (an [[kyo.Teams.InvokeResponse]] with its own status); every other Activity `200` with no
          * body.
          */
        def handler[E](webhook: TeamsWebhookConfig)(
            f: [A] => Activity[A] => A < (Async & Abort[E] & Env[Teams])
        )(using Frame): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], E] < Env[Teams] =
            Env.use[Teams] { teams =>
                HttpRoute.postRaw(webhook.path).request(_.bodyBinary).response(_.bodyBinary).handler[E] { request =>
                    val body = request.fields.body
                    Abort.run[TeamsWebhookVerifyFailure](Jwt.verify(teams, request.headers.get("Authorization"), body)).map {
                        case Result.Success(_) =>
                            Abort.run[TeamsWebhookDecodeFailure](decode(body)).map {
                                case Result.Success(activity) => Env.run(teams)(answer(activity, f))
                                case Result.Failure(_)        => empty(HttpStatus.BadRequest)
                                case Result.Panic(ex)         => Abort.panic(ex)
                            }
                        case Result.Failure(failure) => empty(refusal(failure))
                        case Result.Panic(ex)        => Abort.panic(ex)
                    }
                }
            }

        private def answer[E](activity: Activity[Activity.Answer], f: [A] => Activity[A] => A < (Async & Abort[E] & Env[Teams]))(
            using Frame
        ): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[E] & Env[Teams]) =
            activity match
                case action: Activity.AdaptiveCardAction =>
                    f[Card.ActionResponse](action).map(response => json(HttpStatus.OK, Json.encodeBytes(response)))
                case other: Activity.OtherInvoke =>
                    f[InvokeResponse](other).map(response =>
                        response.body match
                            case Present(body) => json(response.status, Json.encodeBytes(body))
                            case Absent        => empty(response.status)
                    )
                case plain: Activity.Plain => f[Unit](plain).andThen(empty(HttpStatus.OK))

        /** The status a delivery that does not verify is answered with. */
        private def refusal(failure: TeamsWebhookVerifyFailure): HttpStatus =
            failure match
                case _: TeamsMissingEndorsementException => HttpStatus.Forbidden
                case _: TeamsMetadataAlgorithmException  => HttpStatus.ServiceUnavailable
                case _: TeamsAuthenticationException     => HttpStatus.Unauthorized
                case _: TeamsWebhookDecodeException      => HttpStatus.BadRequest
                case _: (TeamsTransportException | TeamsRefusedUrlException | TeamsUnexpectedStatusException | TeamsDecodeException) =>
                    HttpStatus.ServiceUnavailable

        private def empty(status: HttpStatus): HttpResponse["body" ~ Span[Byte]] = HttpResponse(status).addField("body", Span.empty[Byte])

        private def json(status: HttpStatus, body: Span[Byte]): HttpResponse["body" ~ Span[Byte]] =
            HttpResponse(status).addField("body", body).addHeader("Content-Type", "application/json")
    end Webhook

    /** The route each verb names in its failures, as the Bot Connector's reference writes it. */
    private[kyo] object Routes:
        inline val Send               = "POST /v3/conversations/{conversationId}/activities"
        inline val Reply              = "POST /v3/conversations/{conversationId}/activities/{activityId}"
        inline val Edit               = "PUT /v3/conversations/{conversationId}/activities/{activityId}"
        inline val Delete             = "DELETE /v3/conversations/{conversationId}/activities/{activityId}"
        inline val Typing             = "POST /v3/conversations/{conversationId}/activities (typing)"
        inline val CreateConversation = "POST /v3/conversations"
        inline val Members            = "GET /v3/conversations/{conversationId}/pagedmembers"
        inline val Member             = "GET /v3/conversations/{conversationId}/members/{memberId}"
    end Routes

    // Teams assigns every id, so an id is refused only when it is empty or longer than any Teams issues; its characters are kept, and a
    // path percent-encodes them. Each is an opaque type so one cannot be passed for another, declared in an object of its own: declared
    // in this object it would be transparent here, and `Schema.derived` of a record in this object would lose its field list.

    /** The bot's Microsoft app id, the audience of every inbound token. */
    type AppId = AppId.Value
    object AppId:
        opaque type Value = String

        /** The id `value`, or a [[kyo.TeamsInvalidIdException]] when it is empty. */
        def init(value: String)(using Frame): Result[TeamsInvalidIdException, AppId] = Ids.init(TeamsInvalidIdException.Id.App, value)
        extension (self: AppId) def value: String                                    = self
        given Schema[AppId]                                                          = Ids.schema(TeamsInvalidIdException.Id.App)
        given CanEqual[AppId, AppId]                                                 = CanEqual.derived
    end AppId

    /** A Microsoft Entra tenant, as Teams names it in `channelData.tenant.id`. */
    type TenantId = TenantId.Value
    object TenantId:
        opaque type Value = String

        /** The id `value`, or a [[kyo.TeamsInvalidIdException]] when it is empty. */
        def init(value: String)(using Frame): Result[TeamsInvalidIdException, TenantId] =
            Ids.init(TeamsInvalidIdException.Id.Tenant, value)
        extension (self: TenantId) def value: String = self
        given Schema[TenantId]                       = Ids.schema(TeamsInvalidIdException.Id.Tenant)
        given CanEqual[TenantId, TenantId]           = CanEqual.derived
    end TenantId

    /** A conversation: a one-on-one chat, a group chat, or a channel's thread (`19:...@thread.skype`). */
    type ConversationId = ConversationId.Value
    object ConversationId:
        opaque type Value = String

        /** The id `value`, or a [[kyo.TeamsInvalidIdException]] when it is empty. */
        def init(value: String)(using Frame): Result[TeamsInvalidIdException, ConversationId] =
            Ids.init(TeamsInvalidIdException.Id.Conversation, value)
        extension (self: ConversationId) def value: String = self
        given Schema[ConversationId]                       = Ids.schema(TeamsInvalidIdException.Id.Conversation)
        given CanEqual[ConversationId, ConversationId]     = CanEqual.derived
    end ConversationId

    /** An activity, unique on its channel: the key a caller deduplicates redelivered activities by. */
    type ActivityId = ActivityId.Value
    object ActivityId:
        opaque type Value = String

        /** The id `value`, or a [[kyo.TeamsInvalidIdException]] when it is empty. */
        def init(value: String)(using Frame): Result[TeamsInvalidIdException, ActivityId] =
            Ids.init(TeamsInvalidIdException.Id.Activity, value)
        extension (self: ActivityId) def value: String = self
        given Schema[ActivityId]                       = Ids.schema(TeamsInvalidIdException.Id.Activity)
        given CanEqual[ActivityId, ActivityId]         = CanEqual.derived
    end ActivityId

    /** A user or a bot as the channel names it (`29:...`), the `id` of a channel account. */
    type UserId = UserId.Value
    object UserId:
        opaque type Value = String

        /** The id `value`, or a [[kyo.TeamsInvalidIdException]] when it is empty. */
        def init(value: String)(using Frame): Result[TeamsInvalidIdException, UserId] = Ids.init(TeamsInvalidIdException.Id.User, value)
        extension (self: UserId) def value: String                                    = self
        given Schema[UserId]                                                          = Ids.schema(TeamsInvalidIdException.Id.User)
        given CanEqual[UserId, UserId]                                                = CanEqual.derived
    end UserId

    /** A channel of a team (`channelData.channel.id`). Not the Activity's `channelId`, which is a [[kyo.Teams.BotChannel]]. */
    type ChannelId = ChannelId.Value
    object ChannelId:
        opaque type Value = String

        /** The id `value`, or a [[kyo.TeamsInvalidIdException]] when it is empty. */
        def init(value: String)(using Frame): Result[TeamsInvalidIdException, ChannelId] =
            Ids.init(TeamsInvalidIdException.Id.Channel, value)
        extension (self: ChannelId) def value: String = self
        given Schema[ChannelId]                       = Ids.schema(TeamsInvalidIdException.Id.Channel)
        given CanEqual[ChannelId, ChannelId]          = CanEqual.derived
    end ChannelId

    /** A team (`channelData.team.id`). */
    type TeamId = TeamId.Value
    object TeamId:
        opaque type Value = String

        /** The id `value`, or a [[kyo.TeamsInvalidIdException]] when it is empty. */
        def init(value: String)(using Frame): Result[TeamsInvalidIdException, TeamId] = Ids.init(TeamsInvalidIdException.Id.Team, value)
        extension (self: TeamId) def value: String                                    = self
        given Schema[TeamId]                                                          = Ids.schema(TeamsInvalidIdException.Id.Team)
        given CanEqual[TeamId, TeamId]                                                = CanEqual.derived
    end TeamId

    /** A user's Microsoft Entra object id (`aadObjectId`), the same across the channels a user is reached on. */
    type AadObjectId = AadObjectId.Value
    object AadObjectId:
        opaque type Value = String

        /** The id `value`, or a [[kyo.TeamsInvalidIdException]] when it is empty. */
        def init(value: String)(using Frame): Result[TeamsInvalidIdException, AadObjectId] =
            Ids.init(TeamsInvalidIdException.Id.AadObject, value)
        extension (self: AadObjectId) def value: String = self
        given Schema[AadObjectId]                       = Ids.schema(TeamsInvalidIdException.Id.AadObject)
        given CanEqual[AadObjectId, AadObjectId]        = CanEqual.derived
    end AadObjectId

    // Microsoft documents no length for these ids, so only an empty one is refused.
    private object Ids:
        def problemOf(value: String): Maybe[TeamsInvalidIdException.Problem] =
            if value.isEmpty then Present(TeamsInvalidIdException.Problem.Empty)
            else Absent

        def init(id: TeamsInvalidIdException.Id, value: String)(using Frame): Result[TeamsInvalidIdException, String] =
            problemOf(value) match
                case Present(problem) => Result.fail(TeamsInvalidIdException(id, problem))
                case Absent           => Result.succeed(value)

        def schema(id: TeamsInvalidIdException.Id): Schema[String] =
            Schema.stringSchema.transformVia(text => init(id, text))(identity)
    end Ids

    /** The bot's client secret, the "app password" of its registration, sent in the body of a token request and nowhere else.
      *
      * `toString` renders `Teams.ClientSecret(<redacted>)`, so a [[kyo.TeamsConfig]], a log line or an assertion message that renders
      * one never shows it; `value` is the only way to read it, and secrets compare by value. The `Schema` reads and writes the secret's
      * text, so encoding one is an explicit act that writes the secret.
      *
      * IMPORTANT: `init` refuses an empty text, more than 64 characters, or a character outside printable ASCII other than space, each
      * with a [[kyo.TeamsInvalidTokenException]]. Microsoft Entra ID generates every client secret and documents them as "strong
      * passwords ... 16-64 characters in length" (Microsoft Graph, `passwordCredential` resource type, `secretText`); it documents no
      * alphabet, and issues secrets holding `~`, `.`, `_` and `-`, which the form body percent-encodes.
      *
      * @see
      *   [[kyo.TeamsConfig.Credential.Secret]] where it is held
      */
    final class ClientSecret private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: ClientSecret => value == that.value
                case _                  => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "Teams.ClientSecret(<redacted>)"
    end ClientSecret

    object ClientSecret:
        /** The longest secret Microsoft Entra ID generates. */
        inline val MaxLength = 64

        /** The secret `value`, or a [[kyo.TeamsInvalidTokenException]] when it cannot be one Entra ID generated. */
        def init(value: String)(using Frame): Result[TeamsInvalidTokenException, ClientSecret] =
            Credentials.init(TeamsInvalidTokenException.Token.ClientSecret, value, Present(MaxLength), new ClientSecret(value))

        given CanEqual[ClientSecret, ClientSecret] = CanEqual.derived
        given Schema[ClientSecret]                 =
            Schema.stringSchema.transformVia(text => init(text))(_.value)
    end ClientSecret

    /** A signed JSON Web Token that stands for the app in a token request (`client_assertion`), as an identity provider outside
      * Microsoft's issues it to a workload whose federated credential the app registration trusts.
      *
      * It is a credential: anyone holding it may obtain the bot's token until it expires. `toString` renders
      * `Teams.ClientAssertion(<redacted>)`, `value` is the only way to read it, and assertions compare by value. The `Schema` reads and
      * writes its text.
      *
      * IMPORTANT: `init` refuses an empty text or a character outside printable ASCII other than space, each with a
      * [[kyo.TeamsInvalidTokenException]]. Microsoft documents no length for it; whether the identity platform accepts it is that token
      * request's answer.
      *
      * @see
      *   [[kyo.TeamsConfig.Credential.Federated]] where it is supplied
      */
    final class ClientAssertion private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: ClientAssertion => value == that.value
                case _                     => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "Teams.ClientAssertion(<redacted>)"
    end ClientAssertion

    object ClientAssertion:
        /** The assertion `value`, or a [[kyo.TeamsInvalidTokenException]] when no request could carry it. */
        def init(value: String)(using Frame): Result[TeamsInvalidTokenException, ClientAssertion] =
            Credentials.init(TeamsInvalidTokenException.Token.ClientAssertion, value, Absent, new ClientAssertion(value))

        given CanEqual[ClientAssertion, ClientAssertion] = CanEqual.derived
        given Schema[ClientAssertion]                    =
            Schema.stringSchema.transformVia(text => init(text))(_.value)
    end ClientAssertion

    /** The value App Service and Azure Functions put in `IDENTITY_HEADER`, which the managed identity endpoint requires in every token
      * request's `X-IDENTITY-HEADER` header as a guard against server-side request forgery.
      *
      * It is a credential of the host: `toString` renders `Teams.IdentityHeader(<redacted>)`, `value` is the only way to read it, and
      * values compare by value. The `Schema` reads and writes its text.
      *
      * IMPORTANT: `init` refuses an empty text or a character outside printable ASCII other than space, each with a
      * [[kyo.TeamsInvalidTokenException]], since a header value cannot carry a line break. Microsoft documents no length for it.
      *
      * @see
      *   [[kyo.TeamsConfig.Credential.ManagedIdentity]] where it is held
      */
    final class IdentityHeader private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: IdentityHeader => value == that.value
                case _                    => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "Teams.IdentityHeader(<redacted>)"
    end IdentityHeader

    object IdentityHeader:
        /** The header value `value`, or a [[kyo.TeamsInvalidTokenException]] when no header could carry it. */
        def init(value: String)(using Frame): Result[TeamsInvalidTokenException, IdentityHeader] =
            Credentials.init(TeamsInvalidTokenException.Token.IdentityHeader, value, Absent, new IdentityHeader(value))

        given CanEqual[IdentityHeader, IdentityHeader] = CanEqual.derived
        given Schema[IdentityHeader]                   =
            Schema.stringSchema.transformVia(text => init(text))(_.value)
    end IdentityHeader

    private object Credentials:
        def problemOf(value: String, max: Maybe[Int]): Maybe[TeamsInvalidTokenException.Problem] =
            import TeamsInvalidTokenException.Problem
            if value.isEmpty then Present(Problem.Empty)
            else
                max.filter(value.length > _).map(limit => Problem.TooLong(value.length, limit)).orElse {
                    val bad = value.indexWhere(c => c <= ' ' || c > '~')
                    if bad >= 0 then Present(Problem.Character(bad)) else Absent
                }
            end if
        end problemOf

        def init[A](token: TeamsInvalidTokenException.Token, value: String, max: Maybe[Int], make: => A)(using
            Frame
        ): Result[TeamsInvalidTokenException, A] =
            problemOf(value, max) match
                case Present(problem) => Result.fail(TeamsInvalidTokenException(token, problem))
                case Absent           => Result.succeed(make)
    end Credentials

    /** The Bot Framework channel an Activity came through, its `channelId`: `msteams` for Teams, or another channel's name.
      *
      * Not a [[kyo.Teams.ChannelId]], which names a channel of a team. The module accepts inbound Activities from `msteams` only, so
      * `Other` appears in a [[kyo.Teams.ConversationReference]] a caller built or stored, never in a verified delivery.
      */
    @tagOnly
    sealed trait BotChannel derives CanEqual
    object BotChannel:
        /** Microsoft Teams. */
        @rename("msteams") case object MsTeams extends BotChannel

        /** Any other channel, by the name the Bot Framework gives it. */
        @catchAll final case class Other(name: String) extends BotChannel

        given Schema[BotChannel] = Schema.derived[BotChannel]
    end BotChannel

    /** The base URL of the Bot Connector that serves a conversation, from an Activity's `serviceUrl`: every call about that
      * conversation goes under it, with the bot's token.
      *
      * Teams serves conversations from a few hosts, differing in path by region (`https://smba.trafficmanager.net/amer/`). Proactive
      * messages use the global URLs Microsoft lists (`https://smba.trafficmanager.net/teams/` and the sovereign clouds').
      *
      * IMPORTANT: `init` and the `Schema` accept an absolute `http` or `https` URL naming a host, with no Unix socket, user info, query
      * or fragment, and only printable ASCII, failing with [[kyo.TeamsInvalidServiceUrlException]] otherwise. A URL of that shape is not
      * yet one the module sends to: every call checks its origin against `TeamsConfig.serviceHosts` first, and refuses it with
      * [[kyo.TeamsRefusedUrlException]] before a token is attached.
      *
      * @see
      *   [[kyo.TeamsConfig]] `serviceHosts`, the origins calls may go to
      */
    type ServiceUrl = ServiceUrl.Value
    object ServiceUrl:
        opaque type Value = HttpUrl

        /** The service URL `text`, or a [[kyo.TeamsInvalidServiceUrlException]] naming why it is not one. */
        def init(text: String)(using Frame): Result[TeamsInvalidServiceUrlException, ServiceUrl] =
            ServiceUrls.parse(text).mapFailure(TeamsInvalidServiceUrlException(_))

        extension (self: ServiceUrl)
            /** The URL. */
            def url: HttpUrl = self

            /** The URL as text. */
            def value: String = url.full
        end extension

        given CanEqual[ServiceUrl, ServiceUrl] = CanEqual.derived
        given Schema[ServiceUrl]               = Schema.stringSchema.transformVia(text => init(text))(u => (u: HttpUrl).full)
    end ServiceUrl

    /** The path of a `custom` call under a service URL, as segments.
      *
      * Teams ids hold `:`, `@` and `;`, so a segment is any text: the module percent-encodes every byte outside RFC 3986's unreserved
      * characters when it builds the URL, so no segment can add a segment, start a query or a fragment. `Path.init("v3",
      * "conversations", conversation.value, "pagedmembers")` addresses a conversation whatever its id holds.
      *
      * IMPORTANT: `init` refuses no segment at all, an empty segment, and a `.` or `..` segment, which would move the request off the
      * service URL's path, with [[kyo.TeamsInvalidPathException]].
      *
      * @see
      *   [[kyo.Teams]] `custom`, the call that takes it
      */
    type Path = Path.Value
    object Path:
        opaque type Value = Chunk[String]

        /** The path of `segments`, or a [[kyo.TeamsInvalidPathException]] naming the first one that cannot be a segment. */
        def init(segments: String*)(using Frame): Result[TeamsInvalidPathException, Path] =
            import TeamsInvalidPathException.Problem
            val all = Chunk.from(segments)
            if all.isEmpty then Result.fail(TeamsInvalidPathException(Problem.Empty))
            else
                val empty = all.indexWhere(_.isEmpty)
                val dots  = all.indexWhere(s => s == "." || s == "..")
                if empty >= 0 then Result.fail(TeamsInvalidPathException(Problem.EmptySegment(empty)))
                else if dots >= 0 then Result.fail(TeamsInvalidPathException(Problem.DotSegment(dots)))
                else Result.succeed(all)
                end if
            end if
        end init

        extension (self: Path)
            /** The segments, as given. */
            def segments: Chunk[String] = self
        end extension

        given CanEqual[Path, Path] = CanEqual.derived
    end Path

    /** An Activity the Bot Connector posts to the bot's webhook, indexed by the answer the bot owes it: `Unit` for every Activity
      * answered `200` with no body, [[kyo.Teams.Card.ActionResponse]] for an Adaptive Card's `Action.Execute`, and
      * [[kyo.Teams.InvokeResponse]] for any other invoke.
      *
      * A handler is the polymorphic function `[A] => Teams.Activity[A] => A < ...`; matching a case refines `A`, so answering an
      * Activity that takes no answer, or leaving an invoke unanswered, does not compile. `case _: Teams.Activity.Plain => Kyo.unit` is
      * the fallback for every Activity answered with `200` alone.
      *
      * Each modelled case holds the fields every Activity shares in `common` ([[kyo.Teams.Activity.Common]]), the Activity `id`
      * included: Teams may deliver an Activity again, and a caller deduplicates by it. `Unknown` keeps an Activity type the module does
      * not model, and `OtherInvoke` an invoke other than `adaptiveCard/action`, each with its whole JSON: Microsoft adds Activity types
      * and invoke names over time.
      *
      * The `Schema` is the Bot Framework's Activity JSON, `{"type": ..., ...}`, given for `Teams.Activity[Teams.Activity.Answer]`;
      * `Json.decode[Teams.Activity[Teams.Activity.Answer]](body)` reads a delivery.
      *
      * @see
      *   [[kyo.Teams.Activity.Common]] the shared fields
      * @see
      *   [[kyo.Teams.ConversationReference]] how to answer later
      */
    @discriminator("type")
    sealed trait Activity[+A]

    object Activity:

        given [A, B]: CanEqual[Activity[A], Activity[B]] = CanEqual.derived

        /** Every answer an Activity can owe, the index at which the root's `Schema` is given. */
        type Answer = Unit | Card.ActionResponse | InvokeResponse

        /** Every Activity answered with `200` and no body. */
        type Plain = Message | MessageUpdate | MessageDelete | MessageReaction | ConversationUpdate | InstallationUpdate | Typing | Unknown

        /** The fields every modelled Activity shares.
          *
          * `channel` is the Activity's `channelId`, `sender` its `from`. `timestamp` is when Teams sent it, in UTC. `channelData` holds
          * the Teams-specific part: the tenant, the team and channel, and the event a `conversationUpdate` reports. `entities` holds
          * mentions and the client's metadata.
          */
        final case class Common(
            id: ActivityId,
            timestamp: Maybe[Instant] = Absent,
            serviceUrl: ServiceUrl,
            @rename("channelId") channel: BotChannel,
            @rename("from") sender: Account,
            conversation: ConversationAccount,
            recipient: Account,
            locale: Maybe[String] = Absent,
            entities: Chunk[Entity] = Chunk.empty,
            channelData: Maybe[ChannelData] = Absent
        ) derives CanEqual

        object Common:
            given Schema[Common] = Schema[Common].omitNone.omitEmptyCollections
        end Common

        /** A modelled Activity, which carries the shared fields and so the reference that answers its conversation. */
        sealed trait Addressed:
            def common: Common

            /** The point in the conversation this Activity is, as `send`, `reply` and a later proactive message take it. */
            def reference: ConversationReference =
                ConversationReference(
                    serviceUrl = common.serviceUrl,
                    conversation = common.conversation,
                    bot = Present(common.recipient),
                    user = Present(common.sender),
                    activityId = Present(common.id),
                    channel = common.channel
                )
        end Addressed

        /** A message a user sent: its text, its attachments, and, for an Adaptive Card's `Action.Submit`, the card's input values in
          * `value`.
          */
        @rename("message")
        final case class Message(
            common: Common,
            text: Maybe[String] = Absent,
            textFormat: Maybe[TextFormat] = Absent,
            attachments: Chunk[Attachment] = Chunk.empty,
            value: Maybe[RawJson] = Absent,
            replyToId: Maybe[ActivityId] = Absent
        ) extends Activity[Unit] with Addressed derives CanEqual

        object Message:
            given Schema[Message] = Schema[Message].flatten(_.common).omitNone.omitEmptyCollections

        /** A user edited a message: its new text and attachments. */
        @rename("messageUpdate")
        final case class MessageUpdate(
            common: Common,
            text: Maybe[String] = Absent,
            textFormat: Maybe[TextFormat] = Absent,
            attachments: Chunk[Attachment] = Chunk.empty
        ) extends Activity[Unit] with Addressed derives CanEqual

        object MessageUpdate:
            given Schema[MessageUpdate] = Schema[MessageUpdate].flatten(_.common).omitNone.omitEmptyCollections

        /** A user deleted a message, the one `common.id` names. */
        @rename("messageDelete")
        final case class MessageDelete(common: Common) extends Activity[Unit] with Addressed derives CanEqual

        object MessageDelete:
            given Schema[MessageDelete] = Schema[MessageDelete].flatten(_.common)

        /** A user added or removed reactions on the bot's message `replyToId`. Teams sends no content of that message. */
        @rename("messageReaction")
        final case class MessageReaction(
            common: Common,
            reactionsAdded: Chunk[Reaction] = Chunk.empty,
            reactionsRemoved: Chunk[Reaction] = Chunk.empty,
            replyToId: Maybe[ActivityId] = Absent
        ) extends Activity[Unit] with Addressed derives CanEqual

        object MessageReaction:
            given Schema[MessageReaction] = Schema[MessageReaction].flatten(_.common).omitNone.omitEmptyCollections

        /** Members joined or left the conversation, or the conversation changed: `common.channelData`'s `eventType` says which. The bot
          * itself is among `membersAdded` when it was added, with the id of `common.recipient`.
          */
        @rename("conversationUpdate")
        final case class ConversationUpdate(
            common: Common,
            membersAdded: Chunk[Account] = Chunk.empty,
            membersRemoved: Chunk[Account] = Chunk.empty
        ) extends Activity[Unit] with Addressed derives CanEqual

        object ConversationUpdate:
            given Schema[ConversationUpdate] = Schema[ConversationUpdate].flatten(_.common).omitNone.omitEmptyCollections

        /** The bot was installed in or removed from the conversation. */
        @rename("installationUpdate")
        final case class InstallationUpdate(common: Common, action: InstallAction) extends Activity[Unit] with Addressed derives CanEqual

        object InstallationUpdate:
            given Schema[InstallationUpdate] = Schema[InstallationUpdate].flatten(_.common)

        /** A user is typing. */
        @rename("typing")
        final case class Typing(common: Common) extends Activity[Unit] with Addressed derives CanEqual

        object Typing:
            given Schema[Typing] = Schema[Typing].flatten(_.common)

        /** An Activity of a type the module does not model, by its `type` and its whole JSON. */
        @catchAll
        final case class Unknown(`type`: String, payload: RawJson) extends Activity[Unit] derives CanEqual

        /** An invoke: an Activity the bot answers in the HTTP reply, told apart by its `name`. */
        @rename("invoke")
        sealed trait Invoke extends Activity[Answer] derives CanEqual

        object Invoke:
            given Schema[Invoke] = Schema[Invoke].discriminator("name").catchAll("OtherInvoke")

        /** A user ran an Adaptive Card's `Action.Execute`, or the card refreshed itself (`value.trigger` says which). The answer, a
          * [[kyo.Teams.Card.ActionResponse]], is the card or message the client shows next.
          */
        @rename("adaptiveCard/action")
        final case class AdaptiveCardAction(common: Common, value: AdaptiveCardAction.Request)
            extends Invoke with Activity[Card.ActionResponse] with Addressed derives CanEqual

        object AdaptiveCardAction:
            given Schema[AdaptiveCardAction] = Schema[AdaptiveCardAction].flatten(_.common)

            /** The action as the card defines it, its `data` holding the card's input values, and what triggered it. */
            final case class Request(@transform(Card.Action.Execute.Standalone) action: Card.Action.Execute, trigger: Trigger)
                derives CanEqual, Schema
        end AdaptiveCardAction

        /** An invoke the module does not model (a message extension query, a dialog, a sign-in step), by its `name` and its whole
          * JSON, answered with a raw [[kyo.Teams.InvokeResponse]].
          */
        final case class OtherInvoke(name: String, payload: RawJson) extends Invoke with Activity[InvokeResponse] derives CanEqual

        /** What triggered an `Action.Execute`: a user's click, or the card's automatic refresh. */
        @tagOnly
        sealed trait Trigger derives CanEqual
        object Trigger:
            @rename("manual") case object Manual           extends Trigger
            @rename("automatic") case object Automatic     extends Trigger
            @catchAll final case class Other(name: String) extends Trigger
            given Schema[Trigger] = Schema.derived[Trigger]
        end Trigger

        given Schema[Activity[Answer]] = Schema.derived[Activity[Answer]]

    end Activity

    /** The format of a message's `text`. */
    @tagOnly
    sealed trait TextFormat derives CanEqual
    object TextFormat:
        @rename("markdown") case object Markdown       extends TextFormat
        @rename("plain") case object Plain             extends TextFormat
        @rename("xml") case object Xml                 extends TextFormat
        @catchAll final case class Other(name: String) extends TextFormat
        given Schema[TextFormat] = Schema.derived[TextFormat]
    end TextFormat

    /** How the Teams client shows a message. */
    @tagOnly
    sealed trait Importance derives CanEqual
    object Importance:
        @rename("low") case object Low                 extends Importance
        @rename("normal") case object Normal           extends Importance
        @rename("high") case object High               extends Importance
        @catchAll final case class Other(name: String) extends Importance
        given Schema[Importance] = Schema.derived[Importance]
    end Importance

    /** Whether an `installationUpdate` added or removed the bot, by install or by an app upgrade. */
    @tagOnly
    sealed trait InstallAction derives CanEqual
    object InstallAction:
        @rename("add") case object Add                      extends InstallAction
        @rename("remove") case object Remove                extends InstallAction
        @rename("add-upgrade") case object AddUpgrade       extends InstallAction
        @rename("remove-upgrade") case object RemoveUpgrade extends InstallAction
        @catchAll final case class Other(name: String)      extends InstallAction
        given Schema[InstallAction] = Schema.derived[InstallAction]
    end InstallAction

    /** A reaction on a message, by the name Teams gives it. */
    final case class Reaction(@rename("type") kind: Reaction.Kind) derives CanEqual, Schema
    object Reaction:
        /** The reactions Teams documents, and any other by its name. */
        @tagOnly
        sealed trait Kind derives CanEqual
        object Kind:
            @rename("like") case object Like               extends Kind
            @rename("heart") case object Heart             extends Kind
            @rename("laugh") case object Laugh             extends Kind
            @rename("surprised") case object Surprised     extends Kind
            @rename("sad") case object Sad                 extends Kind
            @rename("angry") case object Angry             extends Kind
            @rename("plusOne") case object PlusOne         extends Kind
            @catchAll final case class Other(name: String) extends Kind
            given Schema[Kind] = Schema.derived[Kind]
        end Kind
    end Reaction

    /** A user or a bot as the channel names it: the Bot Framework's `ChannelAccount`. */
    final case class Account(
        id: UserId,
        name: Maybe[String] = Absent,
        aadObjectId: Maybe[AadObjectId] = Absent,
        role: Maybe[Account.Role] = Absent
    ) derives CanEqual

    object Account:
        given Schema[Account] = Schema[Account].omitNone

        /** Whether the account is a user's or a bot's. */
        @tagOnly
        sealed trait Role derives CanEqual
        object Role:
            @rename("user") case object User               extends Role
            @rename("bot") case object Bot                 extends Role
            @catchAll final case class Other(name: String) extends Role
            given Schema[Role] = Schema.derived[Role]
        end Role
    end Account

    /** A conversation as an Activity names it: the Bot Framework's `ConversationAccount`. `conversationType` is `personal` for a
      * one-on-one chat, `groupChat`, or `channel`.
      */
    final case class ConversationAccount(
        id: ConversationId,
        name: Maybe[String] = Absent,
        isGroup: Maybe[Boolean] = Absent,
        conversationType: Maybe[ConversationAccount.Kind] = Absent,
        tenantId: Maybe[TenantId] = Absent
    ) derives CanEqual

    object ConversationAccount:
        given Schema[ConversationAccount] = Schema[ConversationAccount].omitNone

        /** The kind of conversation. */
        @tagOnly
        sealed trait Kind derives CanEqual
        object Kind:
            @rename("personal") case object Personal       extends Kind
            @rename("groupChat") case object GroupChat     extends Kind
            @rename("channel") case object Channel         extends Kind
            @catchAll final case class Other(name: String) extends Kind
            given Schema[Kind] = Schema.derived[Kind]
        end Kind
    end ConversationAccount

    /** The Teams-specific part of an Activity: the tenant, the team and channel it happened in, the event a `conversationUpdate`
      * reports, and the channel a user chose when installing the bot in a team. Teams documents these keys; any other is ignored.
      */
    final case class ChannelData(
        tenant: Maybe[ChannelData.Tenant] = Absent,
        team: Maybe[ChannelData.Team] = Absent,
        channel: Maybe[ChannelData.Channel] = Absent,
        eventType: Maybe[ChannelData.Event] = Absent,
        settings: Maybe[ChannelData.Settings] = Absent
    ) derives CanEqual

    object ChannelData:
        given Schema[ChannelData] = Schema[ChannelData].omitNone

        /** The tenant the Activity belongs to. */
        final case class Tenant(id: TenantId) derives CanEqual, Schema

        /** A team, with the name and group id Teams sends on some events. */
        final case class Team(id: TeamId, name: Maybe[String] = Absent, aadGroupId: Maybe[String] = Absent) derives CanEqual
        object Team:
            given Schema[Team] = Schema[Team].omitNone

        /** A channel of a team. */
        final case class Channel(id: ChannelId, name: Maybe[String] = Absent) derives CanEqual
        object Channel:
            given Schema[Channel] = Schema[Channel].omitNone

        /** The channel a user selected when installing the bot in a team. */
        final case class Settings(selectedChannel: Maybe[Channel] = Absent) derives CanEqual
        object Settings:
            given Schema[Settings] = Schema[Settings].omitNone

        /** What a `conversationUpdate` reports, `eventType` in `channelData`. Teams adds events over time; another arrives as `Other`. */
        @tagOnly
        sealed trait Event derives CanEqual
        object Event:
            @rename("channelCreated") case object ChannelCreated                    extends Event
            @rename("channelRenamed") case object ChannelRenamed                    extends Event
            @rename("channelDeleted") case object ChannelDeleted                    extends Event
            @rename("channelRestored") case object ChannelRestored                  extends Event
            @rename("teamMemberAdded") case object TeamMemberAdded                  extends Event
            @rename("teamMemberRemoved") case object TeamMemberRemoved              extends Event
            @rename("teamRenamed") case object TeamRenamed                          extends Event
            @rename("teamDeleted") case object TeamDeleted                          extends Event
            @rename("teamArchived") case object TeamArchived                        extends Event
            @rename("teamUnarchived") case object TeamUnarchived                    extends Event
            @rename("teamRestored") @alias("teamrestored") case object TeamRestored extends Event
            @catchAll final case class Other(name: String)                          extends Event
            given Schema[Event] = Schema.derived[Event]
        end Event
    end ChannelData

    /** Metadata an Activity carries beside its text: a mention of a user or the bot, or another kind by its `type` and JSON. */
    @discriminator("type")
    sealed trait Entity derives CanEqual
    object Entity:
        /** `mentioned` is mentioned in the text as `text` (`<at>Name</at>`). */
        @rename("mention")
        final case class Mention(mentioned: Account, text: Maybe[String] = Absent) extends Entity derives CanEqual
        object Mention:
            given Schema[Mention] = Schema[Mention].omitNone

        /** An entity of a type the module does not model, by its `type` and its whole JSON. */
        @catchAll final case class Other(`type`: String, payload: RawJson) extends Entity derives CanEqual

        given Schema[Entity] = Schema.derived[Entity]
    end Entity

    /** A file or a card in a message, told apart by `contentType`. An Adaptive Card is modelled; any other card or file is kept as
      * `Other`, with its JSON.
      */
    @discriminator("contentType")
    sealed trait Attachment derives CanEqual
    object Attachment:
        /** An Adaptive Card. */
        @rename("application/vnd.microsoft.card.adaptive")
        final case class AdaptiveCard(content: Card) extends Attachment derives CanEqual, Schema

        /** An attachment the module does not model, by its `contentType` and its whole JSON. */
        @catchAll final case class Other(contentType: String, payload: RawJson) extends Attachment derives CanEqual

        given Schema[Attachment] = Schema.derived[Attachment]
    end Attachment

    /** A JSON value the module keeps as it came: the payload of an Activity, an invoke or an entity it does not model, or a field
      * Microsoft types as an open object (a card's `data`, a message's `value`).
      *
      * Note: it can hold what a user wrote, so `toString` renders `Teams.RawJson(<redacted>)`. Its `Schema` is the JSON value itself, so
      * an `Unknown` encodes back to what Teams sent.
      */
    final class RawJson private (val json: Structure.Value):
        /** The value as JSON text. */
        def value(using Frame): String = Json.encode(json)(using Structure.Value.valueSchema)

        override def equals(other: Any): Boolean =
            other match
                case that: RawJson => json == that.json
                case _             => false
        override def hashCode: Int    = json.hashCode
        override def toString: String = "Teams.RawJson(<redacted>)"
    end RawJson

    object RawJson:
        /** The value `json`. */
        def apply(json: Structure.Value): RawJson = new RawJson(json)
        given CanEqual[RawJson, RawJson]          = CanEqual.derived

        // Dynamic by definition: the payload of a kind the model does not declare, or a field Microsoft leaves unshaped.
        given Schema[RawJson] = Structure.Value.valueSchema.transform(RawJson(_))(_.json)
    end RawJson

    /** A point in a conversation, as the Bot Framework's `ConversationReference`: where to send (`serviceUrl`), which conversation,
      * and, from a received Activity, the bot, the user and the Activity.
      *
      * A received Activity's `reference` is one; `createConversation` answers one. Its `Schema` is that JSON, so a caller stores it and
      * sends to the conversation later, after a restart. Its `serviceUrl` is checked against `TeamsConfig.serviceHosts` on every call.
      */
    final case class ConversationReference(
        serviceUrl: ServiceUrl,
        conversation: ConversationAccount,
        bot: Maybe[Account] = Absent,
        user: Maybe[Account] = Absent,
        activityId: Maybe[ActivityId] = Absent,
        @rename("channelId") channel: BotChannel = BotChannel.MsTeams
    ) derives CanEqual

    object ConversationReference:
        given Schema[ConversationReference] = Schema[ConversationReference].omitNone
    end ConversationReference

    /** The messages the bot sends. */
    object Message:
        /** A message `send`, `reply` and `edit` send: text, cards and files, and the mentions the text holds.
          *
          * `summary` is the text a notification shows, and `importance` how the client flags it. Its `Schema` is the Activity JSON
          * Teams receives, a `message` with every absent field left out.
          */
        final case class Create(
            text: Maybe[String] = Absent,
            textFormat: Maybe[TextFormat] = Absent,
            attachments: Chunk[Attachment] = Chunk.empty,
            entities: Chunk[Entity] = Chunk.empty,
            summary: Maybe[String] = Absent,
            importance: Maybe[Importance] = Absent
        ) derives CanEqual

        object Create:
            given Schema[Create] = Schema[Create].add("type")(_ => "message").omitNone.omitEmptyCollections

            /** A message of `text` alone. */
            def text(text: String): Create = Create(text = Present(text))

            /** A message holding `card`. */
            def card(card: Card): Create = Create(attachments = Chunk(Attachment.AdaptiveCard(card)))
        end Create
    end Message

    /** Starting a conversation. */
    object Conversation:
        /** What `createConversation` sends, the Bot Framework's `ConversationParameters`: the bot, the members, the tenant, and the
          * channel (in `channelData`) or user to start it with, and an optional first message.
          */
        final case class Create(
            bot: Maybe[Account] = Absent,
            members: Chunk[Account] = Chunk.empty,
            isGroup: Maybe[Boolean] = Absent,
            tenantId: Maybe[TenantId] = Absent,
            channelData: Maybe[ChannelData] = Absent,
            activity: Maybe[Message.Create] = Absent,
            topicName: Maybe[String] = Absent
        ) derives CanEqual

        object Create:
            given Schema[Create] = Schema[Create].omitNone.omitEmptyCollections
    end Conversation

    /** Reading a conversation's members, a page at a time. */
    object Member:
        /** One page request: `size` members (50 to 500, the bounds Teams documents), after `continuation` when it is a later page. */
        final case class Page private (size: Int, continuation: Maybe[String]) derives CanEqual

        object Page:
            /** The smallest page Teams serves. */
            inline val MinSize = 50

            /** The largest page Teams serves. */
            inline val MaxSize = 500

            /** The first page of 200 members, Teams' default. */
            val first: Page = new Page(200, Absent)

            /** A page of `size` members after `continuation`, or a [[kyo.TeamsInvalidPageException]] when `size` is out of bounds. */
            def init(size: Int, continuation: Maybe[String] = Absent)(using Frame): Result[TeamsInvalidPageException, Page] =
                if size >= MinSize && size <= MaxSize then Result.succeed(new Page(size, continuation))
                else Result.fail(TeamsInvalidPageException(TeamsInvalidPageException.Problem.Size(size, MinSize, MaxSize)))
        end Page

        /** A page of members, and the token of the next page when there is one. */
        final case class Paged(members: Chunk[Account] = Chunk.empty, continuationToken: Maybe[String] = Absent) derives CanEqual
        object Paged:
            given Schema[Paged] = Schema[Paged].omitNone
    end Member

    /** An Adaptive Card: its schema `version`, the elements of its `body`, its `actions`, and an optional `refresh`.
      *
      * Elements and actions are sums tagged by `type`; each has an `Other` case holding an element or action the module does not model
      * as JSON, so every feature of the format stays reachable. `Action.Execute` (the Universal Action Model) reaches the bot as an
      * [[kyo.Teams.Activity.AdaptiveCardAction]] invoke, answered with a [[kyo.Teams.Card.ActionResponse]].
      *
      * Note: Microsoft gives two minimum versions for `Action.Execute` and `refresh`: 1.4 in the Universal Action Model's schema
      * documentation and 1.5 in Teams' own; the module sets no default version.
      *
      * @see
      *   [[kyo.Teams.Message.Create.card]] a message holding a card
      */
    final case class Card(
        version: Card.Version,
        body: Chunk[Card.Element] = Chunk.empty,
        actions: Chunk[Card.Action] = Chunk.empty,
        refresh: Maybe[Card.Refresh] = Absent
    ) derives CanEqual

    object Card:
        given Schema[Card] = Schema[Card].add("type")(_ => "AdaptiveCard").omitNone.omitEmptyCollections

        /** The Adaptive Card schema version a card targets, `major.minor`. */
        type Version = Version.Value
        object Version:
            opaque type Value = String

            /** The version `text`, `major.minor` with each part 0 to 99, or a [[kyo.TeamsInvalidCardException]]. */
            def init(text: String)(using Frame): Result[TeamsInvalidCardException, Version] =
                if valid(text) then Result.succeed(text)
                else Result.fail(TeamsInvalidCardException(TeamsInvalidCardException.Problem.Version))

            extension (self: Version) def value: String = self

            given CanEqual[Version, Version] = CanEqual.derived
            given Schema[Version]            = Schema.stringSchema.transformVia(text => init(text))(identity)

            private def valid(text: String): Boolean =
                text.split('.') match
                    case Array(major, minor) => part(major) && part(minor)
                    case _                   => false
            private def part(text: String): Boolean = text.nonEmpty && text.length <= 2 && text.forall(c => c >= '0' && c <= '9')
        end Version

        /** A card's automatic refresh: `action` runs when one of `userIds` (at most 60) views the card. */
        final case class Refresh private (@transform(Action.Execute.Standalone) action: Action.Execute, userIds: Chunk[UserId])
            derives CanEqual

        object Refresh:
            /** The most users Teams refreshes a card for. */
            inline val MaxUsers = 60

            /** The refresh, or a [[kyo.TeamsInvalidCardException]] when `userIds` lists more than 60 users. */
            def init(action: Action.Execute, userIds: Chunk[UserId])(using Frame): Result[TeamsInvalidCardException, Refresh] =
                if userIds.size <= MaxUsers then Result.succeed(new Refresh(action, userIds))
                else Result.fail(TeamsInvalidCardException(TeamsInvalidCardException.Problem.RefreshUsers(userIds.size, MaxUsers)))

            given Schema[Refresh] =
                Schema.derivedVia((action: Action.Execute, userIds: Chunk[UserId]) => init(action, userIds))
        end Refresh

        /** An element of a card's body. */
        @discriminator("type")
        sealed trait Element derives CanEqual
        object Element:
            /** A block of text. */
            @rename("TextBlock")
            final case class TextBlock(
                text: String,
                wrap: Maybe[Boolean] = Absent,
                size: Maybe[Style.Size] = Absent,
                weight: Maybe[Style.Weight] = Absent,
                color: Maybe[Style.Color] = Absent,
                isSubtle: Maybe[Boolean] = Absent
            ) extends Element derives CanEqual
            object TextBlock:
                given Schema[TextBlock] = Schema[TextBlock].omitNone

            /** An image at `url`, an http, https or data URI the client fetches. */
            @rename("Image")
            final case class Image(url: String, altText: Maybe[String] = Absent) extends Element derives CanEqual
            object Image:
                given Schema[Image] = Schema[Image].omitNone

            /** A group of elements. */
            @rename("Container")
            final case class Container(items: Chunk[Element]) extends Element derives CanEqual, Schema

            /** Columns side by side. */
            @rename("ColumnSet")
            final case class ColumnSet(columns: Chunk[Column]) extends Element derives CanEqual, Schema

            /** Pairs of a title and a value. */
            @rename("FactSet")
            final case class FactSet(facts: Chunk[Fact]) extends Element derives CanEqual, Schema

            /** Actions shown among the body's elements. */
            @rename("ActionSet")
            final case class ActionSet(actions: Chunk[Action]) extends Element derives CanEqual, Schema

            /** A text input, its value sent with an action's `data` under `id`. */
            @rename("Input.Text")
            final case class InputText(
                id: String,
                label: Maybe[String] = Absent,
                placeholder: Maybe[String] = Absent,
                value: Maybe[String] = Absent,
                isMultiline: Maybe[Boolean] = Absent,
                isRequired: Maybe[Boolean] = Absent
            ) extends Element derives CanEqual
            object InputText:
                given Schema[InputText] = Schema[InputText].omitNone

            /** A number input. */
            @rename("Input.Number")
            final case class InputNumber(
                id: String,
                label: Maybe[String] = Absent,
                placeholder: Maybe[String] = Absent,
                value: Maybe[Double] = Absent,
                min: Maybe[Double] = Absent,
                max: Maybe[Double] = Absent,
                isRequired: Maybe[Boolean] = Absent
            ) extends Element derives CanEqual
            object InputNumber:
                given Schema[InputNumber] = Schema[InputNumber].omitNone

            /** A date input; `value` is the date as `YYYY-MM-DD`, as the card format writes it. */
            @rename("Input.Date")
            final case class InputDate(
                id: String,
                label: Maybe[String] = Absent,
                value: Maybe[String] = Absent,
                isRequired: Maybe[Boolean] = Absent
            ) extends Element derives CanEqual
            object InputDate:
                given Schema[InputDate] = Schema[InputDate].omitNone

            /** A toggle, sending `valueOn` or `valueOff`. */
            @rename("Input.Toggle")
            final case class InputToggle(
                id: String,
                title: String,
                label: Maybe[String] = Absent,
                value: Maybe[String] = Absent,
                valueOn: Maybe[String] = Absent,
                valueOff: Maybe[String] = Absent,
                isRequired: Maybe[Boolean] = Absent
            ) extends Element derives CanEqual
            object InputToggle:
                given Schema[InputToggle] = Schema[InputToggle].omitNone

            /** A choice among `choices`, one or several. */
            @rename("Input.ChoiceSet")
            final case class InputChoiceSet(
                id: String,
                choices: Chunk[Choice],
                label: Maybe[String] = Absent,
                value: Maybe[String] = Absent,
                isMultiSelect: Maybe[Boolean] = Absent,
                isRequired: Maybe[Boolean] = Absent
            ) extends Element derives CanEqual
            object InputChoiceSet:
                given Schema[InputChoiceSet] = Schema[InputChoiceSet].omitNone

            /** An element the module does not model, by its `type` and its whole JSON. */
            @catchAll final case class Other(`type`: String, payload: RawJson) extends Element derives CanEqual

            given Schema[Element] = Schema.derived[Element]
        end Element

        /** A column of a [[kyo.Teams.Card.Element.ColumnSet]]. */
        final case class Column(items: Chunk[Element], width: Maybe[String] = Absent) derives CanEqual
        object Column:
            given Schema[Column] = Schema[Column].add("type")(_ => "Column").omitNone

        /** A fact of a [[kyo.Teams.Card.Element.FactSet]]. */
        final case class Fact(title: String, value: String) derives CanEqual, Schema

        /** A choice of a [[kyo.Teams.Card.Element.InputChoiceSet]]. */
        final case class Choice(title: String, value: String) derives CanEqual, Schema

        /** The text styles a [[kyo.Teams.Card.Element.TextBlock]] takes. */
        object Style:
            /** The text size. */
            @tagOnly
            sealed trait Size derives CanEqual
            object Size:
                @rename("default") case object Default         extends Size
                @rename("small") case object Small             extends Size
                @rename("medium") case object Medium           extends Size
                @rename("large") case object Large             extends Size
                @rename("extraLarge") case object ExtraLarge   extends Size
                @catchAll final case class Other(name: String) extends Size
                given Schema[Size] = Schema.derived[Size]
            end Size

            /** The text weight. */
            @tagOnly
            sealed trait Weight derives CanEqual
            object Weight:
                @rename("default") case object Default         extends Weight
                @rename("lighter") case object Lighter         extends Weight
                @rename("bolder") case object Bolder           extends Weight
                @catchAll final case class Other(name: String) extends Weight
                given Schema[Weight] = Schema.derived[Weight]
            end Weight

            /** The text color. */
            @tagOnly
            sealed trait Color derives CanEqual
            object Color:
                @rename("default") case object Default         extends Color
                @rename("dark") case object Dark               extends Color
                @rename("light") case object Light             extends Color
                @rename("accent") case object Accent           extends Color
                @rename("good") case object Good               extends Color
                @rename("warning") case object Warning         extends Color
                @rename("attention") case object Attention     extends Color
                @catchAll final case class Other(name: String) extends Color
                given Schema[Color] = Schema.derived[Color]
            end Color
        end Style

        /** An action of a card. */
        @discriminator("type")
        sealed trait Action derives CanEqual
        object Action:
            /** The Universal Action Model's action: it reaches the bot as an `adaptiveCard/action` invoke carrying `verb` and `data`
              * with the card's input values, and the bot answers with the card or message to show next.
              */
            @rename("Action.Execute")
            final case class Execute(
                title: Maybe[String] = Absent,
                verb: Maybe[String] = Absent,
                id: Maybe[String] = Absent,
                data: Maybe[RawJson] = Absent
            ) extends Action derives CanEqual
            object Execute:
                given Schema[Execute] = Schema[Execute].omitNone

                /** An `Execute` held by a field outside the `Action` sum, written with its `type`. Inside the sum the discriminator
                  * writes the tag, so the given leaves it out: a variant field under the tag key would be overwritten by it.
                  */
                object Standalone extends Transformer.Of[Execute](
                        Schema.derived[Execute].add("type")(_ => "Action.Execute").omitNone
                    )
            end Execute

            /** Sends `data` with the card's input values as a message Activity's `value`. */
            @rename("Action.Submit")
            final case class Submit(title: Maybe[String] = Absent, data: Maybe[RawJson] = Absent) extends Action derives CanEqual
            object Submit:
                given Schema[Submit] = Schema[Submit].omitNone

            /** Opens `url`, which the client opens and the module never requests. */
            @rename("Action.OpenUrl")
            final case class OpenUrl(url: String, title: Maybe[String] = Absent) extends Action derives CanEqual
            object OpenUrl:
                given Schema[OpenUrl] = Schema[OpenUrl].omitNone

            /** Shows `card` below the current one. */
            @rename("Action.ShowCard")
            final case class ShowCard(card: Card, title: Maybe[String] = Absent) extends Action derives CanEqual
            object ShowCard:
                given Schema[ShowCard] = Schema[ShowCard].omitNone

            /** Shows or hides the elements whose ids `targetElements` lists. */
            @rename("Action.ToggleVisibility")
            final case class ToggleVisibility(targetElements: Chunk[String], title: Maybe[String] = Absent) extends Action
                derives CanEqual
            object ToggleVisibility:
                given Schema[ToggleVisibility] = Schema[ToggleVisibility].omitNone

            /** An action the module does not model, by its `type` and its whole JSON. */
            @catchAll final case class Other(`type`: String, payload: RawJson) extends Action derives CanEqual

            given Schema[Action] = Schema.derived[Action]
        end Action

        /** The answer to an [[kyo.Teams.Activity.AdaptiveCardAction]]: the card or the message the client shows next, or a failure,
          * each with the `statusCode` and `type` the Universal Action Model gives it, written in a `200` reply.
          */
        @discriminator("type")
        sealed trait ActionResponse derives CanEqual
        object ActionResponse:
            /** Status 200: show `card` in place of the current one. */
            @rename("application/vnd.microsoft.card.adaptive")
            final case class ShowCard(card: Card) extends ActionResponse derives CanEqual
            object ShowCard:
                given Schema[ShowCard] = Schema[ShowCard].rename(_.card, "value").add("statusCode")(_ => 200)

            /** Status 200: show `text` as a message. */
            @rename("application/vnd.microsoft.activity.message")
            final case class ShowMessage(text: String) extends ActionResponse derives CanEqual
            object ShowMessage:
                given Schema[ShowMessage] = Schema[ShowMessage].rename(_.text, "value").add("statusCode")(_ => 200)

            /** Status 400: the request was invalid. */
            @rename("application/vnd.microsoft.error")
            final case class BadRequest(error: Failure) extends ActionResponse derives CanEqual
            object BadRequest:
                given Schema[BadRequest] = Schema[BadRequest].rename(_.error, "value").add("statusCode")(_ => 400)

            /** Status 412: the single sign-on flow failed. */
            @rename("application/vnd.microsoft.error.preconditionFailed")
            final case class PreconditionFailed(error: Failure) extends ActionResponse derives CanEqual
            object PreconditionFailed:
                given Schema[PreconditionFailed] = Schema[PreconditionFailed].rename(_.error, "value").add("statusCode")(_ => 412)

            /** Status 401: the client must ask the user to sign in, with `card`, an OAuth card. */
            @rename("application/vnd.microsoft.activity.loginRequest")
            final case class LoginRequest(card: RawJson) extends ActionResponse derives CanEqual
            object LoginRequest:
                given Schema[LoginRequest] = Schema[LoginRequest].rename(_.card, "value").add("statusCode")(_ => 401)

            /** A failure's code and message. */
            final case class Failure(code: String, message: String) derives CanEqual, Schema

            given Schema[ActionResponse] = Schema.derived[ActionResponse]
        end ActionResponse
    end Card

    /** The answer to an [[kyo.Teams.Activity.OtherInvoke]]: the HTTP status of the reply and its JSON body, as that invoke's own
      * documentation gives them.
      */
    final case class InvokeResponse(status: HttpStatus, body: Maybe[RawJson] = Absent) derives CanEqual

    object InvokeResponse:
        /** Status 200 with `body`. */
        def ok(body: RawJson): InvokeResponse = InvokeResponse(HttpStatus(200), Present(body))

        // The reply's status line and body, not JSON: the root's `Schema` reports this answer's shape only.
        given Schema[InvokeResponse] =
            summon[Schema[(Int, Maybe[RawJson])]].transformVia((s, b) =>
                HttpStatus.init(s) match
                    case Result.Success(status) => Result.succeed(InvokeResponse(status, b))
                    case _                      => Result.fail(s"status $s is outside 100 to 599")
            )(r => (r.status.code, r.body))
    end InvokeResponse

end Teams
