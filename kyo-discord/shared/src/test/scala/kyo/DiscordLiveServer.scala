package kyo

import kyo.net.TlsTestCertShared

/** A Spacebar container standing in for Discord for one leaf, removed when the leaf's `Scope` closes.
  *
  * Spacebar (github.com/spacebarchat/server) is AGPL-3.0. The suite only runs it: the image is built from
  * `shared/src/test/spacebar/Containerfile`, out of the source at a pinned commit fetched as GitHub's tarball and checked against its
  * SHA-256, on bases pinned by digest, and nothing of it is distributed or linked. A CI run that selects this module builds it
  * before the tests (`scripts/fixture-images.sh`), so a leaf never reaches the internet; where it was not built,
  * [[DiscordLiveServer.init]] fails with the command that builds it. Spacebar needs Postgres: its entities declare `jsonb` columns and it excludes the SQLite drivers,
  * so the runtime stage is the Postgres image with the built server and the build stage's `node` copied in. The build changes three
  * lines where Spacebar's answer departs from Discord's documented one, each checked by a `grep` so a moved line fails the build: a
  * message with no components serializes them as `[]`, not `null`; a message's `member.roles` are role ids, not role objects; and an
  * interaction's `UpdateMessage` answer sets the message's content, not only its embeds and components.
  *
  * The module refuses a Gateway url that is not `wss`, so a TLS terminator in the container (Node's `tls`, piping each connection to
  * the server's one port) serves the API and the Gateway on `TlsTestCertShared`'s certificate, and the client trusts it through
  * `DiscordConfig.tls`. Spacebar builds the urls it hands out (`/gateway/bot`'s among them) from its config file, so the host port is
  * chosen before the container starts and published as itself.
  *
  * Each server holds one person, who owns the application, its bot and a guild the bot was added to with Administrator; `channel` is
  * the guild's text channel. The person's actions are calls on Spacebar's own API with their user token, so the bot receives each one
  * as the Gateway event Discord sends.
  */
final case class DiscordLiveServer(
    container: Container,
    port: Int,
    botToken: String,
    personToken: String,
    application: Discord.ApplicationId,
    bot: Discord.UserId,
    guild: Discord.GuildId,
    channel: Discord.ChannelId
):

    import DiscordLiveServer.*

    private def api: String = s"https://$Host:$port/api/v10"

    def config(intents: Discord.Intents)(using Frame): DiscordConfig =
        DiscordConfig.init(
            Discord.Token.init(botToken).getOrThrow,
            intents,
            baseUrl = HttpUrl.parse(api).getOrThrow,
            tls = Tls
        ).getOrThrow

    /** The person sends `content` in `in`, replying to `replyTo` when given. Answers the message's id. */
    def says(in: Discord.ChannelId, content: String, replyTo: Maybe[Discord.MessageId] = Absent)(using
        Frame
    ): Discord.MessageId < (Async & Abort[HttpException]) =
        val reference = replyTo.map(id => Reference(id.value.toString, in.value.toString))
        HttpClient.postJson[Created](url(api, s"channels/${in.value}/messages"), NewMessage(content, reference), auth(personToken))
            .map(created => Discord.MessageId.parse(created.id).getOrThrow)
    end says

    /** The person edits their message `message` in `in` to `content`. */
    def edits(in: Discord.ChannelId, message: Discord.MessageId, content: String)(using Frame): Unit < (Async & Abort[HttpException]) =
        HttpClient.patchJson[Created](url(api, s"channels/${in.value}/messages/${message.value}"), Edit(content), auth(personToken)).unit

    /** The person deletes their message `message` in `in`. */
    def deletes(in: Discord.ChannelId, message: Discord.MessageId)(using Frame): Unit < (Async & Abort[HttpException]) =
        HttpClient.deleteUnit(url(api, s"channels/${in.value}/messages/${message.value}"), auth(personToken))

    /** The person reacts to `message` in `in` with the unicode `emoji`. */
    def reacts(in: Discord.ChannelId, message: Discord.MessageId, emoji: String)(using Frame): Unit < (Async & Abort[HttpException]) =
        HttpClient.putUnit(
            url(api, s"channels/${in.value}/messages/${message.value}/reactions/${percentEncoded(emoji)}/@me"),
            headers = auth(personToken)
        )

    /** The person sends the bot `content` in their direct message channel with it. Answers that channel. */
    def messagesBot(content: String)(using Frame): Discord.ChannelId < (Async & Abort[HttpException]) =
        HttpClient.postJson[Created](url(api, "users/@me/channels"), Recipient(bot.value.toString), auth(personToken)).map { dm =>
            val channel = Discord.ChannelId.parse(dm.id).getOrThrow
            says(channel, content).andThen(channel)
        }

    /** The person presses the button `customId` on the bot's `message` in `in`. */
    def presses(in: Discord.ChannelId, message: Discord.MessageId, customId: String)(using Frame): Unit < (Async & Abort[HttpException]) =
        interaction(Interaction(
            3,
            application.value.toString,
            guild.value.toString,
            in.value.toString,
            Present(message.value.toString),
            InteractionData(Present(2), Present(customId), Absent, Absent, Absent, Chunk.empty)
        ))

    /** The person runs the chat input command `command`, named `name`, in `in` with one string option `option` valued `value`. */
    def runs(in: Discord.ChannelId, command: Discord.CommandId, name: String, option: String, value: String)(using
        Frame
    ): Unit < (Async & Abort[HttpException]) =
        interaction(Interaction(
            2,
            application.value.toString,
            guild.value.toString,
            in.value.toString,
            Absent,
            InteractionData(
                Absent,
                Absent,
                Present(command.value.toString),
                Present(name),
                Present(1),
                Chunk(OptionValue(option, 3, value))
            )
        ))

    /** The tail of the container's log, for a leaf that failed. */
    def postMortem(using Frame): String < Async = ContainerPredef.postMortem(container)

    // Spacebar answers an interaction with an empty 204, which no JSON route decodes.
    private def interaction(body: Interaction)(using Frame): Unit < (Async & Abort[HttpException]) =
        HttpClient.postUnit(url(api, "interactions"), Json.encode(body), auth(personToken).add("Content-Type", "application/json"))

