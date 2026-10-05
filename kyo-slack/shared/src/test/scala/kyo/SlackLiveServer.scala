package kyo

import kyo.internal.slack.TransportTest

/** A slack-simulator container standing in for Slack for one leaf, removed when the leaf's `Scope` closes with the directory its app
  * list was staged in.
  *
  * slack-simulator (github.com/ClydeDz/slack-simulator) is GPL-3.0. The suite only runs it: the image is built locally from the source
  * at `Commit`, fetched as GitHub's tarball and checked against `TarballSha256`, on a base pinned by digest, and nothing of it is
  * distributed or linked. The build changes one line: `apps.connections.open` answers a `wss` Socket Mode url instead of `ws`, since
  * the module refuses any other scheme before it connects. The suite then connects over `TransportTest.plainLocal`, which speaks `ws`
  * whatever the url says, so the container target exercises the Socket Mode protocol and not its TLS, which the real target covers.
  *
  * One app (`AppId`) with the bot user `BotUserId` and the suite's slash command, in a workspace whose channel `Channel` holds the
  * person `PersonId`. The emulator builds every url it hands out (`auth.test`'s, the Socket Mode url, each `response_url`) from
  * `SIMULATOR_BASE_URL`, so the host port is chosen before the container starts and published as itself.
  */
final case class SlackLiveServer(container: Container, port: Int, appToken: String, botToken: String):

    import SlackLiveServer.*

    private def base: String = s"http://$Host:$port"

    def credentials(using Frame): SlackLiveTest.Credentials =
        import SlackLiterals.*
        SlackLiveTest.Credentials(
            configOf(appLevelOf(appToken), botOf(botToken), baseUrl = urlOf(s"$base/api")),
            Channel,
            TransportTest.plainLocal
        )
    end credentials

    /** The person runs `command` with `text` in the channel. The emulator answers once the bot acknowledges the envelope. */
    def runs(command: String, text: String)(using Frame): Unit < (Async & Abort[HttpException]) =
        control("slash_command", SlashBody(Channel.value, command, text))

    /** The person presses the button `actionId` of block `blockId`, valued `value`, in the message at `ts`. */
    def presses(ts: SlackTs, blockId: String, actionId: String, value: String)(using Frame): Unit < (Async & Abort[HttpException]) =
        control("block_action", PressBody(Channel.value, ts.value, blockId, actionId, value))

    /** The person submits the open modal `viewId`. */
    def submits(viewId: SlackId.ViewId)(using Frame): Unit < (Async & Abort[HttpException]) =
        control("view_submit", SubmitBody(viewId.value, Map.empty))

    /** The tail of the container's log, for a leaf that failed. */
    def postMortem(using Frame): String < Async = ContainerPredef.postMortem(container)

    // The control API reads a body only as application/json, and answers a refused one with an error status, which fails the call.
    private def control[B: Schema](path: String, body: B)(using Frame): Unit < (Async & Abort[HttpException]) =
        HttpClient.postJson[SlackLiveTest.Ok](s"$base/_control/$path", body).unit

end SlackLiveServer

