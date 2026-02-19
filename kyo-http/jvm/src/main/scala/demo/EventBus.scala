package demo

import kyo.*

/** Event bus: POST events, stream them as NDJSON.
  *
  * Demonstrates: form data input, NDJSON streaming (server + client), POST with JSON body, warmupUrl, retry with Schedule.
  *
  * Endpoints:
  *   - POST /events — publish an event (JSON body)
  *   - POST /events/form — publish an event (form data)
  *   - GET /stream — NDJSON stream of all events
  *   - GET /events — list all events (buffered)
  *
  * Test: curl -X POST http://localhost:3010/events -H "Content-Type: application/json" -d
  * '{"source":"curl","kind":"test","payload":"hello"}' curl -X POST http://localhost:3010/events/form -d
  * "source=curl&kind=form-test&payload=world" curl -N http://localhost:3010/stream curl http://localhost:3010/events
  */
object EventBus extends KyoApp:

    case class Event(source: String, kind: String, payload: String) derives Schema
    case class StoredEvent(id: Int, source: String, kind: String, payload: String, timestamp: String) derives Schema
    case class FormEvent(source: String, kind: String, payload: String) derives HttpFormCodec

    def storeEvent(
        event: Event,
        eventsRef: AtomicRef[List[StoredEvent]],
        nextIdRef: AtomicInt
    ): StoredEvent < Async =
        for
            id  <- nextIdRef.incrementAndGet
            now <- Clock.now
            stored = StoredEvent(id, event.source, event.kind, event.payload, now.toString)
            _ <- eventsRef.updateAndGet(stored :: _)
        yield stored

    run {
        for
            eventsRef <- AtomicRef.init(List.empty[StoredEvent])
            nextIdRef <- AtomicInt.init(0)

            // JSON body route
            postJsonRoute = HttpRoute
                .post("events")
                .request(_.bodyJson[Event])
                .response(_.bodyJson[StoredEvent].status(HttpStatus.Created))
                .metadata(_.summary("Publish event (JSON)").tag("events"))
                .handle { in =>
                    storeEvent(in.body, eventsRef, nextIdRef)
                }

            // Form data route
            postFormRoute = HttpRoute
                .post("events" / "form")
                .request(_.bodyForm[FormEvent])
                .response(_.bodyJson[StoredEvent].status(HttpStatus.Created))
                .metadata(_.summary("Publish event (form)").tag("events"))
                .handle { in =>
                    val event = Event(in.body.source, in.body.kind, in.body.payload)
                    storeEvent(event, eventsRef, nextIdRef)
                }

            // List all events (buffered)
            listRoute = HttpRoute
                .get("events")
                .response(_.bodyJson[List[StoredEvent]])
                .metadata(_.summary("List all events").tag("events"))
                .handle { _ =>
                    eventsRef.get.map(_.reverse)
                }

            // NDJSON stream
            ndjsonHandler = HttpHandler.streamNdjson[StoredEvent]("stream") { _ =>
                // Poll for new events every 2 seconds, emit new ones
                AtomicRef.init(0).map { lastSeenRef =>
                    Stream.init(Chunk.from(1 to 10000)).mapChunk { _ =>
                        Async.delay(2.seconds) {
                            for
                                lastSeen <- lastSeenRef.get
                                events   <- eventsRef.get
                                newEvents = events.filter(_.id > lastSeen).reverse
                                _ <- lastSeenRef.set(events.headOption.map(_.id).getOrElse(lastSeen))
                            yield Chunk.from(newEvents)
                        }
                    }
                }
            }

            health = HttpHandler.health()

            server <- HttpFilter.server.logging.enable {
                HttpServer.init(
                    HttpServer.Config.default.port(3010).openApi("/openapi.json", "Event Bus")
                )(postJsonRoute, postFormRoute, listRoute, ndjsonHandler, health)
            }
            _ <- Console.printLine(s"EventBus running on http://localhost:${server.port}")
            _ <- Console.printLine(
                """  curl -X POST http://localhost:3010/events -H "Content-Type: application/json" -d '{"source":"curl","kind":"test","payload":"hello"}'"""
            )
            _ <- Console.printLine("""  curl -X POST http://localhost:3010/events/form -d "source=curl&kind=form-test&payload=world"""")
            _ <- Console.printLine("  curl -N http://localhost:3010/stream")
            _ <- Console.printLine("  curl http://localhost:3010/events")
            _ <- server.await
        yield ()
    }
end EventBus
