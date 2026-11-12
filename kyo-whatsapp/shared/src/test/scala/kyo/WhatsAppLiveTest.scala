package kyo

import kyo.crypto.Sha256
import kyo.internal.Platform
import kyo.internal.TestContainers

/** The module against the Cloud API: Meta's when `KYO_WHATSAPP_TOKEN` is set, a [[WhatsAppLiveServer]] (whaloc, an emulator of Graph
  * v25.0, MIT-licensed and run, never distributed) otherwise.
  *
  * On Meta every leaf needs `KYO_WHATSAPP_TOKEN`, the app's access token, `KYO_WHATSAPP_PHONE_NUMBER_ID`, the test number's Phone Number
  * ID, and `KYO_WHATSAPP_RECIPIENT`, the wa_id (E.164 digits, no `+`) of a number added as a recipient on the app's API Setup page. They
  * are read through `kyo.System.env`, the names `demo.WhatsAppDemo` reads, and a leaf is cancelled with a message naming the first one
  * missing. On the container, the leaves share a server, one for the leaves that receive webhooks and one for the rest, which approves
  * the `hello_world` template every Meta test number has, and talks to one person, [[WhatsAppLiveTest.Person]].
  *
  * Only a template reaches a person outside the 24-hour window that their own message to the test number opens. Meta accepts every other
  * send outside it and reports the message failed later, so the send leaves pass either way; the person sees them only inside the window.
  *
  * The leaves after the `Interactive` separator send the person an instruction and wait for the webhook notification their action
  * produces. On Meta a person acts, and those leaves run only when `KYO_WHATSAPP_INTERACTIVE` is set: each serves `WhatsApp.Webhook` on
  * `localhost` at `KYO_WHATSAPP_WEBHOOK_PORT`, checking signatures with `KYO_WHATSAPP_APP_SECRET` and answering the handshake with
  * `KYO_WHATSAPP_VERIFY_TOKEN`, behind a tunnel the person runs from the app's callback URL. On the container the suite injects what
  * the person does through whaloc's control plane, and whaloc posts the signed webhook.
  *
  * A leaf whose behaviour the emulator does not reproduce asserts Meta's, and is cancelled on the container with the reason. A leaf that
  * needs what only the emulator can do (an injected failure, the handshake run on demand, the typing indicator observed) runs on a
  * container whatever the credentials.
  *
  * The Cloud API deletes no sent message, so what the suite sends stays in the chat; the media it uploads it deletes.
  */