object SlackLiveServer:

    val Commit: String = "87373b8855e68307737ef577f0875c0118940523"

    val TarballSha256: String = "57bf9176cd04ea399ba7242096ce7dced1f827644abb9be90933ec72502ff325"

    val Base: String = "docker.io/library/node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c"

    /** The local tag of the built image. A change to the build below changes its last component. */
    val Image: ContainerImage = ContainerImage("localhost/kyo-slack-simulator", s"${Commit.take(10)}-1")

    val Host: String = "127.0.0.1"

    val AppId: String = "A0KYOLIVE"

    val BotUserId: String = "U0KYOBOT"

    val PersonId: String = "U001"

    val Channel: SlackId.ChannelId = SlackId.ChannelId("C001")

    private val AppsJson = "/app/config/apps.json"

    def init(using Frame): SlackLiveServer < (Async & Scope & Abort[ContainerException | FileSystemException | HttpException]) =
        for
            _                 <- ensureImage
            appToken          <- Random.nextStringAlphanumeric(24).map(s => s"xapp-1-$AppId-$s")
            botToken          <- Random.nextStringAlphanumeric(24).map(s => s"xoxb-1-$s")
            directory         <- Path.run(Path.tempDir("kyo-slack-server"))
            _                 <- Path.run((directory / "apps.json").write(appsJson(appToken, botToken)))
            (container, port) <- started(PortAttempts)(port => Container.init(containerConfig(directory, port)).map((_, port)))
        yield SlackLiveServer(container, port, appToken, botToken)

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

    /** Builds the image unless the daemon holds it. The suite runs leaves one at a time, so no two builds race. */
    private def ensureImage(using Frame): Unit < (Async & Scope & Abort[ContainerException | FileSystemException]) =
        Abort.run[ContainerException](ContainerImage.inspect(Image)).map {
            case Result.Success(_)                                 => Kyo.unit
            case Result.Failure(_: ContainerImageMissingException) =>
                Path.run(Path.tempDir("kyo-slack-build")).map { directory =>
                    Path.run((directory / "Containerfile").write(containerfile)).andThen {
                        ContainerImage.buildFromPath(directory, "Containerfile", tags = Chunk(Image.reference)).discard
                    }
                }
            case Result.Failure(other) => Abort.fail(other)
            case Result.Panic(e)       => Abort.panic(e)
        }

    // The emulator builds every url it hands out from the port it is published on, so the port is chosen before the container starts.
    private def freePort(using Frame): Int < (Async & Abort[HttpException]) =
        Scope.run(HttpServer.init(0, Host)().map(_.port))

    private def containerConfig(directory: Path, port: Int)(using Frame): Container.Config =
        Container.Config.default
            .copy(image = Image)
            .port(4500, port)
            .requireService(true)
            .env("SIMULATOR_BASE_URL", s"http://$Host:$port")
            .bind(directory / "apps.json", Path(AppsJson), readOnly = true)
            .healthCheck(Container.HealthCheck.init(ready(port)))

    private def ready(port: Int)(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.run[HttpException](HttpClient.getText(s"http://$Host:$port/_control/workspace")).map {
            case Result.Success(_) => Kyo.unit
            case other             => Abort.fail(ContainerHealthCheckException(container.id, s"slack-simulator: $other", attempts = 1))
        }

    private val containerfile: String =
        s"""FROM $Base
           |WORKDIR /app
           |RUN node -e "fetch('https://codeload.github.com/ClydeDz/slack-simulator/tar.gz/$Commit').then(r => r.arrayBuffer()).then(b => require('fs').writeFileSync('/tmp/src.tgz', Buffer.from(b)))" \\
           | && echo "$TarballSha256  /tmp/src.tgz" | sha256sum -c - \\
           | && tar xzf /tmp/src.tgz --strip-components=1 -C /app \\
           | && rm /tmp/src.tgz
           |RUN sed -i 's#url: `ws://#url: `wss://#' src/server/routes/slackApi.ts && grep -q 'url: `wss://' src/server/routes/slackApi.ts
           |RUN yarn install --frozen-lockfile && yarn build:server
           |EXPOSE 4500
           |CMD ["node", "dist/server/server/index.js"]
           |""".stripMargin

    private def appsJson(appToken: String, botToken: String)(using Frame): String =
        Json.encode(Apps(Chunk(App(
            id = AppId,
            name = "Kyo Live",
            botUserId = BotUserId,
            botUserName = "kyo-live",
            description = "kyo-slack live suite",
            socketModeEnabled = true,
            botToken = botToken,
            appToken = appToken,
            signingSecret = "kyo-live-signing-secret",
            subscribedEvents = Chunk("message", "app_mention"),
            slashCommands = Chunk(CommandSpec(SlackLiveTest.SlashCommand, "kyo-slack live suite", ""))
        ))))

    // The JSON the emulator's control API and app list take.

    final private case class SlashBody(channelId: String, command: String, text: String) derives Schema
    final private case class PressBody(channelId: String, messageTs: String, blockId: String, actionId: String, value: String)
        derives Schema
    final private case class SubmitBody(viewId: String, values: Map[String, String]) derives Schema

    final private case class Apps(apps: Chunk[App]) derives Schema
    final private case class App(
        id: String,
        name: String,
        botUserId: String,
        botUserName: String,
        description: String,
        socketModeEnabled: Boolean,
        botToken: String,
        appToken: String,
        signingSecret: String,
        subscribedEvents: Chunk[String],
        slashCommands: Chunk[CommandSpec]
    ) derives Schema
    final private case class CommandSpec(command: String, description: String, usage: String) derives Schema

end SlackLiveServer
