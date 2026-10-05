package kyo

import kyo.net.TlsTestCertShared

/** A Spacebar container standing in for Discord for one leaf, removed when the leaf's `Scope` closes.
  *
  * Spacebar (github.com/spacebarchat/server) is AGPL-3.0. The suite only runs it: the image is built locally from the source at
  * `Commit`, fetched as GitHub's tarball and checked against `TarballSha256`, on bases pinned by digest, and nothing of it is
  * distributed or linked. Spacebar needs Postgres: its entities declare `jsonb` columns and it excludes the SQLite drivers, so the
  * runtime stage is the Postgres image with the built server and the build stage's `node` copied in. The build changes three lines
  * where Spacebar's answer departs from Discord's documented one, each checked by a `grep` so a moved line fails the build: a message
  * with no components serializes them as `[]`, not `null`; a message's `member.roles` are role ids, not role objects; and an
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

    val Commit: String = "0eb6f04f6d8055dba0194d10c47c33e074a8a281"

    val TarballSha256: String = "72a195a37f60720550c51072e8b040284364e1b7563c3f3f730f757134a49428"

    /** The build stage: the Node release Spacebar's own packaging builds with. */
    val BuildBase: String = "docker.io/library/node:26-bookworm@sha256:2aaae6d91f99fee84cfc92da9b52c22a185752d247746052bbc3f961e44478c6"

    /** The runtime stage, on the same Debian release as `BuildBase`, so the copied `node` finds the libraries it was linked against. */
    val RuntimeBase: String =
        "docker.io/library/postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652"

    /** The local tag of the built image. A change to the build below changes its last component. */
    val Image: ContainerImage = ContainerImage("localhost/kyo-discord-spacebar", s"${Commit.take(10)}-4")

    val Host: String = "127.0.0.1"

    /** The client trusts the terminator's self-signed certificate; `TlsTestCertShared` is the one it serves. */
    val Tls: HttpTlsConfig = HttpTlsConfig(trustAll = true)

    def init(using Frame): DiscordLiveServer < (Async & Scope & Abort[ContainerException | FileSystemException | HttpException]) =
        for
            _                 <- ensureImage
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

    /** Builds the image unless the daemon holds it. The suite runs leaves one at a time, so no two builds race. */
    private def ensureImage(using Frame): Unit < (Async & Scope & Abort[ContainerException | FileSystemException]) =
        Abort.run[ContainerException](ContainerImage.inspect(Image)).map {
            case Result.Success(_)                                 => Kyo.unit
            case Result.Failure(_: ContainerImageMissingException) =>
                Path.run(Path.tempDir("kyo-discord-build")).map { directory =>
                    Path.run {
                        (directory / "Containerfile").write(containerfile)
                            .andThen((directory / "start.sh").write(startScript))
                            .andThen((directory / "tls-proxy.js").write(tlsProxy))
                    }.andThen {
                        ContainerImage.buildFromPath(directory, "Containerfile", tags = Chunk(Image.reference)).discard
                    }
                }
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

    private val containerfile: String =
        s"""FROM $BuildBase AS build
           |WORKDIR /app
           |RUN node -e "fetch('https://codeload.github.com/spacebarchat/server/tar.gz/$Commit').then(r => r.arrayBuffer()).then(b => require('fs').writeFileSync('/tmp/src.tgz', Buffer.from(b)))" \\
           | && echo "$TarballSha256  /tmp/src.tgz" | sha256sum -c - \\
           | && tar xzf /tmp/src.tgz --strip-components=1 -C /app \\
           | && rm /tmp/src.tgz
           |RUN sed -i 's#^\\( *\\): this.components,$$#\\1: (this.components ?? []),#' src/database/entities/Message.ts \\
           | && grep -q ': (this.components ?? \\[\\]),' src/database/entities/Message.ts
           |RUN sed -i 's#^\\( *\\)member_id: undefined,$$#&\\n\\1member: (this.member ? { ...this.member, roles: this.member.roles?.map((role) => (typeof role === \"string\" ? role : role.id)) } : undefined) as never,#' src/database/entities/Message.ts \\
           | && grep -q 'typeof role === "string" ? role : role.id' src/database/entities/Message.ts
           |RUN sed -i 's#^\\( *\\)message.embeds = body.data.embeds || \\[\\];$$#&\\n\\1if (body.data.content !== undefined) message.content = body.data.content;#' 'src/api/routes/interactions/#interaction_id/#interaction_token/callback.ts' \\
           | && grep -q 'message.content = body.data.content;' 'src/api/routes/interactions/#interaction_id/#interaction_token/callback.ts'
           |RUN HUSKY=0 npm ci --no-audit --no-fund && npm run build
           |
           |FROM $RuntimeBase
           |COPY --from=build /usr/local/bin/node /usr/local/bin/node
           |COPY --from=build /usr/lib/*-linux-gnu/libatomic.so.1* /usr/local/lib/
           |RUN ldconfig && node --version
           |COPY --from=build /app /app
           |COPY start.sh tls-proxy.js /opt/
           |WORKDIR /app
           |EXPOSE 8443
           |ENTRYPOINT ["sh", "/opt/start.sh"]
           |""".stripMargin

    private val startScript: String =
        """set -e
          |export PGDATA=/tmp/pgdata
          |mkdir -p "$PGDATA" && chown postgres "$PGDATA"
          |gosu postgres initdb -D "$PGDATA" --auth=trust -U spacebar >/dev/null
          |gosu postgres pg_ctl -D "$PGDATA" -o "-c listen_addresses=127.0.0.1 -c fsync=off" -w start >/dev/null
          |gosu postgres createdb -h 127.0.0.1 -U spacebar spacebar
          |printf '%s' "$SPACEBAR_CONFIG_JSON" > /tmp/config.json
          |printf '%s' "$TLS_CERT" > /tmp/cert.pem
          |printf '%s' "$TLS_KEY" > /tmp/key.pem
          |node /opt/tls-proxy.js &
          |export DATABASE=postgres://spacebar@127.0.0.1:5432/spacebar CONFIG_PATH=/tmp/config.json PORT=3001
          |exec node --enable-source-maps dist/bundle/start.js
          |""".stripMargin

    private val tlsProxy: String =
        """const tls = require("node:tls");
          |const net = require("node:net");
          |const fs = require("node:fs");
          |tls.createServer({ key: fs.readFileSync("/tmp/key.pem"), cert: fs.readFileSync("/tmp/cert.pem") }, (client) => {
          |    const upstream = net.connect(3001, "127.0.0.1");
          |    client.pipe(upstream);
          |    upstream.pipe(client);
          |    client.on("error", () => upstream.destroy());
          |    upstream.on("error", () => client.destroy());
          |}).listen(8443, "0.0.0.0");
          |""".stripMargin

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