end DiscordLiveServer

object DiscordLiveServer:

    /** The tag `scripts/fixture-images.sh` builds: the Spacebar commit's prefix, then the build's revision, which a change to the
      * Containerfile or its scripts increments here and there together.
      */
    val Image: ContainerImage = ContainerImage("localhost/kyo-discord-spacebar", "0eb6f04f6d-4")

    private val Context = "kyo-discord/shared/src/test/spacebar"

    val Host: String = "127.0.0.1"

    /** The client trusts the terminator's self-signed certificate; `TlsTestCertShared` is the one it serves. */
    val Tls: HttpTlsConfig = HttpTlsConfig(trustAll = true)

    def init(using Frame): DiscordLiveServer < (Async & Scope & Abort[ContainerException | HttpException]) =
        for
            _                 <- built
            (container, port) <- started(PortAttempts)(port => Container.init(containerConfig(port)).map((_, port)))
            server            <- populate(container, port)
        yield server

    /** How many host ports a server tries before its start fails with the conflict. */
    private val PortAttempts = 5

    // A port free on the host can still be held inside the container daemon's VM (podman publishes it from there), which the host's
    // check cannot see; the daemon then refuses it as allocated, and a fresh port is the answer.
    private def started[A](attempts: Int)(start: Int => A < (Async & Scope & Abort[ContainerException | HttpException]))(using
        Frame
    ): A < (Async & Scope & Abort[ContainerException | HttpException]) =
        freePort.map { port =>
            Abort.run[ContainerPortConflictException](start(port)).map {
                case Result.Success(value)             => value
                case Result.Failure(_) if attempts > 1 => started(attempts - 1)(start)
                case Result.Failure(conflict)          => Abort.fail(conflict)
                case Result.Panic(e)                   => Abort.panic(e)
            }
        }

    /** Registers the person, their application and its bot, and a guild the bot is added to with Administrator. */
    private def populate(container: Container, port: Int)(using
        Frame
    ): DiscordLiveServer < (Async & Abort[HttpException]) =
        val api = s"https://$Host:$port/api/v10"
        for
            person <-
                HttpClient.postJson[TokenAnswer](url(api, "auth/register"), Register("kyoperson", "kyo-live-password", consent = true))
            owner = auth(person.token)
            app      <- HttpClient.postJson[Created](url(api, "applications"), Named("kyo-live"), owner)
            botToken <- HttpClient.postJson[TokenAnswer](url(api, s"applications/${app.id}/bot"), Empty(), owner)
            guild    <- HttpClient.postJson[Created](url(api, "guilds"), Named("kyo-live"), owner)
            _        <- HttpClient.postJson[Location](
                url(api, "oauth2/authorize"),
                Authorize(true, guild.id, "8"),
                owner,
                HttpQueryParams.empty.add("client_id", app.id).add("scope", "bot")
            )
            channels <- HttpClient.getJson[Chunk[ChannelRow]](url(api, s"guilds/${guild.id}/channels"), owner)
            me       <- HttpClient.getJson[Created](url(api, "users/@me"), auth(s"Bot ${botToken.token}"))
        yield DiscordLiveServer(
            container,
            port,
            botToken.token,
            person.token,
            Discord.ApplicationId.parse(app.id).getOrThrow,
            Discord.UserId.parse(me.id).getOrThrow,
            Discord.GuildId.parse(guild.id).getOrThrow,
            Discord.ChannelId.parse(channels.filter(_.`type` == 0).head.id).getOrThrow
        )
        end for
    end populate

    // A missing local tag would otherwise be pulled from a registry named `localhost`, failing with a connection error that names
    // neither the image's origin nor the fix.
    private def built(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.run[ContainerException](ContainerImage.inspect(Image)).map {
            case Result.Success(_)                                 => Kyo.unit
            case Result.Failure(_: ContainerImageMissingException) =>
                Abort.panic(new IllegalStateException(
                    s"${Image.reference} is not built; from the repository root run: podman build -t ${Image.reference} -f $Context/Containerfile $Context"
                ))
            case Result.Failure(other) => Abort.fail(other)
            case Result.Panic(e)       => Abort.panic(e)
        }

    private def freePort(using Frame): Int < (Async & Abort[HttpException]) =
        Scope.run(HttpServer.init(0, Host)().map(_.port))

    private def containerConfig(port: Int)(using Frame): Container.Config =
        Container.Config.default
            .copy(image = Image)
            .port(8443, port)
            .requireService(true)
            .env("DB_SYNC", "true")
            .env("APPLY_DB_MIGRATIONS", "false")
            .env("SPACEBAR_CONFIG_JSON", Json.encode(spacebarConfig(port)))
            .env("TLS_CERT", TlsTestCertShared.certPem)
            .env("TLS_KEY", TlsTestCertShared.keyPem)
            .healthCheck(Container.HealthCheck.init(ready(port)))

    private def ready(port: Int)(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.run[HttpException](HttpClient.getText(s"https://$Host:$port/api/v10/ping")).map {
            case Result.Success(_) => Kyo.unit
            case other             => Abort.fail(ContainerHealthCheckException(container.id, s"spacebar: $other", attempts = 1))
        }

    private def url(api: String, path: String)(using Frame): HttpUrl = HttpUrl.parse(s"$api/$path").getOrThrow

    private def auth(token: String): HttpHeaders = HttpHeaders.empty.add("Authorization", token)

    private def percentEncoded(text: String): String =
        text.getBytes(java.nio.charset.StandardCharsets.UTF_8).map(b => f"%%${b & 0xff}%02X").mkString

    // Spacebar runs migrations only for Postgres through an advisory lock, and `DB_SYNC` creates the schema from the entities instead.
    // The endpoints are the terminator's, and `general.serverName` must be set or the server refuses to start.
    private def spacebarConfig(port: Int): SpacebarConfig =
        val public = s"https://$Host:$port"
        SpacebarConfig(
            General(public),
            Endpoint(s"wss://$Host:$port", Absent),
            Endpoint(s"$public/api", Absent),
            Endpoint(public, Present("http://127.0.0.1:3001")),
            RegisterSettings(requireCaptcha = false, Required(false), Required(false))
        )
    end spacebarConfig

    // The JSON of Spacebar's API and config file.

    final private case class Register(username: String, password: String, consent: Boolean) derives Schema
    final private case class TokenAnswer(token: String) derives Schema
    final private case class Named(name: String) derives Schema
    final private case class Empty() derives Schema
    final private case class Created(id: String) derives Schema
    final private case class Authorize(authorize: Boolean, guild_id: String, permissions: String) derives Schema
    final private case class Location(location: String) derives Schema
    final private case class ChannelRow(id: String, `type`: Int) derives Schema
    final private case class Reference(message_id: String, channel_id: String) derives Schema
    final private case class NewMessage(content: String, message_reference: Maybe[Reference]) derives Schema
    final private case class Edit(content: String) derives Schema
    final private case class Recipient(recipient_id: String) derives Schema
    final private case class OptionValue(name: String, `type`: Int, value: String) derives Schema
    final private case class InteractionData(
        component_type: Maybe[Int],
        custom_id: Maybe[String],
        id: Maybe[String],
        name: Maybe[String],
        `type`: Maybe[Int],
        options: Chunk[OptionValue]
    ) derives Schema
    final private case class Interaction(
        `type`: Int,
        application_id: String,
        guild_id: String,
        channel_id: String,
        message_id: Maybe[String],
        data: InteractionData
    ) derives Schema

    final private case class General(serverName: String) derives Schema
    final private case class Endpoint(endpointPublic: String, endpointPrivate: Maybe[String]) derives Schema
    final private case class Required(required: Boolean) derives Schema
    final private case class RegisterSettings(requireCaptcha: Boolean, email: Required, dateOfBirth: Required) derives Schema
    final private case class SpacebarConfig(general: General, gateway: Endpoint, api: Endpoint, cdn: Endpoint, register: RegisterSettings)
        derives Schema

end DiscordLiveServer
