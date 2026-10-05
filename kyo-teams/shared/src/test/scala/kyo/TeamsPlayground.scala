package kyo

/** Microsoft 365 Agents Playground (formerly the Teams App Test Tool) in a container for one leaf: Microsoft's local emulation of the Bot
  * Connector, served at `/_connector`. It is removed when the leaf's `Scope` closes.
  *
  * Microsoft publishes it as an npm package, not an image, so the image is built from `shared/src/test/playground/Containerfile`: the Node
  * image pinned by digest with the package installed at a pinned version. A CI run that selects this module builds it before the tests
  * (`scripts/fixture-images.sh`), so a leaf never reaches the npm registry; where it was not built, [[init]] fails with the command that builds it rather than pulling.
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

    /** The tag `scripts/fixture-images.sh` builds; its version is the package version the Containerfile installs. */
    val Image: ContainerImage = ContainerImage("localhost/kyo-teams-playground:0.2.28")

    val Host: String = "127.0.0.1"

    val ConversationText: String = "kyo-teams-live"

    private val Port = 56150

    private val Context = "kyo-teams/shared/src/test/playground"

    def init(using Frame): TeamsPlayground < (Async & Scope & Abort[ContainerException]) =
        built.andThen(Container.init(containerConfig).map(container => container.mappedPort(Port).map(TeamsPlayground(container, _))))

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

    private def containerConfig(using Frame): Container.Config =
        Container.Config.default
            .copy(image = Image)
            // Without it the Playground opens a browser on its UI at start.
            .env("TEAMSAPPTESTER_BROWSER", "none")
            .port(Port, 0)
            .requireService(true)
            // The bot endpoint is never called: the leaves drive the connector, so the Playground's wait for it only logs.
            .command(
                "agentsplayground",
                "--app-endpoint",
                "http://127.0.0.1:3978/api/messages",
                "--channel-id",
                "msteams",
                "--conversation-id",
                ConversationText,
                "--disable-telemetry",
                "--port",
                Port.toString
            )
            .healthCheck(ContainerPredef.readinessLoop(Chunk(
                "node",
                "-e",
                s"fetch('http://127.0.0.1:$Port/_debug/ping').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))"
            )))

end TeamsPlayground
