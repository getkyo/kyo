# kyo-case-app

Bridge [case-app](https://github.com/alexarchambault/case-app) command-line parsing with Kyo application entrypoints. Use **`KyoCaseApp`** for single-command apps and **`KyoCommand`** for subcommands grouped via case-app's [`CommandsEntryPoint`](https://github.com/alexarchambault/case-app).

Register effectful work with **`run`** after case-app parses the command line. Unlike [`KyoApp`](https://github.com/getkyo/kyo), there are no implicit `options` / `remainingArgs` accessors — parsed data is passed explicitly via overloads:

| Form | When to use |
|------|-------------|
| `run { (options, remainingArgs) => ... }` | You need options and leftover positionals ([`RemainingArgs`](https://github.com/alexarchambault/case-app/blob/main/core/shared/src/main/scala/caseapp/core/RemainingArgs.scala)) |
| `run { options => ... }` | You need parsed options only |
| `run { ... }` | The effect does not use parsed CLI data (startup hooks, etc.) |

All three overloads share one registration queue: multiple `run` blocks run in object-initialization order, and each block sees the same parse result from that `main` invocation.

case-app handles help, usage, and argument parsing; this module runs your effects after parsing completes. For how to define options (annotations, defaults, subcommand metadata, etc.), see the [case-app documentation](https://alexarchambault.github.io/case-app/) — that is not repeated here.

Works on JVM, Scala.js, and Scala Native.

## Getting Started

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.getkyo" %%% "kyo-case-app" % "<latest version>"
```

You also need a case-app dependency if you use its annotations or helpers directly:

```scala
libraryDependencies += "com.github.alexarchambault" %%% "case-app" % "2.1.0"
```

## `KyoCaseApp` — single command

Define a case class for options (see [case-app](https://alexarchambault.github.io/case-app/) for field annotations and parsers), then extend `KyoCaseApp`:

```scala
import caseapp.*
import kyo.*

final case class GreetOptions(
    @Name("name") name: String = "world"
)

object Greet extends KyoCaseApp[GreetOptions]:
    run { (options, remainingArgs) =>
        Console.printLine(s"Hello, ${options.name}!")
    }
```

`Greet` already inherits `main(args: Array[String])` from case-app. The `run` block does not replace `main` — it registers the Kyo effects that `main` runs after parsing CLI arguments (the same relationship as `run` on `KyoApp`).

Point your build at that object as the main class, for example in sbt:

```scala
Compile / mainClass := Some("Greet")
```

Then `run` / `assembly` / your packager invokes `Greet.main` directly. You do not need a separate `@main` method that forwards to `Greet.main(args)`.

During development, pass CLI args to sbt after `--` (everything after `--` is forwarded to `main`):

```bash
sbt run -- --name Alice
# Hello, Alice!

sbt run -- Bob extra-arg
# Hello, Bob!
# remainingArgs.remaining == Seq("extra-arg")
```

You can also name the main class explicitly:

```bash
sbt "runMain Greet -- --name Alice"
```

With a packaged binary (for example after `assembly` / `nativeImage` / `scala-cli --assembly`), invoke the artifact directly:

```bash
./greet --name Alice
# Hello, Alice!

./greet Bob extra-arg
# Hello, Bob!
```

You can mix overloads in one app; registration order is preserved:

```scala
object Greet extends KyoCaseApp[GreetOptions]:
    run { Console.printLine("starting") }                    // no CLI params
    run { options => Console.printLine(options.name) }       // options only
    run { (options, remainingArgs) =>                       // full parse result
        Console.printLine(s"${options.name} ${remainingArgs.remaining.mkString(" ")}")
    }
```

## `KyoCommand` — subcommands

Each subcommand is a **`object`** extending `KyoCommand`. Group them with case-app's `CommandsEntryPoint`:

```scala
import caseapp.*
import caseapp.core.app.CommandsEntryPoint
import kyo.*

enum TodoStatus:
    case Pending, Active, Completed

final case class Todo(id: Int, title: String, status: TodoStatus)

final case class CreateOptions(@Name("title") title: Option[String] = None)
final case class IdOptions(@Name("id") id: Option[Int] = None)
final case class ListOptions(@Name("all") all: Boolean = false)

object TodoApp extends CommandsEntryPoint:

    private val store =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init(Chunk.empty[Todo])

    override def progName: String = "todo"
    def commands                  = Seq(Create, Complete, List, Delete, Start)

    object Create extends KyoCommand[CreateOptions]:
        override def name = "create"
        run { (options, remainingArgs) =>
            val title = options.title.orElse(remainingArgs.remaining.headOption).getOrElse {
                throw new IllegalArgumentException("create requires --title or a positional title")
            }
            for
                todos <- store.get
                id   = todos.map(_.id).maxOption.getOrElse(0) + 1
                _ <- store.set(todos.appended(Todo(id, title, TodoStatus.Pending)))
                _ <- Console.printLine(s"created #$id: $title")
            yield ()
        }

    object Complete extends KyoCommand[IdOptions]:
        override def name = "complete"
        run { (options, remainingArgs) =>
            val id = options.id.orElse(remainingArgs.remaining.headOption.flatMap(_.toIntOption)).get
            for
                todos <- store.get
                todo  <- todos.find(_.id == id).map(Sync.defer(_)).getOrElse(Abort.fail(new NoSuchElementException(s"no todo #$id")))
                _ <- store.set(todos.map(t => if t.id == id then t.copy(status = TodoStatus.Completed) else t))
                _ <- Console.printLine(s"completed #$id: ${todo.title}")
            yield ()
        }

    object List extends KyoCommand[ListOptions]:
        override def name = "list"
        run { options =>
            for
                todos   <- store.get
                visible <- Sync.defer(if options.all then todos else todos.filter(t => t.status ne TodoStatus.Completed))
                _ <- if visible.isEmpty then Console.printLine("no todos")
                     else Async.foreachDiscard(visible)(t => Console.printLine(render(t)))
            yield ()
        }

    object Delete extends KyoCommand[IdOptions]:
        override def name = "delete"
        run { (options, remainingArgs) =>
            val id = options.id.orElse(remainingArgs.remaining.headOption.flatMap(_.toIntOption)).get
            for
                todos <- store.get
                todo  <- todos.find(_.id == id).map(Sync.defer(_)).getOrElse(Abort.fail(new NoSuchElementException(s"no todo #$id")))
                _ <- store.set(todos.filterNot(_.id == id))
                _ <- Console.printLine(s"deleted #$id: ${todo.title}")
            yield ()
        }

    object Start extends KyoCommand[IdOptions]:
        override def name = "start"
        run { (options, remainingArgs) =>
            val id = options.id.orElse(remainingArgs.remaining.headOption.flatMap(_.toIntOption)).get
            for
                todos <- store.get
                todo  <- todos.find(_.id == id).map(Sync.defer(_)).getOrElse(Abort.fail(new NoSuchElementException(s"no todo #$id")))
                _ <-
                    if todo.status eq TodoStatus.Active then
                        Console.printLine(s"todo #$id already active")
                    else if todo.status eq TodoStatus.Completed then
                        Abort.fail(new IllegalStateException(s"already completed"))
                    else
                        store.set(todos.map(t => if t.id == id then t.copy(status = TodoStatus.Active) else t))
                            .andThen(Console.printLine(s"started #$id: ${todo.title}"))
            yield ()
        }

    private def render(todo: Todo): String =
        val mark =
            if todo.status eq TodoStatus.Pending then "[ ]"
            else if todo.status eq TodoStatus.Active then "[~]"
            else if todo.status eq TodoStatus.Completed then "[x]"
            else "[?]"
        s"$mark #${todo.id} ${todo.title}"
```

`TodoApp` likewise inherits `main` from `CommandsEntryPoint`. Set `Compile / mainClass := Some("TodoApp")` in sbt.

During development:

```bash
sbt run -- create --title "Buy milk"
sbt run -- create "Walk dog"
sbt run -- start 1
sbt run -- list
# [~] #1 Buy milk
# [ ] #2 Walk dog

sbt run -- complete 2
sbt run -- list --all
```

Or with an explicit main class:

```bash
sbt "runMain TodoApp -- create --title Buy milk"
```

With a packaged binary:

```bash
./todo create --title "Buy milk"
./todo create "Walk dog"
./todo start 1
./todo list
# [~] #1 Buy milk
# [ ] #2 Walk dog

./todo complete 2
./todo list --all
```

The test suite includes a runnable variant of this app in [`casetest.TodoAppFixture`](shared/src/test/scala/casetest/TodoAppFixture.scala) (store initialized via `TodoAppFixture.init` for tests).

## API summary

| Type | Extends | Use when |
|------|---------|----------|
| [`KyoCaseApp[T]`](shared/src/main/scala/kyo/KyoCaseApp.scala) | `caseapp.CaseApp[T]` | One options type, one entrypoint |
| [`KyoCommand[T]`](shared/src/main/scala/kyo/KyoCommand.scala) | `caseapp.core.app.Command[T]` | Subcommand with its own options type |

Both provide three `run` overloads (same names, resolved by the shape of the block):

- `run { (options, remainingArgs) => ... }` — full parse result
- `run { options => ... }` — parsed options only
- `run { ... }` — effect without parsed CLI data

All delegate to a single `registerRun` queue so mixed overloads keep registration order.

Non-throwable failures call `exitApp(1)` (case-app already defines `exit` for its own use). Interrupt handling matches `KyoApp` (SIGINT/SIGTERM on non-Windows platforms).

## Related

- [case-app](https://alexarchambault.github.io/case-app/) — option definitions, help, completions
- [KyoApp](https://github.com/getkyo/kyo) — raw `args` without a CLI parser
