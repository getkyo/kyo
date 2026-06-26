package demo

import kyo.*

/** TaskTracker: a stateful task-list MCP server.
  *
  * Holds an in-memory task list in an `AtomicRef` and exposes it two ways at once:
  *
  *   - **Tools** mutate it: `add-task`, `list-tasks`, `complete-task`, `delete-task`. A typed
  *     `TaskNotFound` is mapped to a wire error via `.error`, so a bad id is a structured failure,
  *     not a panic.
  *   - **A subscribable resource** (`tasks://board`, `subscribe = true`) renders it. After every
  *     mutation the handler calls `Mcp.server.notifyResourceUpdated`, so a subscribed host re-reads
  *     the board without polling.
  *
  * Run as a stdio MCP server: `java -cp <kyo-mcpJVM test classpath> demo.TaskTracker`.
  */
object TaskTracker extends KyoApp:

    case class Task(id: Int, title: String, done: Boolean) derives Schema, CanEqual
    case class AddIn(title: String) derives Schema, CanEqual
    case class IdIn(id: Int) derives Schema, CanEqual
    case class TaskList(tasks: Chunk[Task]) derives Schema, CanEqual
    case class Ack(ok: Boolean, message: String) derives Schema, CanEqual
    case class TaskNotFound(id: Int) derives Schema, CanEqual

    private val boardUri = McpResourceUri("tasks://board")

    run {
        for
            state     <- AtomicRef.init(Chunk.empty[Task])
            nextId    <- AtomicInt.init(1)
            transport <- JsonRpcTransport.stdio()
            served <-
                val touch: Unit < (Async & Abort[McpConnectionClosedException]) =
                    Mcp.server.map(_.notifyResourceUpdated(boardUri))

                val add =
                    McpHandler.tool[AddIn]("add-task", "Add a task to the board") { in =>
                        for
                            id <- nextId.getAndUpdate(_ + 1)
                            t = Task(id, in.title, false)
                            _ <- state.updateAndGet(_.append(t))
                            _ <- touch
                        yield t
                    }

                val list =
                    McpHandler.tool[Unit]("list-tasks", "List every task on the board") { _ =>
                        state.get.map(TaskList(_))
                    }

                val complete =
                    McpHandler.tool[IdIn]("complete-task", "Mark a task done by id") { in =>
                        state.get.map { tasks =>
                            if !tasks.exists(_.id == in.id) then Abort.fail(TaskNotFound(in.id))
                            else
                                state.updateAndGet(_.map(t => if t.id == in.id then t.copy(done = true) else t))
                                    .andThen(touch)
                                    .andThen(Ack(true, s"task ${in.id} completed"))
                        }
                    }.error[TaskNotFound](-40010, "task not found")

                val delete =
                    McpHandler.tool[IdIn]("delete-task", "Delete a task by id") { in =>
                        state.get.map { tasks =>
                            if !tasks.exists(_.id == in.id) then Abort.fail(TaskNotFound(in.id))
                            else
                                state.updateAndGet(_.filter(_.id != in.id))
                                    .andThen(touch)
                                    .andThen(Ack(true, s"task ${in.id} deleted"))
                        }
                    }.error[TaskNotFound](-40010, "task not found")

                val board =
                    McpHandler.resource(boardUri, "Task board", "The current task list, one task per line", subscribe = true) {
                        state.get.map { tasks =>
                            val rendered =
                                if tasks.isEmpty then "(no tasks yet)"
                                else tasks.map(t => s"[${if t.done then "x" else " "}] #${t.id} ${t.title}").mkString("\n")
                            Chunk(McpHandler.ResourceBody.text(rendered))
                        }
                    }

                McpServer.initWith(transport, add, list, complete, delete, board)(_ => Async.never)
        yield served
    }
end TaskTracker
