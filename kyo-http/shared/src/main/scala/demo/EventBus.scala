package demo

import kyo.*

/** Event bus: POST events, stream them as NDJSON.
  *
  * Demonstrates: form data input, NDJSON streaming (server + client), POST with JSON body.
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

    val loggingFilter = HttpFilter.server.logging

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            eventsRef <- AtomicRef.init(List.empty[StoredEvent])
            nextIdRef <- AtomicInt.init(0)

            // JSON body route
            postJsonRoute = HttpRoute
                .postRaw("events")
                .filter(loggingFilter)
                .request(_.bodyJson[Event])
                .response(_.bodyJson[StoredEvent].status(HttpStatus.Created))
                .metadata(_.summary("Publish event (JSON)").tag("events"))
                .handler { req =>
                    storeEvent(req.fields.body, eventsRef, nextIdRef).map(HttpResponse.okJson(_))
                }

            // Form data route
            postFormRoute = HttpRoute
                .postRaw("events" / "form")
                .filter(loggingFilter)
                .request(_.bodyForm[FormEvent])
                .response(_.bodyJson[StoredEvent].status(HttpStatus.Created))
                .metadata(_.summary("Publish event (form)").tag("events"))
                .handler { req =>
                    val event = Event(req.fields.body.source, req.fields.body.kind, req.fields.body.payload)
                    storeEvent(event, eventsRef, nextIdRef).map(HttpResponse.okJson(_))
                }

            // List all events (buffered)
            listRoute = HttpHandler.getJson[List[StoredEvent]]("events") { _ =>
                eventsRef.get.map(_.reverse)
            }

            // NDJSON stream — polls for new events every 2 seconds
            ndjsonHandler = HttpHandler.getNdJson[StoredEvent]("stream") { _ =>
                AtomicRef.init(0).map { lastSeenRef =>
                    Stream.repeatPresent[StoredEvent, Async] {
                        for
                            _        <- Async.delay(2.seconds)(())
                            lastSeen <- lastSeenRef.get
                            events   <- eventsRef.get
                            newEvents = events.filter(_.id > lastSeen).reverse
                            _ <- lastSeenRef.set(events.headOption.map(_.id).getOrElse(lastSeen))
                        yield Maybe.Present(newEvents)
                    }
                }
            }

            health = HttpHandler.health()

            server <- HttpServer.init(
                HttpServer.Config().port(port).openApi("/openapi.json", "Event Bus")
            )(postJsonRoute, postFormRoute, listRoute, ndjsonHandler, health)
            _ <- Console.printLine(s"EventBus running on http://localhost:${server.port}")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/events -H "Content-Type: application/json" -d '{"source":"curl","kind":"test","payload":"hello"}'"""
            )
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/events/form -d "source=curl&kind=form-test&payload=world""""
            )
            _ <- Console.printLine(s"  curl -N http://localhost:${server.port}/stream")
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/events")
            _ <- server.await
        yield ()
        end for
    }
end EventBus
