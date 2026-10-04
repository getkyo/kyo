package kyo

/** Microsoft 365 Agents Playground (formerly the Teams App Test Tool) in a container for one leaf: Microsoft's local emulation of the Bot
  * Connector, served at `/_connector`. It is removed when the leaf's `Scope` closes.
  *
  * Microsoft publishes it as an npm package, not an image, so the container is the Node image pinned by digest, installing the package at
  * a pinned version when it starts. The install is retried, since a fresh CI runner has no cache and reaches the npm registry every run.
  *
  * The Playground holds one personal chat, whose id [[ConversationText]] fixes, and answers send, typing, edit, reply, the members page
  * and a member as Teams does. It ignores the Authorization header, so the token the client sends is [[TeamsLocal]]'s, the only part of
  * the identity platform a container leaf needs. It answers every DELETE with 501 `DeleteActivityAPINotImplemented`, and it serves no
  * OpenID metadata, key set or signed delivery: those leaves run against Teams only.
  */
final case class TeamsPlayground(container: Container, port: Int):

    /** The origin the client's token may be sent to: the Playground's published port. */
    def origin: HttpUrl = HttpUrl(Present("http"), TeamsPlayground.Host, port, "/", Absent)

    def serviceUrl(using Frame): Teams.ServiceUrl =
        Teams.ServiceUrl.init(s"http://${TeamsPlayground.Host}:$port/_connector/").getOrThrow

    /** A reference to the Playground's personal chat. */
    def reference(using Frame): Teams.ConversationReference =
        Teams.ConversationReference(
            serviceUrl,
            Teams.ConversationAccount(Teams.ConversationId.init(TeamsPlayground.ConversationText).getOrThrow)
        )

    /** The tail of the container's log, for a leaf that failed. */
    def postMortem(using Frame): String < Async = ContainerPredef.postMortem(container)

end TeamsPlayground

object TeamsPlayground:

    /** node:22, the multi-architecture index, so linux/amd64 and linux/arm64 runners resolve the same pinned image. */
    val Image: ContainerImage =
        ContainerImage("docker.io/library/node@sha256:363e1587494626837fa7f9a23bdb453d13b0ff3c67c705c2805cfc69c2d2fad7")

    val Package: String = "@microsoft/m365agentsplayground@0.2.28"

    val Host: String = "127.0.0.1"

    val ConversationText: String = "kyo-teams-live"

    private val Port = 56150

    def init(using Frame): TeamsPlayground < (Async & Scope & Abort[ContainerException]) =
        Container.init(containerConfig).map(container => container.mappedPort(Port).map(TeamsPlayground(container, _)))

    private def containerConfig(using Frame): Container.Config =
        Container.Config.default
            .copy(image = Image)
            // Without it the Playground opens a browser on its UI at start.
            .env("TEAMSAPPTESTER_BROWSER", "none")
            .port(Port, 0)
            .requireService(true)
            .command(
                "sh",
                "-c",
                Chunk(
                    s"for attempt in 1 2 3; do npm install --global --no-audit --no-fund $Package && break; sleep 10; done",
                    // The bot endpoint is never called: the leaves drive the connector, so the Playground's wait for it only logs.
                    s"exec agentsplayground --app-endpoint http://127.0.0.1:3978/api/messages --channel-id msteams " +
                        s"--conversation-id $ConversationText --disable-telemetry --port $Port"
                ).mkString(" && ")
            )
            .healthCheck(ContainerPredef.readinessLoop(Chunk(
                "node",
                "-e",
                s"fetch('http://127.0.0.1:$Port/_debug/ping').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))"
            )))

end TeamsPlayground
