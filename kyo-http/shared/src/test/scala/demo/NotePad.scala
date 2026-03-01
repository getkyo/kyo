package demo

import kyo.*

/** Collaborative note-taking API with live SSE updates.
  *
  * Server: CRUD notes with PATCH support, cookie-based session tracking, and SSE change feed. Client: creates notes, patches them,
  * subscribes to live changes via getSseJson.
  *
  * Demonstrates: PATCH method, cookie attributes (maxAge, httpOnly, sameSite), client-side SSE consumption (getSseJson), multiple error
  * mappings on a single route, HttpClient.patchJson.
  */
object NotePad extends KyoApp:

    case class Note(id: Int, title: String, content: String, updatedAt: String) derives Schema
    case class CreateNote(title: String, content: String) derives Schema
    case class PatchNote(title: Option[String], content: Option[String]) derives Schema
    case class NoteChange(kind: String, note: Note) derives Schema

    case class NotFound(error: String) derives Schema
    case class ValidationError(error: String, field: String) derives Schema

    case class Store(notes: Map[Int, Note], nextId: Int)

    def handlers(storeRef: AtomicRef[Store], changesRef: AtomicRef[List[NoteChange]]) =

        val list = HttpHandler.getJson[List[Note]]("notes") { _ =>
            storeRef.get.map(_.notes.values.toList.sortBy(_.id))
        }

        val create = HttpRoute
            .postRaw("notes")
            .request(_.bodyJson[CreateNote])
            .response(
                _.bodyJson[Note]
                    .status(HttpStatus.Created)
                    .cookie[String]("session")
                    .error[ValidationError](HttpStatus.BadRequest)
            )
            .handler { req =>
                val input = req.fields.body
                if input.title.isBlank then Abort.fail(ValidationError("Title cannot be blank", "title"))
                else
                    for
                        now <- Clock.now
                        store <- storeRef.updateAndGet { s =>
                            val note = Note(s.nextId, input.title, input.content, now.toString)
                            Store(s.notes + (s.nextId -> note), s.nextId + 1)
                        }
                        created = store.notes(store.nextId - 1)
                        _ <- changesRef.updateAndGet(NoteChange("created", created) :: _)
                    yield HttpResponse.okJson(created)
                        .addField(
                            "session",
                            HttpCookie(s"user-${created.id}")
                                .maxAge(7.days)
                                .httpOnly(true)
                                .sameSite(HttpCookie.SameSite.Lax)
                                .path("/")
                        )
                end if
            }

        val get = HttpRoute
            .getRaw("notes" / HttpPath.Capture[Int]("id"))
            .response(_.bodyJson[Note].error[NotFound](HttpStatus.NotFound))
            .handler { req =>
                storeRef.get.map { store =>
                    store.notes.get(req.fields.id) match
                        case Some(note) => HttpResponse.okJson(note)
                        case None       => Abort.fail(NotFound(s"Note ${req.fields.id} not found"))
                }
            }

        val patch = HttpRoute
            .patchRaw("notes" / HttpPath.Capture[Int]("id"))
            .request(_.bodyJson[PatchNote])
            .response(
                _.bodyJson[Note]
                    .error[NotFound](HttpStatus.NotFound)
                    .error[ValidationError](HttpStatus.BadRequest)
            )
            .handler { req =>
                val input = req.fields.body
                for
                    now <- Clock.now
                    store <- storeRef.updateAndGet { s =>
                        s.notes.get(req.fields.id) match
                            case Some(existing) =>
                                val updated = Note(
                                    existing.id,
                                    input.title.getOrElse(existing.title),
                                    input.content.getOrElse(existing.content),
                                    now.toString
                                )
                                Store(s.notes + (req.fields.id -> updated), s.nextId)
                            case None => s
                    }
                yield store.notes.get(req.fields.id) match
                    case Some(note) =>
                        changesRef.updateAndGet(NoteChange("updated", note) :: _)
                            .andThen(HttpResponse.okJson(note))
                    case None => Abort.fail(NotFound(s"Note ${req.fields.id} not found"))
                end for
            }

        val delete = HttpRoute
            .deleteRaw("notes" / HttpPath.Capture[Int]("id"))
            .response(_.status(HttpStatus.NoContent).error[NotFound](HttpStatus.NotFound))
            .handler { req =>
                storeRef.get.map { store =>
                    if !store.notes.contains(req.fields.id) then
                        Abort.fail(NotFound(s"Note ${req.fields.id} not found"))
                    else
                        storeRef.updateAndGet(s => Store(s.notes - req.fields.id, s.nextId))
                            .andThen(HttpResponse.noContent)
                }
            }

        val changes = HttpHandler.getSseJson[NoteChange]("notes/changes") { _ =>
            AtomicRef.init(0).map { lastSeenRef =>
                Stream[HttpEvent[NoteChange], Async] {
                    Loop.foreach {
                        for
                            _        <- Async.delay(1.second)(())
                            lastSeen <- lastSeenRef.get
                            all      <- changesRef.get
                            newChanges = all.take(all.size - lastSeen).reverse
                            _ <- lastSeenRef.set(all.size)
                        yield Emit.valueWith(Chunk.from(newChanges.map { c =>
                            HttpEvent(data = c, event = Present(c.kind))
                        }))(Loop.continue)
                    }
                }
            }
        }

        (list, create, get, patch, delete, changes)
    end handlers

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            storeRef   <- AtomicRef.init(Store(Map.empty, 1))
            changesRef <- AtomicRef.init(List.empty[NoteChange])
            (list, create, get, patch, delete, changes) = handlers(storeRef, changesRef)
            health                                      = HttpHandler.health()
            server <- HttpServer.init(
                HttpServer.Config().port(port).openApi("/openapi.json", "NotePad")
            )(list, create, get, patch, delete, changes, health)
            _ <- Console.printLine(s"NotePad running on http://localhost:${server.port}")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/notes -H "Content-Type: application/json" -d '{"title":"Hello","content":"My first note"}'"""
            )
            _ <- Console.printLine(
                s"""  curl -X PATCH http://localhost:${server.port}/notes/1 -H "Content-Type: application/json" -d '{"content":"Updated content"}'"""
            )
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/notes")
            _ <- Console.printLine(s"  curl -N http://localhost:${server.port}/notes/changes")
            _ <- server.await
        yield ()
        end for
    }
