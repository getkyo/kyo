package demo

import kyo.*

/** kyo-whatsapp feature demo against the real WhatsApp Business Cloud API (no mocks).
  *
  * Credentials come from the environment, so nothing secret lives in source or on the command line: set `KYO_WHATSAPP_TOKEN` and
  * `KYO_WHATSAPP_PHONE_NUMBER_ID` (the test sender's access token and Phone Number ID from the Meta app's API Setup page). The recipient
  * wa_id is the first program argument (E.164 digits, no `+`) and is required: without it the demo prints its usage and exits with 1.
  *
  * Run:
  * {{{
  * KYO_WHATSAPP_TOKEN=EAA... KYO_WHATSAPP_PHONE_NUMBER_ID=123456789012345 \
  *   sbt 'kyo-whatsappJVM/Test/runMain demo.WhatsAppDemo <recipient-wa-id>'
  * }}}
  *
  * Only template messages send outside the 24h customer-service window. The free-form text, interactive, and location sends succeed only
  * after the recipient has messaged the sender within 24h; before that they surface a typed `WhatsAppException` (which the demo prints, so
  * the error-mapping path is exercised either way).
  */
object WhatsAppDemo extends KyoApp:

    /** Runs one outbound call, prints its typed outcome, and never aborts the demo. */
    def report[E <: WhatsAppException](label: String)(call: WhatsAppSendResult < (Async & Abort[E] & Env[WhatsApp]))(
        using
        ConcreteTag[E],
        Frame
    ): Unit < (Async & Env[WhatsApp]) =
        Abort.run[E](call).map {
            case Result.Success(r) => Console.printLine(s"  [OK]    $label  ->  wamid ${r.messageId.value}")
            case Result.Failure(e) => Console.printLine(s"  [ERROR] $label  ->  $e")
            case Result.Panic(ex)  => Console.printLine(s"  [PANIC] $label  ->  ${ex.getMessage}")
        }

    run {
        args.headMaybe match
            case Absent =>
                Abort.fail("usage: demo.WhatsAppDemo <recipient-wa-id> (E.164 digits, no +)")
            case Present(recipient) =>
                for
                    token <- System.env[String]("KYO_WHATSAPP_TOKEN")
                    phone <- System.env[String]("KYO_WHATSAPP_PHONE_NUMBER_ID")
                    _     <- (token, phone) match
                        case (Present(t), Present(p)) =>
                            for
                                accessToken <- Abort.get(WhatsAppToken.init(t))
                                config      <- Abort.get(WhatsAppConfig.init(accessToken, WhatsAppId.PhoneNumberId(p)))
                                _           <- WhatsApp.run(config)(outbound(WhatsAppId.WaId(recipient)))
                            yield ()
                        case _ =>
                            Console.printLine("set KYO_WHATSAPP_TOKEN and KYO_WHATSAPP_PHONE_NUMBER_ID")
                yield ()
    }

    def outbound(to: WhatsAppId.WaId)(using Frame): Unit < (Async & Env[WhatsApp]) =
        for
            _ <- Console.printLine(s"=== kyo-whatsapp outbound demo  ->  ${to.value} ===")

            // 1. Template message: the only class that sends outside the 24h window.
            //    Uses the sample order-confirmation template (3 body text parameters).
            _ <- report("template jaspers_market_order_confirmation_v1")(
                WhatsApp.sendTemplate(
                    to,
                    WhatsAppTemplate(
                        "jaspers_market_order_confirmation_v1",
                        "en_US",
                        Chunk(
                            WhatsAppTemplate.Component.Body(
                                Chunk(
                                    WhatsAppTemplate.Parameter.Text("John Doe"),
                                    WhatsAppTemplate.Parameter.Text("123456"),
                                    WhatsAppTemplate.Parameter.Text("Jun 14, 2026")
                                )
                            )
                        )
                    )
                )
            )

            // 2. Free-form text (needs an open 24h window).
            _ <- report("text")(
                WhatsApp.send(to, WhatsAppMessage.Text("Hello from kyo-whatsapp!"))
            )

            // 3. WhatsAppInteractive reply buttons (needs an open window).
            _ <- report("interactive buttons")(
                WhatsApp.send(
                    to,
                    WhatsAppMessage.OfInteractive(
                        WhatsAppInteractive.Buttons(
                            Chunk(
                                WhatsAppInteractive.ReplyButton("yes", "Yes"),
                                WhatsAppInteractive.ReplyButton("no", "No")
                            ),
                            body = Present("Does kyo-whatsapp work end to end?")
                        )
                    )
                )
            )

            // 4. Location message (needs an open window).
            _ <- report("location")(
                WhatsApp.send(
                    to,
                    WhatsAppMessage.Location(-22.9068, -43.1729, name = Present("Rio de Janeiro"))
                )
            )

            _ <- Console.printLine("=== done (a [OK] template means it reached WhatsApp) ===")
        yield ()
