package kyo

import kyo.internal.TestContainers

/** A whaloc container: an emulator of the WhatsApp Cloud API (Graph v25.0) that answers the module's requests with Meta's envelopes
  * and error codes, stores uploaded media, and POSTs signed webhooks for inbound messages and the status ladder. One per webhook
  * target per test process, shared by every leaf. Its control plane (`/api/...`) plays the part of the WhatsApp user and of Meta's
  * console: it injects inbound messages, approves templates, injects failures and runs the verification handshake.
  *
  * Two settings are read once at boot, which shapes how one starts. Media URLs are built from `WHALOC_PUBLIC_URL`, so the host port is
  * chosen before the container starts and published as itself. The webhook URL is fixed too, so the server that receives webhooks
  * binds first and its port is passed. The container reaches that server through the name its daemon gives the host.
  *
  * Tokens are strict: only [[token]] is accepted, so a leaf can see the unauthorized answer.
  *
  * A CI run that selects this module pulls the image at its pinned digest before the tests (`scripts/fixture-images.sh`), so a leaf
  * never reaches a registry; where it was not pulled, [[WhatsAppLiveServer.init]] fails with the command that pulls it rather than pulling.
  */
final case class WhatsAppLiveServer(
    container: Container,
    port: Int,
    token: String,
    phoneNumberId: WhatsAppId.PhoneNumberId,
    wabaId: String
):

    import WhatsAppLiveServer.*

    def baseUrl(using Frame): HttpUrl = HttpUrl.parse(s"http://$Host:$port").getOrThrow

    def config(retry: Maybe[Schedule] = Absent, token: String = token)(using Frame): WhatsAppConfig =
        WhatsAppConfig.init(WhatsAppToken.init(token).getOrThrow, phoneNumberId, baseUrl = baseUrl, retry = retry).getOrThrow

    /** A text message from `from` to the business number, as the user would send it. Answers its wamid. */
    def inbound(from: WhatsAppId.WaId, text: String)(using Frame): WhatsAppId.MessageId < (Async & Abort[HttpException]) =
        injected(InboundText(phoneNumberId.value, from.value, "text", Body(text)))

    /** `from` replies to `message` with `text`, quoting it. */
    def replies(from: WhatsAppId.WaId, message: WhatsAppId.MessageId, text: String)(using
        Frame
    ): WhatsAppId.MessageId < (Async & Abort[HttpException]) =
        injected(InboundReply(phoneNumberId.value, from.value, message.value, "text", Body(text)))

    /** `from` presses the reply button `id` titled `title` on `message`. */
    def presses(from: WhatsAppId.WaId, message: WhatsAppId.MessageId, id: String, title: String)(using
        Frame
    ): WhatsAppId.MessageId < (Async & Abort[HttpException]) =
        injected(InboundButton(
            phoneNumberId.value,
            from.value,
            message.value,
            "interactive",
            ButtonInteractive("button_reply", ButtonReply(id, title))
        ))

    /** `from` chooses the list row `id` on `message`. */
    def chooses(from: WhatsAppId.WaId, message: WhatsAppId.MessageId, id: String, title: String, description: String)(using
        Frame
    ): WhatsAppId.MessageId < (Async & Abort[HttpException]) =
        injected(InboundList(
            phoneNumberId.value,
            from.value,
            message.value,
            "interactive",
            ListInteractive("list_reply", ListReply(id, title, description))
        ))

    /** `from` reacts to `message` with `emoji`. */
    def reacts(from: WhatsAppId.WaId, message: WhatsAppId.MessageId, emoji: String)(using
        Frame
    ): WhatsAppId.MessageId < (Async & Abort[HttpException]) =
        injected(InboundReaction(phoneNumberId.value, from.value, "reaction", Reaction(message.value, emoji)))

    /** The recipient of `message`, which the business sent, reads it. */
    def reads(message: WhatsAppId.MessageId)(using Frame): Unit < (Async & Abort[HttpException]) =
        HttpClient.postText(control(s"messages/${message.value}/status"), """{"status":"read"}""", jsonHeaders).unit

    private def injected[B: Schema](body: B)(using Frame): WhatsAppId.MessageId < (Async & Abort[HttpException]) =
        HttpClient.postJson[InboundAnswer](control("inbound"), body).map(answer => WhatsAppId.MessageId(answer.data.id))

    /** Creates a body-only template through the Graph API and approves it, as Meta's review would. */
    def approvedTemplate(name: String, language: String, text: String)(using Frame): Unit < (Async & Abort[HttpException]) =
        val created = HttpClient.postText(
            HttpUrl.parse(s"http://$Host:$port/$Version/$wabaId/message_templates").getOrThrow,
            Json.encode(NewTemplate(name, language, "UTILITY", Chunk(TemplateComponent("BODY", text)))),
            headers = HttpHeaders.empty.add("Authorization", s"Bearer $token").add("Content-Type", "application/json")
        )
        created.andThen(HttpClient.getJson[Templates](control("templates"), query = HttpQueryParams.empty.add("name", name))).map {
            templates =>
                Kyo.foreachDiscard(templates.data.filter(_.name == name)) { template =>
                    HttpClient.postText(control(s"templates/${template.id}/approve"), "{}", jsonHeaders).unit
                }
        }
    end approvedTemplate

    /** Makes the next send answer 429 with code 130429 and `Retry-After`, Meta's throughput limit. Answers the rule's id. */
    def throttleNextSend(retryAfterSeconds: Int)(using Frame): String < (Async & Abort[HttpException]) =
        HttpClient.postJson[RuleAnswer](
            control("injection-rules"),
            NewRule("messages.send", Trigger("next", 1), "rate_limit_429", retryAfterSeconds)
        ).map(_.data.id)

    /** How many requests the rule `id` answered. */
    def ruleMatches(id: String)(using Frame): Int < (Async & Abort[HttpException]) =
        HttpClient.getJson[Rules](control("injection-rules")).map(_.data.filter(_.id == id).map(_.matches).sum)

    /** Runs Meta's verification handshake against the configured webhook URL and answers what it saw. */
    def handshake(using Frame): Handshake < (Async & Abort[HttpException]) =
        HttpClient.postText(control("webhook/handshake"), "{}", jsonHeaders).map(text => Json.decode[HandshakeAnswer](text).getOrThrow.data)

    /** The users the business number shows a typing indicator to. */
    def typing(using Frame): Chunk[WhatsAppId.WaId] < (Async & Abort[HttpException]) =
        HttpClient.getJson[TypingList](control("typing"), query = HttpQueryParams.empty.add("phoneNumberId", phoneNumberId.value))
            .map(_.data.map(t => WhatsAppId.WaId(t.contactWaId)))

    /** The tail of the container's log, for a leaf that failed. */
    def postMortem(using Frame): String < Async = ContainerPredef.postMortem(container)

    private def control(path: String)(using Frame): HttpUrl = HttpUrl.parse(s"http://$Host:$port/api/$path").getOrThrow