class WhatsAppLiveTest extends BaseWhatsAppTest:

    import WhatsAppLiveTest.*

    // The real target shares one number, one recipient and one webhook port, and container leaves contend on one daemon, so leaves run
    // one at a time across every suite of the process.
    override def config = super.config.sequential.globallySequential(true)

    // deviation: a leaf waits on the Cloud API over the network, a container starting, or a person acting, which no virtual clock can
    // stand in for. This per-leaf bound is the only real-clock limit in the module's tests; no assertion reads elapsed time.
    override def timeout: Duration = 10.minutes

    // The container daemon's HTTP client is scoped to the leaf, so its idle connections do not outlive it.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(client => HttpClient.let(client)(body)))

    private enum Target derives CanEqual:
        case Real(real: WhatsAppConfig, to: WhatsAppId.WaId)
        case Emulator(server: WhatsAppLiveServer)

        def config(using Frame): WhatsAppConfig =
            this match
                case Real(real, _)    => real
                case Emulator(server) => server.config()

        def recipient: WhatsAppId.WaId =
            this match
                case Real(_, to) => to
                case Emulator(_) => Person
    end Target

    /** What starting the container target can fail with. */
    private type Setup = ContainerException | HttpException

    private def required(name: String, why: String)(using Frame): String < Sync =
        System.env[String](name).map {
            case Absent        => cancel(s"$name must be set: $why")
            case Present(text) => text
        }

    private def real(using Frame): Maybe[Target] < Sync =
        System.env[String]("KYO_WHATSAPP_TOKEN").map {
            case Absent         => Maybe.empty[Target]
            case Present(token) =>
                for
                    phone     <- required("KYO_WHATSAPP_PHONE_NUMBER_ID", "the live WhatsApp suite sends from the app's test number")
                    recipient <- required("KYO_WHATSAPP_RECIPIENT", "the live WhatsApp suite sends to a registered recipient")
                yield Maybe(Target.Real(configOf(token, WhatsAppId.PhoneNumberId(phone)), WhatsAppId.WaId(recipient)))
        }

    /** The Cloud API a leaf runs against. `realOnly` is why the emulator cannot stand in for this leaf, which is then cancelled before a
      * container starts.
      */
    private def target(realOnly: Maybe[String])(using Frame): Target < (Async & Scope & Abort[Setup]) =
        real.map {
            case Present(t) => t
            case Absent     =>
                realOnly match
                    case Present(reason) => cancel(s"whaloc differs from the Cloud API: $reason; set KYO_WHATSAPP_TOKEN to run it")
                    case Absent          => emulator(Absent).map(Target.Emulator(_))
        }

    /** The container posting webhooks to the host's `webhook` port and path when given. A leaf that ends in error prints the container's
      * log.
      */
    private def emulator(webhook: Maybe[(Int, String)])(using Frame): WhatsAppLiveServer < (Async & Scope & Abort[Setup]) =
        if Platform.isWindows then cancel("whaloc does not run on Windows: its container daemon cannot serve the Linux image")
        else
            WhatsAppLiveServer.init(webhook).map { server =>
                Scope.ensure {
                    case Present(error) =>
                        server.postMortem.map(log => Console.printLineErr(s"the leaf ended with $error; whaloc log:\n$log"))
                    case Absent => Kyo.unit
                }.andThen(server)
            }

    /** Runs `v` with a client on the target. */
    private def withTarget[A, S](realOnly: Maybe[String] = Absent)(v: Target => A < (S & Env[WhatsApp]))(using
        Frame
    ): A < (S & Async & Scope & Abort[Setup]) =
        target(realOnly).map(t => WhatsApp.run(t.config)(v(t)))

    /** Runs `v` on a container whatever the credentials: what it asserts only the emulator can arrange or show. */
    private def onEmulator[A, S](v: WhatsAppLiveServer => A < (S & Env[WhatsApp]))(using
        Frame
    ): A < (S & Async & Scope & Abort[Setup]) =
        emulator(Absent).map(server => WhatsApp.run(server.config())(v(server)))

    /** The send answered one message, for the recipient as the suite named it. */
    private def sentTo(t: Target)(result: WhatsAppSendResult): Boolean =
        result.contacts.map(_.input) == Chunk(Present(t.recipient.value)) && result.messages.size == 1 &&
            result.messageId.value.startsWith("wamid.")

    // --- Sending ---

    "the hello_world template every test number has is sent" in {
        withTarget() { t =>
            WhatsApp.sendTemplate(t.recipient, WhatsAppTemplate("hello_world", "en_US")).map(result =>
                assert(sentTo(t)(result), s"got: $result")
            )
        }
    }

    "a template that does not exist is WhatsAppTemplateNotFoundException, code 132001" in {
        withTarget() { t =>
            Abort.run[WhatsAppSendTemplateFailure](WhatsApp.sendTemplate(t.recipient, WhatsAppTemplate("kyo_live_absent", "en_US")))
                .map { result =>
                    val failure = failureOf[WhatsAppTemplateNotFoundException](result)
                    assert((failure.method, failure.code) == ("sendTemplate", 132001))
                }
        }
    }

    "a text is sent" in {
        withTarget() { t =>
            WhatsApp.send(t.recipient, text("send")).map(result => assert(sentTo(t)(result), s"got: $result"))
        }
    }

    "a PNG is uploaded, sent by its media id, resolved, downloaded byte for byte and deleted, after which it is unknown" in {
        withTarget() { t =>
            for
                id   <- WhatsAppMedia.upload(png, WhatsAppMedia.MediaType.ImagePng, Present("dot.png"))
                sent <- WhatsApp.send(t.recipient, WhatsAppMessage.Image(WhatsAppMedia.Source.ById(id), Present("kyo-whatsapp live: dot")))
                info <- WhatsAppMedia.resolveUrl(id)
                got  <- WhatsAppMedia.download(id)
                _    <- WhatsAppMedia.delete(id)
                gone <- Abort.run[WhatsAppResolveUrlFailure](WhatsAppMedia.resolveUrl(id))
            yield
                assert(sentTo(t)(sent), s"got: $sent")
                assert(
                    (info.id, info.mimeType, info.fileSize, got.toArray.toSeq) == (id, "image/png", png.size.bytes, png.toArray.toSeq),
                    s"got: $info"
                )
                val failure = failureOf[WhatsAppInvalidParameterException](gone)
                assert((failure.method, failure.code, failure.subcode) == ("resolveUrl", 100, Present(33)))
            end for
        }
    }

    "a resolved media's sha256 is the lowercase hex SHA-256 of its bytes" in {
        withTarget(realOnly =
            Present("whaloc answers sha256 in base64, and Meta's media reference documents the field only as \"<SHA_256_HASH>\"")
        ) { _ =>
            WhatsAppMedia.upload(png, WhatsAppMedia.MediaType.ImagePng, Present("digest.png")).map { id =>
                WhatsAppMedia.resolveUrl(id).map(info =>
                    WhatsAppMedia.delete(id).andThen(assert(info.sha256 == Hex.encode(Sha256.hash(png))))
                )
            }
        }
    }

    "reply buttons and a list are sent" in {
        withTarget() { t =>
            for
                pressed <- WhatsApp.send(t.recipient, buttons)
                chosen  <- WhatsApp.send(t.recipient, list)
            yield assert(sentTo(t)(pressed) && sentTo(t)(chosen), s"got: $pressed, $chosen")
        }
    }

    "custom reads the business phone number's own object" in {
        withTarget() { t =>
            val phone = t.config.phoneNumberId
            WhatsApp.custom[Unit, PhoneNumber](HttpMethod.GET, pathOf(phone.value), HttpQueryParams.empty.add("fields", "id"))
                .map(answer => assert(answer == PhoneNumber(phone.value)))
        }
    }

    // Meta's text for an unparseable token is the Graph API's usual answer and whaloc's, which emulates it; no run against Meta has
    // confirmed it yet.
    "a token Meta cannot parse is WhatsAppTokenExpiredException, and no failure holds the token" in {
        val secretPart = Seq("kyoLiveTest", "NotAToken").mkString
        target(Absent).map { t =>
            val forged = WhatsAppConfig.init(tokenOf(secretPart), t.config.phoneNumberId, baseUrl = t.config.baseUrl).getOrThrow
            WhatsApp.run(forged)(Abort.run[WhatsAppSendFailure](WhatsApp.send(t.recipient, text("bad token")))).map { result =>
                val withoutTrace = result.failure.map {
                    case e: WhatsAppTokenExpiredException => e.copy(traceId = Absent)
                    case other                            => other
                }
                assert(withoutTrace == Present(WhatsAppTokenExpiredException(
                    "send",
                    Absent,
                    "Invalid OAuth access token - Cannot parse access token",
                    Absent,
                    Absent
                )))
                assert(!result.failure.fold(Chunk.empty[String])(BaseWhatsAppTest.renderings).exists(_.contains(secretPart)))
            }
        }
    }

    // --- What only the emulator can arrange or show ---

    "a 429 is WhatsAppThroughputRateLimitException with its Retry-After, and a send with retry set waits it out" in {
        onEmulator { server =>
            for
                _        <- server.throttleNextSend(retryAfterSeconds = 1)
                failed   <- Abort.run[WhatsAppSendFailure](WhatsApp.send(Person, text("throttled")))
                rule     <- server.throttleNextSend(retryAfterSeconds = 1)
                retrying <-
                    WhatsApp.run(server.config(retry = Present(Schedule.fixed(1.second).take(1))))(WhatsApp.send(Person, text("retried")))
                matched <- server.ruleMatches(rule)
            yield
                val failure = failureOf[WhatsAppThroughputRateLimitException](failed)
                assert((failure.method, failure.code, failure.retryAfter) == ("send", 130429, Present(1.second)))
                assert(retrying.messageId.value.startsWith("wamid."), s"got: $retrying")
                assert(matched == 1)
            end for
        }
    }

    "markReadWithTyping shows the person the typing indicator" in {
        onEmulator { server =>
            for
                said   <- server.inbound(Person, "kyo-whatsapp live: typing")
                _      <- WhatsApp.markReadWithTyping(said)
                typing <- server.typing
            yield assert(typing == Chunk(Person))
        }
    }

    // --- Interactive: a person acts in the chat, and the webhook delivers what they did ---

    /** An interactive leaf's target and every notification its webhook received. */
    private case class Live(target: Target, notifications: Channel[WhatsAppNotification])

    /** Runs `v` with the webhook served and every notification handed to `Live.notifications`. On Meta the webhook is served on
      * `KYO_WHATSAPP_WEBHOOK_PORT` behind the person's tunnel. On the container it is served on [[webhookPort]], and on every interface:
      * under rootless podman `host.containers.internal` is the host's own address, which a loopback-only server does not answer. A status
      * an earlier leaf's message produced late reaches the leaf that holds the port then, which skips it, since every leaf picks what it
      * waits for by message id.
      */
    private def interactive[R](v: Live => R < (Async & Abort[WhatsAppException | Closed | Setup] & Env[WhatsApp] & Scope))(using
        Frame
    ): R < (Async & Abort[WhatsAppException | Closed | Setup | HttpBindException | HttpRouteException] & Scope) =
        real.map {
            case Present(t) =>
                System.env[String]("KYO_WHATSAPP_INTERACTIVE").map {
                    case Absent     => cancel("KYO_WHATSAPP_INTERACTIVE not set: this leaf waits for a person to act in WhatsApp")
                    case Present(_) =>
                        required("KYO_WHATSAPP_APP_SECRET", "the webhook checks Meta's signature with the app secret").map { secret =>
                            required("KYO_WHATSAPP_VERIFY_TOKEN", "the webhook answers Meta's handshake with the verify token").map {
                                verify =>
                                    required("KYO_WHATSAPP_WEBHOOK_PORT", "the webhook listens where the tunnel forwards").map { portText =>
                                        val port = Maybe.fromOption(portText.toIntOption)
                                            .getOrElse(cancel("KYO_WHATSAPP_WEBHOOK_PORT must be a port number"))
                                        served(
                                            t.config,
                                            webhookConfigOf(secret, verify),
                                            HttpServerConfig.default.port(port).host("localhost")
                                        )(_ => t)(v)
                                    }
                            }
                        }
                }
            case Absent =>
                // The handler only records what arrives, so the client it hands the callback calls nothing.
                val placeholder = configOf("unused", WhatsAppId.PhoneNumberId("0"), url("http://127.0.0.1"))
                webhookPort.map { port =>
                    served(
                        placeholder,
                        webhookConfigOf(WhatsAppLiveServer.AppSecret, WhatsAppLiveServer.VerifyToken, Path),
                        Wildcard.port(port)
                    ) { server =>
                        // A container whose host alias resolves can still fail to connect to it, as under a daemon that itself runs in a
                        // container; the handshake proves the callback path before a leaf waits on a webhook that cannot arrive.
                        emulator(Present((server.port, Path))).map { emulator =>
                            emulator.handshake.map { h =>
                                if h.status.isEmpty then
                                    cancel(
                                        "whaloc cannot reach the host's webhook from its container (a container daemon running inside a container does not route its host alias back to this host); run on a host daemon or set KYO_WHATSAPP_TOKEN"
                                    )
                                else Target.Emulator(emulator)
                            }
                        }
                    }(v)
                }
        }

    /** The host port the container target's webhook is served on, chosen once per process. whaloc reads its webhook URL once at boot,
      * so the leaves sharing a container serve it on the same port, each binding it for its own extent: a server outliving the leaves
      * would be a socket open at the end of the run. Chosen below every platform's ephemeral range (Linux's starts at 32768), so an
      * outbound connection's local port cannot take it between two leaves.
      */
    private def webhookPort(using Frame): Int < (Async & Abort[HttpBindException | HttpRouteException]) =
        def attempt(remaining: Int): Int < (Async & Abort[HttpBindException | HttpRouteException]) =
            Random.nextInt(12000).map { offset =>
                Abort.run[HttpBindException](Scope.run(HttpServer.init(20000 + offset, "0.0.0.0")().map(_.port))).map {
                    case Result.Success(port)               => port
                    case Result.Failure(_) if remaining > 1 => attempt(remaining - 1)
                    case Result.Failure(taken)              => Abort.fail(taken)
                    case Result.Panic(e)                    => Abort.panic(e)
                }
            }
        TestContainers.getOrInit(webhookPorts, "webhook")(attempt(10))
    end webhookPort

    /** Serves the webhook on `server` with a handler on `handlerClient`, then starts the leaf's target with `start` and runs `v` on a
      * client of that target.
      */
    private def served[R](handlerClient: WhatsAppConfig, webhook: WhatsAppWebhookConfig, server: HttpServerConfig)(
        start: HttpServer => Target < (Async & Scope & Abort[Setup])
    )(v: Live => R < (Async & Abort[WhatsAppException | Closed | Setup] & Env[WhatsApp] & Scope))(using
        Frame
    ): R < (Async & Abort[WhatsAppException | Closed | Setup | HttpBindException | HttpRouteException] & Scope) =
        Channel.init[WhatsAppNotification](1024).map { notifications =>
            WhatsApp.run(handlerClient) {
                WhatsApp.Webhook.handler[Closed](webhook)(notifications.put(_)).map { handler =>
                    HttpServer.init(server)(WhatsApp.Webhook.verificationHandler(webhook), handler).map { bound =>
                        start(bound).map(target => WhatsApp.run(target.config)(v(Live(target, notifications))))
                    }
                }
            }
        }

    /** The first notification `pick` selects, skipping every other. */
    private def next[R](l: Live)(pick: WhatsAppNotification => Maybe[R])(using Frame): R < (Async & Abort[Closed]) =
        l.notifications.take.map { notification =>
            pick(notification) match
                case Present(r) => r
                case Absent     => next(l)(pick)
        }

    /** What the person does after an instruction: on Meta the instruction asks for it, on the container it is injected. */
    private enum Act derives CanEqual:
        case Replies(text: String)
        case Presses(id: String, title: String)
        case Chooses(id: String, title: String, description: String)
        case Reacts(emoji: String)
        case Reads
    end Act

    /** Sends the person `message`, who then does `act`, answering the message sent and the person's wa_id as the send resolved it, which
      * inbound messages come from.
      */
    private def ask(l: Live, message: WhatsAppMessage, act: Act)(using
        Frame
    ): (WhatsAppSendResult, WhatsAppId.WaId) < (Async & Abort[WhatsAppSendFailure | Setup] & Env[WhatsApp]) =
        WhatsApp.send(l.target.recipient, message).map { sent =>
            val person = sent.contactWaId.getOrElse(l.target.recipient)
            perform(l, sent, person, act).andThen((sent, person))
        }

    /** The person does `act` on `sent`: injected on the container, left to the person on Meta. */
    private def perform(l: Live, sent: WhatsAppSendResult, person: WhatsAppId.WaId, act: Act)(using
        Frame
    ): Unit < (Async & Abort[Setup]) =
        l.target match
            case Target.Real(_, _)       => Kyo.unit
            case Target.Emulator(server) =>
                act match
                    case Act.Replies(text)                   => server.replies(person, sent.messageId, text).unit
                    case Act.Presses(id, title)              => server.presses(person, sent.messageId, id, title).unit
                    case Act.Chooses(id, title, description) => server.chooses(person, sent.messageId, id, title, description).unit
                    case Act.Reacts(emoji)                   => server.reacts(person, sent.messageId, emoji).unit
                    case Act.Reads                           => server.reads(sent.messageId)

    private def replyingTo(prompt: WhatsAppSendResult, person: WhatsAppId.WaId)(m: WhatsAppInboundMessage.Common): Boolean =
        m.from == person && m.context.map(_.id) == Present(prompt.messageId)

    "a text reply arrives as Text with the message it replies to, and is marked read" in {
        interactive { l =>
            for
                (prompt, person)  <- ask(l, text("reply to this message (swipe it) with the word kyo"), Act.Replies("kyo"))
                (metadata, reply) <- next(l) {
                    case WhatsAppNotification.Message(md, _, m: WhatsAppInboundMessage.Text) if replyingTo(prompt, person)(m) =>
                        Present((md, m))
                    case _ => Absent
                }
                _ <- WhatsApp.markRead(reply.id)
            yield assert(
                (reply.body, reply.context.map(_.id), metadata.phoneNumberId) ==
                    ("kyo", Present(prompt.messageId), l.target.config.phoneNumberId)
            )
        }
    }

    "a reply button press arrives as Interactive ButtonReply with the button's id and title" in {
        interactive { l =>
            for
                (prompt, person) <- ask(l, buttons, Act.Presses("kyo-live:yes", "Yes"))
                press            <- next(l) {
                    case WhatsAppNotification.Message(_, _, m: WhatsAppInboundMessage.Interactive) if replyingTo(prompt, person)(m) =>
                        Present(m.interactive)
                    case _ => Absent
                }
            yield assert(
                press == WhatsAppInboundMessage.Interactive.Reply.ButtonReply(
                    WhatsAppInboundMessage.Interactive.Reply.ButtonReply.Body("kyo-live:yes", "Yes")
                ),
                s"got: $press"
            )
        }
    }

    "a list selection arrives as Interactive ListReply with the row's id, title and description" in {
        interactive { l =>
            for
                (prompt, person) <- ask(l, list, Act.Chooses("kyo-live:two", "Two", "the second row"))
                choice           <- next(l) {
                    case WhatsAppNotification.Message(_, _, m: WhatsAppInboundMessage.Interactive) if replyingTo(prompt, person)(m) =>
                        Present(m.interactive)
                    case _ => Absent
                }
            yield assert(
                choice == WhatsAppInboundMessage.Interactive.Reply.ListReply(
                    WhatsAppInboundMessage.Interactive.Reply.ListReply.Body("kyo-live:two", "Two", Present("the second row"))
                ),
                s"got: $choice"
            )
        }
    }

    "a reaction a person sets on the bot's message arrives as Reaction with the emoji" in {
        interactive { l =>
            for
                (prompt, person) <- ask(l, text("react to this message with 😂"), Act.Reacts("😂"))
                reaction         <- next(l) {
                    case WhatsAppNotification.Message(_, _, m: WhatsAppInboundMessage.Reaction)
                        if m.from == person && m.messageId == prompt.messageId => Present(m)
                    case _ => Absent
                }
            yield assert((reaction.messageId, reaction.emoji) == (prompt.messageId, "😂"), s"got: $reaction")
        }
    }

    "a message the bot sent is reported sent, delivered and read, each status for the recipient" in {
        val wanted = Set[WhatsAppStatus.Kind](WhatsAppStatus.Kind.Sent, WhatsAppStatus.Kind.Delivered, WhatsAppStatus.Kind.Read)
        def statuses(l: Live, prompt: WhatsAppSendResult, seen: Chunk[WhatsAppStatus], until: Set[WhatsAppStatus.Kind])(using
            Frame
        ): Chunk[WhatsAppStatus] < (Async & Abort[Closed]) =
            if until.subsetOf(seen.map(_.status).toSet) then seen
            else
                next(l) {
                    case WhatsAppNotification.Status(_, s) if s.id == prompt.messageId => Present(s)
                    case _                                                             => Absent
                }.map(s => statuses(l, prompt, seen :+ s, until))
        interactive { l =>
            for
                prompt <- WhatsApp.send(l.target.recipient, text("open this chat so this message shows as read"))
                person = prompt.contactWaId.getOrElse(l.target.recipient)
                // A person reads only a delivered message, and whaloc drops the pending delivered status of a message read before it.
                delivered <- statuses(l, prompt, Chunk.empty, Set(WhatsAppStatus.Kind.Sent, WhatsAppStatus.Kind.Delivered))
                _         <- perform(l, prompt, person, Act.Reads)
                seen      <- statuses(l, prompt, delivered, wanted)
                got = (seen.map(_.status).toSet.intersect(wanted), seen.map(_.recipientId).distinct)
            yield assert(got == (wanted, Chunk(person)), s"got: $got")
        }
    }

    "Meta's verification handshake is answered with its challenge" in {
        interactive { l =>
            l.target match
                case Target.Real(_, _) =>
                    cancel("on Meta the handshake runs when the callback URL is saved in the app, not on the suite's request")
                case Target.Emulator(server) =>
                    server.handshake.map(h => assert((h.ok, h.status, h.echo) == (true, Present(200), Present(h.challenge))))
        }
    }

