package casetest

import caseapp.*
import caseapp.core.RemainingArgs
import caseapp.core.app.Command
import caseapp.core.app.CommandsEntryPoint
import kyo.*

enum TodoStatus:
    case Pending, Active, Completed

final case class Todo(id: Int, title: String, status: TodoStatus)

final case class CreateOptions(
    @Name("title") title: Option[String] = None
)

final case class IdOptions(
    @Name("id") id: Option[Int] = None
)

final case class ListOptions(
    @Name("all") all: Boolean = false
)

/** In-memory todo CLI demonstrating [[kyo.KyoCommand]] subcommands with a shared store.
  *
  * Subcommands: `create`, `complete`, `list`, `delete`, `start`.
  */
final class TodoAppFixture private (val store: AtomicRef[Chunk[Todo]])

object TodoAppFixture:

    private var _store: AtomicRef[Chunk[Todo]] | Null = null

    private def store: AtomicRef[Chunk[Todo]] =
        val ref = _store
        if ref eq null then throw IllegalStateException("TodoAppFixture.init must be called first")
        else ref
    end store

    private def requireId(options: IdOptions, remaining: RemainingArgs): Int < (Sync & Abort[Throwable]) =
        options.id
            .orElse(remaining.remaining.headOption.flatMap(_.toIntOption))
            .map(id => Sync.defer(id))
            .getOrElse(Abort.fail[Throwable](new IllegalArgumentException("command requires --id or a positional id")))

    object Create extends KyoCommand[CreateOptions]:
        override def name = "create"
        run {
            options.title.orElse(remainingArgs.remaining.headOption) match
                case None =>
                    Abort.fail(new IllegalArgumentException("create requires a title (--title or positional)"))
                case Some(title) =>
                    for
                        todos <- store.get
                        id   = todos.map(_.id).maxOption.getOrElse(0) + 1
                        todo = Todo(id, title, TodoStatus.Pending)
                        _ <- store.set(todos.appended(todo))
                        _ <- Console.printLine(s"created #$id: $title")
                    yield ()
        }
    end Create

    object Complete extends KyoCommand[IdOptions]:
        override def name = "complete"
        run {
            for
                id    <- requireId(options, remainingArgs)
                todos <- store.get
                todo <- todos.find(_.id == id) match
                    case None =>
                        Abort.fail(new NoSuchElementException(s"no todo #$id"))
                    case Some(value) =>
                        Sync.defer(value)
                _ <-
                    if todo.status eq TodoStatus.Completed then
                        Console.printLine(s"todo #$id already completed")
                    else
                        store.set(todos.map(t => if t.id == id then t.copy(status = TodoStatus.Completed) else t))
                            .andThen(Console.printLine(s"completed #$id: ${todo.title}"))
            yield ()
        }
    end Complete

    object List extends KyoCommand[ListOptions]:
        override def name = "list"
        run {
            for
                todos <- store.get
                visible <- Sync.defer {
                    if options.all then todos
                    else todos.filter(t => t.status ne TodoStatus.Completed)
                }
                _ <-
                    if visible.isEmpty then Console.printLine("no todos")
                    else Async.foreachDiscard(visible)(t => Console.printLine(render(t)))
            yield ()
        }
    end List

    object Delete extends KyoCommand[IdOptions]:
        override def name = "delete"
        run {
            for
                id    <- requireId(options, remainingArgs)
                todos <- store.get
                todo <- todos.find(_.id == id) match
                    case None =>
                        Abort.fail(new NoSuchElementException(s"no todo #$id"))
                    case Some(value) =>
                        Sync.defer(value)
                _ <- store.set(todos.filterNot(_.id == id))
                    .andThen(Console.printLine(s"deleted #$id: ${todo.title}"))
            yield ()
        }
    end Delete

    object Start extends KyoCommand[IdOptions]:
        override def name = "start"
        run {
            for
                id    <- requireId(options, remainingArgs)
                todos <- store.get
                todo <- todos.find(_.id == id) match
                    case None =>
                        Abort.fail(new NoSuchElementException(s"no todo #$id"))
                    case Some(value) =>
                        Sync.defer(value)
                _ <-
                    if todo.status eq TodoStatus.Active then
                        Console.printLine(s"todo #$id already active")
                    else if todo.status eq TodoStatus.Completed then
                        Abort.fail(new IllegalStateException(s"todo #$id is already completed"))
                    else
                        store.set(todos.map(t => if t.id == id then t.copy(status = TodoStatus.Active) else t))
                            .andThen(Console.printLine(s"started #$id: ${todo.title}"))
            yield ()
        }
    end Start

    val entryPoint: CommandsEntryPoint = new CommandsEntryPoint:
        def progName: String             = "todo"
        override def description: String = "A small todo CLI demonstrating kyo-case-app"
        override def summaryDesc         = "create | complete | list | delete | start"
        def commands: Seq[Command[?]]    = Seq(Create, Complete, List, Delete, Start)

    def render(todo: Todo): String =
        val mark =
            if todo.status eq TodoStatus.Pending then "[ ]"
            else if todo.status eq TodoStatus.Active then "[~]"
            else if todo.status eq TodoStatus.Completed then "[x]"
            else "[?]"
        s"$mark #${todo.id} ${todo.title}"
    end render

    def init: TodoAppFixture < (Async & Scope & Abort[Throwable]) =
        AtomicRef.init(Chunk.empty[Todo]).map { ref =>
            _store = ref
            new TodoAppFixture(ref)
        }

end TodoAppFixture