end WhatsAppLiveServer

object WhatsAppLiveServer:

    /** whaloc 0.1.0, pinned by the digest of its multi-arch index (linux/amd64 and linux/arm64), which `scripts/fixture-images.sh`
      * pulls; the two change together.
      */
    val Image: ContainerImage =
        ContainerImage("docker.io/dgadelha/whaloc@sha256:793780655dbe3be764de559625be0026a67978a3a9527e23d9d023f613c51314")

    val Host: String = "127.0.0.1"

    /** The Graph API version whaloc serves, the module's default. */
    val Version: String = "v25.0"

    /** The webhook signing secret and verify token every server is started with. */
    val AppSecret: String   = "kyo-whatsapp-live-app-secret"
    val VerifyToken: String = "kyo-whatsapp-live-verify-token"

    type Failure = ContainerException | HttpException

    private val servers = TestContainers.memo[WhatsAppLiveServer, Failure]

    /** The process's server posting webhooks to `webhook`'s port and path on the host when given, started on a free host port on first
      * use, with the `hello_world` template every Meta test number has created and approved once, when the server starts.
      */
    def init(webhook: Maybe[(Int, String)] = Absent)(using Frame): WhatsAppLiveServer < (Async & Abort[Failure]) =
        val key = webhook.fold("whaloc")((port, path) => s"whaloc:$port/$path")
        TestContainers.getOrInit(servers, key) {
            pulled.andThen(started(PortAttempts)(port => start(port, webhook))).map { server =>
                server.approvedTemplate("hello_world", "en_US", "Hello World").andThen(server)
            }
        }
    end init

    // kyo-pod pulls a missing image on its own, which would reach the registry from inside the leaf.
    private def pulled(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.run[ContainerException](ContainerImage.inspect(Image)).map {
            case Result.Success(_)                                 => Kyo.unit
            case Result.Failure(_: ContainerImageMissingException) =>
                Abort.panic(new IllegalStateException(s"${Image.reference} is not pulled; run: podman pull ${Image.reference}"))
            case Result.Failure(other) => Abort.fail(other)
            case Result.Panic(e)       => Abort.panic(e)
        }

    /** How many host ports a server tries before its start fails with the conflict. */
    private val PortAttempts = 5

    // A port free on the host can still be held inside the container daemon's VM (podman publishes it from there), which the host's
    // check cannot see; the daemon then refuses it as allocated, and a fresh port is the answer.
    private def started[A](attempts: Int)(start: Int => A < (Async & Abort[ContainerException | HttpException]))(using
        Frame
    ): A < (Async & Abort[ContainerException | HttpException]) =
        freePort.map { port =>
            Abort.run[ContainerPortConflictException](start(port)).map {
                case Result.Success(value)             => value
                case Result.Failure(_) if attempts > 1 => started(attempts - 1)(start)
                case Result.Failure(conflict)          => Abort.fail(conflict)
                case Result.Panic(e)                   => Abort.panic(e)
            }
        }

    // Media URLs name the port the server is published on, so the port is chosen before the container starts.
    private def freePort(using Frame): Int < (Async & Abort[HttpException]) =
        HttpServer.initUnscoped(0, Host)(Probe).map(server => server.closeNow.andThen(server.port))

    private def start(port: Int, webhook: Maybe[(Int, String)])(using
        Frame
    ): WhatsAppLiveServer < (Async & Abort[ContainerException | HttpException]) =
        Random.nextStringAlphanumeric(24).map { token =>
            TestContainers.initShared(containerConfig(port, token, webhook), "whaloc").map { container =>
                HttpClient.getJson[State](HttpUrl.parse(s"http://$Host:$port/api/state").getOrThrow).map { state =>
                    val waba = state.wabas.head
                    WhatsAppLiveServer(container, port, token, WhatsAppId.PhoneNumberId(waba.phoneNumbers.head.id), waba.id)
                }
            }
        }

    private def containerConfig(port: Int, token: String, webhook: Maybe[(Int, String)])(using Frame): Container.Config =
        val env = Map(
            "WHALOC_PUBLIC_URL"           -> s"http://$Host:$port",
            "WHALOC_TOKENS"               -> token,
            "WHALOC_APP_SECRET"           -> AppSecret,
            "WHALOC_WEBHOOK_VERIFY_TOKEN" -> VerifyToken
        )
        val config = Container.Config.default
            .copy(image = Image)
            .envAll(Dict.from(env))
            .port(8080, port)
            .requireService(true)
            .healthCheck(ContainerPredef.readinessLoop(Chunk("wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/health")))
        webhook.fold(config)((hostPort, path) => config.command(Command("sh", "-c", startWithWebhook(hostPort, path))))
    end containerConfig

    /** The container's start command when it posts webhooks to the host.
      *
      * The host's name differs by daemon (`host.containers.internal` under podman, `host.docker.internal` under Docker Desktop, which
      * kyo-pod prefers on macOS), and whaloc reads its webhook URL once at boot and ignores Meta's per-WABA `override_callback_uri`. So
      * the name is resolved inside the container before whaloc starts, and a container that resolves neither exits at once rather than
      * leaving a leaf to wait out its timeout for a webhook that cannot arrive. The `exec` line is the image's own `CMD`, fixed by the
      * pinned digest.
      */
    private def startWithWebhook(hostPort: Int, path: String): String =
        s"""for host in host.containers.internal host.docker.internal; do
           |  if getent hosts "$$host" >/dev/null; then
           |    export WHALOC_WEBHOOK_URL="http://$$host:$hostPort/$path"
           |    exec node --disable-warning=ExperimentalWarning packages/server/dist/main.js
           |  fi
           |done
           |echo "neither host.containers.internal nor host.docker.internal resolves: whaloc cannot reach the host's webhook" >&2
           |exit 1
           |""".stripMargin

    private val jsonHeaders = HttpHeaders.empty.add("Content-Type", "application/json")

    private def Probe(using Frame) = HttpRoute.getRaw("").response(_.bodyText).handler(_ => HttpResponse.ok(""))

    final private case class PhoneNumber(id: String) derives Schema
    final private case class Waba(id: String, phoneNumbers: Chunk[PhoneNumber]) derives Schema
    final private case class State(wabas: Chunk[Waba]) derives Schema

    final private case class Body(body: String) derives Schema
    final private case class InboundText(phoneNumberId: String, from: String, `type`: String, text: Body) derives Schema
    final private case class InboundReply(phoneNumberId: String, from: String, replyTo: String, `type`: String, text: Body)
        derives Schema
    final private case class ButtonReply(id: String, title: String) derives Schema
    final private case class ButtonInteractive(`type`: String, button_reply: ButtonReply) derives Schema
    final private case class InboundButton(
        phoneNumberId: String,
        from: String,
        replyTo: String,
        `type`: String,
        interactive: ButtonInteractive
    ) derives Schema
    final private case class ListReply(id: String, title: String, description: String) derives Schema
    final private case class ListInteractive(`type`: String, list_reply: ListReply) derives Schema
    final private case class InboundList(phoneNumberId: String, from: String, replyTo: String, `type`: String, interactive: ListInteractive)
        derives Schema
    final private case class Reaction(message_id: String, emoji: String) derives Schema
    final private case class InboundReaction(phoneNumberId: String, from: String, `type`: String, reaction: Reaction) derives Schema
    final private case class InboundMessage(id: String) derives Schema
    final private case class InboundAnswer(data: InboundMessage) derives Schema

    final private case class TemplateComponent(`type`: String, text: String) derives Schema
    final private case class NewTemplate(name: String, language: String, category: String, components: Chunk[TemplateComponent])
        derives Schema
    final private case class TemplateRow(id: String, name: String) derives Schema
    final private case class Templates(data: Chunk[TemplateRow]) derives Schema

    final private case class Trigger(kind: String, count: Int) derives Schema
    final private case class NewRule(target: String, trigger: Trigger, preset: String, retryAfterSeconds: Int) derives Schema
    final private case class Rule(id: String, matches: Int) derives Schema
    final private case class RuleAnswer(data: Rule) derives Schema
    final private case class Rules(data: Chunk[Rule]) derives Schema

    /** What whaloc saw when it ran the handshake: whether the echo matched the challenge, and the endpoint's status and echo. */
    final case class Handshake(ok: Boolean, status: Maybe[Int], challenge: String, echo: Maybe[String]) derives Schema
    final private case class HandshakeAnswer(data: Handshake) derives Schema

    final private case class Typing(phoneNumberId: String, contactWaId: String) derives Schema
    final private case class TypingList(data: Chunk[Typing]) derives Schema

end WhatsAppLiveServer