end WhatsAppDemo

/** Inbound webhook server demo. Mounts the GET verification handshake and the POST notification handler (X-Hub-Signature-256 verified
  * against the app secret), printing each decoded `WhatsAppNotification` and marking each inbound message read. Credentials come from the
  * environment: `KYO_WHATSAPP_TOKEN`, `KYO_WHATSAPP_PHONE_NUMBER_ID`, `KYO_WHATSAPP_APP_SECRET` and `KYO_WHATSAPP_VERIFY_TOKEN`. The port
  * is the first program argument (default 8080). Expose it with a tunnel (for example `ngrok http 8080`) and register the tunnel URL plus
  * the verify token in the Meta app's WhatsApp Configuration page.
  *
  * Run:
  * {{{
  * sbt 'kyo-whatsappJVM/Test/runMain demo.WhatsAppWebhookDemo 8080'
  * }}}
  */
object WhatsAppWebhookDemo extends KyoApp:

    run {
        for
            token       <- System.env[String]("KYO_WHATSAPP_TOKEN")
            phone       <- System.env[String]("KYO_WHATSAPP_PHONE_NUMBER_ID")
            appSecret   <- System.env[String]("KYO_WHATSAPP_APP_SECRET")
            verifyToken <- System.env[String]("KYO_WHATSAPP_VERIFY_TOKEN")
            _           <- (token, phone, appSecret, verifyToken) match
                case (Present(t), Present(p), Present(s), Present(v)) =>
                    val port = args.headMaybe.flatMap(a => Maybe.fromOption(a.toIntOption)).getOrElse(8080)
                    for
                        accessToken <- Abort.get(WhatsAppToken.init(t))
                        config      <- Abort.get(WhatsAppConfig.init(accessToken, WhatsAppId.PhoneNumberId(p)))
                        secret      <- Abort.get(WhatsAppAppSecret.init(s))
                        verify      <- Abort.get(WhatsAppVerifyToken.init(v))
                        webhook     <- Abort.get(WhatsAppWebhookConfig.init(secret, verify))
                        _           <- serve(port, config, webhook)
                    yield ()
                    end for
                case _ =>
                    Console.printLine(
                        "set KYO_WHATSAPP_TOKEN, KYO_WHATSAPP_PHONE_NUMBER_ID, KYO_WHATSAPP_APP_SECRET and KYO_WHATSAPP_VERIFY_TOKEN"
                    )
        yield ()
    }

    def serve(port: Int, config: WhatsAppConfig, webhook: WhatsAppWebhookConfig)(using
        Frame
    ): Unit < (Async & Scope & Abort[HttpBindException]) =
        WhatsApp.run(config)(WhatsApp.Webhook.handler(webhook) {
            case WhatsAppNotification.Message(_, _, message: WhatsAppInboundMessage.Common) =>
                Console.printLine(s"  inbound: $message").andThen(WhatsApp.markRead(message.id))
            case other =>
                Console.printLine(s"  inbound: $other")
        }.map { handler =>
            HttpServer.init(HttpServerConfig.default.port(port))(WhatsApp.Webhook.verificationHandler(webhook), handler).map { server =>
                for
                    _ <- Console.printLine(s"webhook server on http://localhost:${server.port}")
                    _ <- Console.printLine(s"expose it: ngrok http ${server.port}")
                    _ <- server.await
                yield ()
            }
        })
end WhatsAppWebhookDemo