end WhatsAppLiveTest

object WhatsAppLiveTest:

    /** The person a container leaf talks to. */
    val Person: WhatsAppId.WaId = WhatsAppId.WaId("5511999990000")

    private val Path = "webhook"

    private val Wildcard = HttpServerConfig.default.port(0).host("0.0.0.0")

    private val webhookPorts = TestContainers.memo[Int, HttpBindException | HttpRouteException]

    final case class PhoneNumber(id: String) derives CanEqual, Schema

    private def text(body: String): WhatsAppMessage = WhatsAppMessage.Text(s"kyo-whatsapp live: $body")

    /** A 1x1 PNG. */
    private val png: Span[Byte] = Span.from(Array[Int](
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
        0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63,
        0xf8, 0xcf, 0xc0, 0xf0, 0x1f, 0x00, 0x05, 0x00, 0x01, 0xff, 0x89, 0x99, 0x3d, 0x1d, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
        0xae, 0x42, 0x60, 0x82
    ).map(_.toByte))

    private val buttons: WhatsAppMessage = WhatsAppMessage.OfInteractive(WhatsAppInteractive.Buttons(
        Chunk(WhatsAppInteractive.ReplyButton("kyo-live:yes", "Yes"), WhatsAppInteractive.ReplyButton("kyo-live:no", "No")),
        body = Present("kyo-whatsapp live: press Yes")
    ))

    private val list: WhatsAppMessage = WhatsAppMessage.OfInteractive(WhatsAppInteractive.ListMenu(
        "Options",
        Chunk(WhatsAppInteractive.Section(
            "kyo",
            Chunk(
                WhatsAppInteractive.Row("kyo-live:one", "One", Present("the first row")),
                WhatsAppInteractive.Row("kyo-live:two", "Two", Present("the second row"))
            )
        )),
        body = Present("kyo-whatsapp live: open Options and choose Two")
    ))

end WhatsAppLiveTest