end NotePad

/** Client that exercises NotePad CRUD and consumes SSE changes.
  *
  * Demonstrates: HttpClient.getSseJson (client-side SSE consumption), HttpClient.patchJson, postJson, deleteJson with baseUrl config,
  * stream.take for bounded SSE consumption.
  */
object NotePadClient extends KyoApp:

    import NotePad.*

    run {
        for
            storeRef   <- AtomicRef.init(Store(Map.empty, 1))
            changesRef <- AtomicRef.init(List.empty[NoteChange])
            (list, create, get, patch, delete, changes) = handlers(storeRef, changesRef)
            server <- HttpServer.init(HttpServer.Config().port(0))(list, create, get, patch, delete, changes)
            _      <- Console.printLine(s"NotePadClient started server on http://localhost:${server.port}")

            _ <- HttpClient.withConfig(_.baseUrl(s"http://localhost:${server.port}").timeout(5.seconds)) {
                for
                    _  <- Console.printLine("\n=== Creating notes ===")
                    n1 <- HttpClient.postJson[Note]("/notes", CreateNote("Shopping", "Milk, eggs, bread"))
                    _  <- Console.printLine(s"  Created: ${n1.title} (id=${n1.id})")

                    n2 <- HttpClient.postJson[Note]("/notes", CreateNote("Ideas", "Learn Kyo effects"))
                    _  <- Console.printLine(s"  Created: ${n2.title} (id=${n2.id})")

                    _       <- Console.printLine("\n=== Patching note ===")
                    patched <- HttpClient.patchJson[Note]("/notes/1", PatchNote(None, Some("Milk, eggs, bread, butter")))
                    _       <- Console.printLine(s"  Patched: ${patched.title} -> ${patched.content}")

                    _   <- Console.printLine("\n=== Final list ===")
                    all <- HttpClient.getJson[List[Note]]("/notes")
                    _ <- Kyo.foreach(all) { n =>
                        Console.printLine(s"  [${n.id}] ${n.title}: ${n.content}")
                    }

                    _ <- Console.printLine("\n=== Consuming SSE changes ===")
                    // Changes were already recorded, SSE will deliver them on first poll
                    _ <- HttpClient.getSseJson[NoteChange](s"http://localhost:${server.port}/notes/changes").take(3).foreachChunk { chunk =>
                        Kyo.foreach(chunk.toSeq) { event =>
                            Console.printLine(s"  [SSE] ${event.data.kind}: ${event.data.note.title}")
                        }
                    }
                yield ()
            }

            _ <- server.closeNow
            _ <- Console.printLine("\nDone.")
        yield ()
        end for
    }
end NotePadClient
