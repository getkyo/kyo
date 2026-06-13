package demo

import kyo.*

/** kyo-whatsapp feature demo against the real WhatsApp Business Cloud API (no mocks).
  *
  * Credentials come from the `StaticFlag` default config, so nothing secret lives in source:
  * set `KYO_WHATSAPP_FLAGS_TOKEN` and `KYO_WHATSAPP_FLAGS_PHONENUMBERID` (the test sender's
  * access token and Phone Number ID from the Meta app's API Setup page). The recipient wa_id
  * is the first program argument (E.164 digits, no `+`).
  *
  * Run:
  * {{{
  * KYO_WHATSAPP_FLAGS_TOKEN=EAA... KYO_WHATSAPP_FLAGS_PHONENUMBERID=123456789012345 \
  *   sbt 'kyo-whatsappJVM/Test/runMain demo.WhatsAppDemo 5519992056433'
  * }}}
  *
  * Only template messages send outside the 24h customer-service window. The free-form text,
  * interactive, and location sends succeed only after the recipient has messaged the sender
  * within 24h; before that they surface a typed `WhatsAppError` (which the demo prints, so the
  * error-mapping path is exercised either way).
  */
object WhatsAppDemo extends KyoApp:

    /** Runs one outbound call, prints its typed outcome, and never aborts the demo. */
    def report(label: String)(call: WhatsAppSendResult < (Async & Abort[WhatsAppError]))(
        using Frame
    ): Unit < (Async & Sync) =
        Abort.run[WhatsAppError](call).map {
            case Result.Success(r) => Console.printLine(s"  [OK]    $label  ->  wamid ${r.messageId.value}")
            case Result.Failure(e) => Console.printLine(s"  [ERROR] $label  ->  $e")
            case Result.Panic(ex)  => Console.printLine(s"  [PANIC] $label  ->  ${ex.getMessage}")
        }

    run {
        val to = WhatsAppId.WaId(args.headOption.getOrElse("5519992056433"))
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
        end for
    }
end WhatsAppDemo

/** Inbound webhook server demo. Mounts the GET verification handshake and the POST event
  * handler (X-Hub-Signature-256 verified against the app secret), printing each decoded
  * `WhatsAppNotification`. Args: verify-token, app-secret, [port]. Expose the port with a tunnel
  * (for example `ngrok http 8080`) and register the tunnel URL plus the verify token in the
  * Meta app's WhatsApp Configuration page.
  *
  * Run:
  * {{{
  * sbt 'kyo-whatsappJVM/Test/runMain demo.WhatsAppWebhookDemo my-verify-token <app-secret> 8080'
  * }}}
  */
object WhatsAppWebhookDemo extends KyoApp:

    run {
        val verifyToken = args.headOption.getOrElse("kyo-verify-token")
        val appSecret   = args.lift(1).getOrElse("")
        val port        = args.lift(2).flatMap(_.toIntOption).getOrElse(8080)
        HttpServer.init(HttpServerConfig.default.port(port))(
            WhatsAppWebhook.verificationHandler(verifyToken),
            WhatsAppWebhook.handler(appSecret) { notification =>
                Console.printLine(s"  inbound: $notification")
            }
        ).map { server =>
            for
                _ <- Console.printLine(s"webhook server on http://localhost:${server.port}")
                _ <- Console.printLine(s"expose it: ngrok http ${server.port}")
                _ <- server.await
            yield ()
        }
    }
end WhatsAppWebhookDemo
