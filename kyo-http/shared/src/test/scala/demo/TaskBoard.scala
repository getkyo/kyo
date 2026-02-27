package demo

import kyo.*

/** Kanban-style task board with rich error handling and typed client.
  *
  * Server: CRUD tasks with columns (todo/doing/done), assignment, and validation. Multiple typed error channels on routes return different
  * error shapes for different failure modes. Client: exercises all CRUD and demonstrates error recovery.
  *
  * Demonstrates: HttpHandler.putJson, HttpHandler.deleteJson (convenience), multiple error mappings on one route (400 + 404 + 409), OpenAPI
  * security metadata, rich typed error responses.
  */
object TaskBoard extends KyoApp:

    case class Task(id: Int, title: String, column: String, assignee: Option[String]) derives Schema
    case class CreateTask(title: String, column: Option[String], assignee: Option[String]) derives Schema
    case class UpdateTask(title: String, column: String, assignee: Option[String]) derives Schema

    case class NotFound(error: String) derives Schema
    case class ValidationError(error: String, field: String) derives Schema
    case class ConflictError(error: String, existingId: Int) derives Schema

    case class Store(tasks: Map[Int, Task], nextId: Int)

    val validColumns = Set("todo", "doing", "done")

    def handlers(storeRef: AtomicRef[Store]) =

        val list = HttpHandler.getJson[List[Task]]("tasks") { _ =>
            storeRef.get.map(_.tasks.values.toList.sortBy(_.id))
        }

        val create = HttpRoute
            .postRaw("tasks")
            .request(_.bodyJson[CreateTask])
            .response(
                _.bodyJson[Task]
                    .status(HttpStatus.Created)
                    .error[ValidationError](HttpStatus.BadRequest)
                    .error[ConflictError](HttpStatus.Conflict)
            )
            .metadata(_.copy(
                summary = Present("Create a new task"),
                tags = Seq("tasks"),
                security = Present("bearerAuth")
            ))
            .handler { req =>
                val input = req.fields.body
                if input.title.isBlank then Abort.fail(ValidationError("Title cannot be blank", "title"))
                else
                    val col = input.column.getOrElse("todo")
                    if !validColumns.contains(col) then
                        Abort.fail(ValidationError(s"Invalid column: $col. Must be one of: ${validColumns.mkString(", ")}", "column"))
                    else
                        storeRef.get.map { store =>
                            store.tasks.values.find(_.title == input.title) match
                                case Some(existing) =>
                                    Abort.fail(ConflictError(s"Task '${input.title}' already exists", existing.id))
                                case None =>
                                    storeRef.updateAndGet { s =>
                                        val task = Task(s.nextId, input.title, col, input.assignee)
                                        Store(s.tasks + (s.nextId -> task), s.nextId + 1)
                                    }.map(s => HttpResponse.okJson(s.tasks(s.nextId - 1)))
                        }
                    end if
                end if
            }

        val update = HttpRoute
            .putRaw("tasks" / HttpPath.Capture[Int]("id"))
            .request(_.bodyJson[UpdateTask])
            .response(
                _.bodyJson[Task]
                    .error[NotFound](HttpStatus.NotFound)
                    .error[ValidationError](HttpStatus.BadRequest)
            )
            .metadata(_.copy(
                summary = Present("Update a task"),
                tags = Seq("tasks"),
                security = Present("bearerAuth")
            ))
            .handler { req =>
                val input = req.fields.body
                if !validColumns.contains(input.column) then
                    Abort.fail(ValidationError(s"Invalid column: ${input.column}", "column"))
                else
                    storeRef.get.map { store =>
                        store.tasks.get(req.fields.id) match
                            case None =>
                                Abort.fail(NotFound(s"Task ${req.fields.id} not found"))
                            case Some(_) =>
                                val updated = Task(req.fields.id, input.title, input.column, input.assignee)
                                storeRef.updateAndGet(s => Store(s.tasks + (req.fields.id -> updated), s.nextId))
                                    .map(_ => HttpResponse.okJson(updated))
                    }
                end if
            }

        val delete = HttpRoute
            .deleteRaw("tasks" / HttpPath.Capture[Int]("id"))
            .response(_.status(HttpStatus.NoContent).error[NotFound](HttpStatus.NotFound))
            .metadata(_.copy(
                summary = Present("Delete a task"),
                tags = Seq("tasks"),
                security = Present("bearerAuth")
            ))
            .handler { req =>
                storeRef.get.map { store =>
                    if !store.tasks.contains(req.fields.id) then
                        Abort.fail(NotFound(s"Task ${req.fields.id} not found"))
                    else
                        storeRef.updateAndGet(s => Store(s.tasks - req.fields.id, s.nextId))
                            .andThen(HttpResponse.noContent)
                }
            }

        (list, create, update, delete)
    end handlers

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            storeRef <- AtomicRef.init(Store(Map.empty, 1))
            (list, create, update, delete) = handlers(storeRef)
            health                         = HttpHandler.health()
            server <- HttpServer.init(
                HttpServer.Config().port(port).openApi("/openapi.json", "TaskBoard")
            )(list, create, update, delete, health)
            _ <- Console.printLine(s"TaskBoard running on http://localhost:${server.port}")
            _ <- Console.printLine(
                s"""  curl -X POST http://localhost:${server.port}/tasks -H "Content-Type: application/json" -d '{"title":"Buy milk","assignee":"Alice"}'"""
            )
            _ <- Console.printLine(
                s"""  curl -X PUT http://localhost:${server.port}/tasks/1 -H "Content-Type: application/json" -d '{"title":"Buy milk","column":"doing","assignee":"Alice"}'"""
            )
            _ <- Console.printLine(s"  curl http://localhost:${server.port}/tasks")
            _ <- server.await
        yield ()
        end for
    }
