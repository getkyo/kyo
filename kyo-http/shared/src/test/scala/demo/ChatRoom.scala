package demo

import kyo.*

/** Simple chat room with text messages and SSE activity feed.
  *
  * Server: POST text messages, GET all messages, subscribe to live activity via SSE text stream. Uses server-level CORS and OpenAPI
  * security metadata. Client: posts messages, lists them, then consumes the SSE text feed.
  *
  * Demonstrates: HttpHandler.getText, HttpHandler.postText (convenience), HttpClient.getSseText (client-side SSE text consumption),
  * server-level CORS (HttpServer.Config.cors), OpenAPI security metadata, request body text (bodyText).
  */
object ChatRoom extends KyoApp:

    case class Message(id: Int, user: String, text: String) derives Schema

    case class Store(messages: List[Message], nextId: Int)

    def handlers(storeRef: AtomicRef[Store]) =

        val post = HttpHandler.postText("messages") { (_, body) =>
            storeRef.updateAndGet { s =>
                val parts = body.split(":", 2)
                val (user, text) =
                    if parts.length == 2 then (parts(0).trim, parts(1).trim)
                    else ("anonymous", body.trim)
                val msg = Message(s.nextId, user, text)
                Store(msg :: s.messages, s.nextId + 1)
            }.map(s => s"OK: message ${s.nextId - 1}")
        }

        val list = HttpHandler.getJson[List[Message]]("messages") { _ =>
            storeRef.get.map(_.messages.reverse)
        }

        val feed = HttpHandler.getSseText("messages/feed") { _ =>
            AtomicRef.init(0).map { lastSeenRef =>
                Stream[HttpEvent[String], Async] {
                    Loop.foreach {
                        for
                            _        <- Async.delay(1.second)(())
                            lastSeen <- lastSeenRef.get
                            store    <- storeRef.get
                            allMsgs = store.messages.reverse
                            newMsgs = allMsgs.drop(lastSeen)
                            _ <- lastSeenRef.set(allMsgs.size)
                        yield Emit.valueWith(Chunk.from(newMsgs.map { m =>
                            HttpEvent(data = s"[${m.user}] ${m.text}", event = Present("message"))
                        }))(Loop.continue)
                    }
                }
            }
        }

        (post, list, feed)
    end handlers

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            storeRef <- AtomicRef.init(Store(List.empty, 1))
            (post, list, feed) = handlers(storeRef)
            health             = HttpHandler.health()
            server <- HttpServer.init(
                HttpServer.Config()
                    .port(port)
                    .cors(CorsConfig(allowOrigin = "*", allowCredentials = false))
                    .openApi("/openapi.json", "ChatRoom")
            )(post, list, feed, health)
            _ <- Console.printLine(s"ChatRoom running on http://localhost:${server.port}")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/messages -H "Content-Type: text/plain" -d "Alice: Hello everyone!\""""
            )
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/messages")
            _ <- Console.printLine(s"  curl -N http://localhost:${server.port}/messages/feed")
            _ <- server.await
        yield ()
        end for
    }
end ChatRoom

/** Client that exercises ChatRoom text posting and SSE text consumption.
  *
  * Demonstrates: HttpClient.postText, HttpClient.getText, HttpClient.getSseText, stream.take for bounded SSE.
  */
object ChatRoomClient extends KyoApp:

    import ChatRoom.*

    run {
        for
            storeRef <- AtomicRef.init(Store(List.empty, 1))
            (post, list, feed) = handlers(storeRef)
            server <- HttpServer.init(
                HttpServer.Config()
                    .port(0)
                    .cors(CorsConfig(allowOrigin = "*"))
            )(post, list, feed)
            _ <- Console.printLine(s"ChatRoomClient started server on http://localhost:${server.port}")

            _ <- HttpClient.withConfig(_.baseUrl(s"http://localhost:${server.port}").timeout(5.seconds)) {
                for
                    _  <- Console.printLine("\n=== Posting messages ===")
                    r1 <- HttpClient.postText("/messages", "Alice: Hello everyone!")
                    _  <- Console.printLine(s"  $r1")
                    r2 <- HttpClient.postText("/messages", "Bob: Hey Alice!")
                    _  <- Console.printLine(s"  $r2")
                    r3 <- HttpClient.postText("/messages", "Welcome to Kyo")
                    _  <- Console.printLine(s"  $r3")

                    _    <- Console.printLine("\n=== Listing messages ===")
                    msgs <- HttpClient.getJson[List[Message]]("/messages")
                    _ <- Kyo.foreach(msgs) { m =>
                        Console.printLine(s"  [${m.id}] ${m.user}: ${m.text}")
                    }

                    _      <- Console.printLine("\n=== Consuming SSE text feed ===")
                    stream <- HttpClient.getSseText(s"http://localhost:${server.port}/messages/feed")
                    _ <- stream.take(3).foreachChunk { chunk =>
                        Kyo.foreach(chunk.toSeq) { event =>
                            Console.printLine(s"  [SSE] ${event.data}")
                        }
                    }
                yield ()
            }

            _ <- server.closeNow
            _ <- Console.printLine("\nDone.")
        yield ()
        end for
    }
end ChatRoomClient
