package demo

import kyo.*

/** Webhook relay: receive webhooks via POST, replay to SSE subscribers.
  *
  * Demonstrates: bodySseText (SSE with plain text), HttpResponse.accepted (202), securityHeaders with custom hsts and csp, operationId and
  * description in metadata, CORS with custom allowHeaders/exposeHeaders, OpenAPI.
  */
object WebhookRelay extends KyoApp:

    case class Webhook(id: Int, source: String, event: String, payload: String, receivedAt: String) derives Schema
    case class WebhookInput(source: String, event: String, payload: String) derives Schema

    val serverFilter = HttpFilter.server.logging
        .andThen(HttpFilter.server.securityHeaders(
            hsts = Present(365.days),
            csp = Present("default-src 'none'")
        ))
        .andThen(HttpFilter.server.cors(
            allowOrigin = "*",
            allowMethods = Seq(HttpMethod.GET, HttpMethod.POST),
            allowHeaders = Seq("Content-Type", "X-Webhook-Secret"),
            exposeHeaders = Seq("X-Webhook-Id")
        ))

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            storeRef  <- AtomicRef.init(List.empty[Webhook])
            nextIdRef <- AtomicInt.init(0)

            // POST /hooks — receive webhook, return 202 Accepted
            receive = HttpRoute
                .postRaw("hooks")
                .filter(serverFilter)
                .request(_.bodyJson[WebhookInput])
                .response(_.bodyJson[Webhook].status(HttpStatus.Accepted))
                .metadata(
                    _.summary("Receive a webhook")
                        .description("Accepts a webhook payload and queues it for SSE subscribers. Returns 202 Accepted.")
                        .operationId("receiveWebhook")
                        .tag("webhooks")
                )
                .handler { req =>
                    val input = req.fields.body
                    for
                        id  <- nextIdRef.incrementAndGet
                        now <- Clock.now
                        webhook = Webhook(id, input.source, input.event, input.payload, now.toString)
                        _ <- storeRef.updateAndGet(webhook :: _)
                    yield HttpResponse.acceptedJson(webhook).setHeader("X-Webhook-Id", id.toString)
                    end for
                }

            // GET /hooks/list — list buffered webhooks
            list = HttpRoute
                .getRaw("hooks" / "list")
                .filter(serverFilter)
                .request(_.queryOpt[Int]("limit"))
                .response(_.bodyJson[List[Webhook]])
                .metadata(
                    _.summary("List received webhooks")
                        .description("Returns buffered webhooks, most recent first. Optional limit query param.")
                        .operationId("listWebhooks")
                        .tag("webhooks")
                )
                .handler { req =>
                    for hooks <- storeRef.get
                    yield
                        val limited = req.fields.limit match
                            case Present(n) => hooks.take(n)
                            case _          => hooks
                        HttpResponse.okJson(limited)
                }

            // GET /hooks/stream — SSE text stream
            sseHandler = HttpHandler.getSseText("hooks/stream") { _ =>
                AtomicRef.init(0).map { lastSeenRef =>
                    Stream.repeatPresent[HttpEvent[String], Async] {
                        for
                            _        <- Async.delay(2.seconds)(())
                            lastSeen <- lastSeenRef.get
                            hooks    <- storeRef.get
                            newHooks = hooks.filter(_.id > lastSeen).reverse
                            _ <- lastSeenRef.set(hooks.headOption.map(_.id).getOrElse(lastSeen))
                        yield Maybe.Present(newHooks.map { hook =>
                            HttpEvent(
                                data = s"[${hook.source}] ${hook.event}: ${hook.payload}",
                                event = Present(hook.event),
                                id = Present(hook.id.toString)
                            )
                        })
                    }
                }
            }

            health = HttpHandler.health()
            server <- HttpServer.init(
                HttpServer.Config().port(port).openApi("/openapi.json", "Webhook Relay")
            )(receive, list, sseHandler, health)
            _ <- Console.printLine(s"WebhookRelay running on http://localhost:${server.port}")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/hooks -H "Content-Type: application/json" -d '{"source":"github","event":"push","payload":"main branch updated"}'"""
            )
            _ <- Console.printLine(s"  curl -N http://localhost:${server.port}/hooks/stream")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/hooks/list")
            _ <- server.await
        yield ()
        end for
    }
end WebhookRelay