end TaskBoard

/** Client that exercises TaskBoard CRUD and demonstrates typed error recovery.
  *
  * Demonstrates: HttpClient.postJson, HttpClient.putJson, HttpClient.getJson, HttpClient.deleteText, error recovery with Abort.run.
  */
object TaskBoardClient extends KyoApp:

    import TaskBoard.*

    run {
        for
            storeRef <- AtomicRef.init(Store(Map.empty, 1))
            (list, create, update, delete) = handlers(storeRef)
            server <- HttpServer.init(HttpServer.Config().port(0))(list, create, update, delete)
            _      <- Console.printLine(s"TaskBoardClient started server on http://localhost:${server.port}")

            baseUrl = s"http://localhost:${server.port}"

            // CRUD operations
            _ <- HttpClient.withConfig(_.baseUrl(baseUrl).timeout(5.seconds)) {
                for
                    _  <- Console.printLine("\n=== Creating tasks ===")
                    t1 <- HttpClient.postJson[Task, CreateTask]("/tasks", CreateTask("Design API", Some("todo"), Some("Alice")))
                    _  <- Console.printLine(s"  Created: [${t1.id}] ${t1.title} (${t1.column}, ${t1.assignee.getOrElse("unassigned")})")
                    t2 <- HttpClient.postJson[Task, CreateTask]("/tasks", CreateTask("Write tests", None, None))
                    _  <- Console.printLine(s"  Created: [${t2.id}] ${t2.title} (${t2.column}, ${t2.assignee.getOrElse("unassigned")})")

                    _       <- Console.printLine("\n=== Updating task (move to doing) ===")
                    updated <- HttpClient.putJson[Task, UpdateTask]("/tasks/1", UpdateTask("Design API", "doing", Some("Alice")))
                    _       <- Console.printLine(s"  Updated: [${updated.id}] ${updated.title} -> ${updated.column}")

                    _     <- Console.printLine("\n=== Listing all tasks ===")
                    tasks <- HttpClient.getJson[List[Task]]("/tasks")
                    _ <- Kyo.foreach(tasks) { t =>
                        Console.printLine(s"  [${t.id}] ${t.title} (${t.column}, ${t.assignee.getOrElse("unassigned")})")
                    }
                yield ()
            }

            // Error handling demos
            _ <- HttpClient.withConfig(_.baseUrl(baseUrl).timeout(5.seconds)) {
                for
                    _ <- Console.printLine("\n=== Error handling: duplicate title ===")
                    dupResult <- Abort.run[HttpError](
                        HttpClient.postJson[Task, CreateTask]("/tasks", CreateTask("Design API", None, None))
                    )
                    _ <- dupResult match
                        case Result.Success(t)  => Console.printLine(s"  Unexpected success: $t")
                        case Result.Error(fail) => Console.printLine(s"  Expected error: ${fail.getMessage}")
                        case Result.Panic(ex)   => Console.printLine(s"  Panic: ${ex.getMessage}")

                    _ <- Console.printLine("\n=== Error handling: invalid column ===")
                    badCol <- Abort.run[HttpError](
                        HttpClient.putJson[Task, UpdateTask]("/tasks/1", UpdateTask("Design API", "invalid", None))
                    )
                    _ <- badCol match
                        case Result.Success(t)  => Console.printLine(s"  Unexpected success: $t")
                        case Result.Error(fail) => Console.printLine(s"  Expected error: ${fail.getMessage}")
                        case Result.Panic(ex)   => Console.printLine(s"  Panic: ${ex.getMessage}")

                    _          <- Console.printLine("\n=== Deleting task ===")
                    _          <- HttpClient.deleteText("/tasks/2")
                    _          <- Console.printLine("  Deleted task 2")
                    finalTasks <- HttpClient.getJson[List[Task]]("/tasks")
                    _ <- Kyo.foreach(finalTasks) { t =>
                        Console.printLine(s"  [${t.id}] ${t.title} (${t.column})")
                    }
                yield ()
            }

            _ <- server.closeNow
            _ <- Console.printLine("\nDone.")
        yield ()
        end for
    }
end TaskBoardClient
