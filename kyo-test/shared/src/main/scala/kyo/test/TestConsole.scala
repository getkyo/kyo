package kyo.test

import java.io.EOFException
import java.io.IOException
// Assumed Kyo imports
import kyo.*

// Placeholder for Restorable and UnsafeAPI interfaces
trait UnsafeAPI:
    def print(line: Any): Unit
    def printError(line: Any): Unit
    def printLine(line: Any): Unit
    def printLineError(line: Any): Unit
    def readLine(): String
end UnsafeAPI

// Helper function placeholder
def withConsoleScoped[T](test: T): IO = Kyo.unit

trait TestConsole extends Console with Restorable:
    def clearInput: Unit < IO
    def clearOutput: Unit < IO
    def clearOutputErr: Unit < IO
    def debug[A](comp: A < IO): A < IO
    def feedLines(lines: String*): Unit < IO
    def output: Vector[String] < IO
    def outputErr: Vector[String] < IO
    def silent[A](comp: A < IO): A < IO

    def print(line: => Any): Unit < (IO & Abort[IOException])
    def printError(line: => Any): Unit < (IO & Abort[IOException])
    def printLine(line: => Any): Unit < (IO & Abort[IOException])
    def printLineError(line: => Any): Unit < (IO & Abort[IOException])

    def save: (Unit < IO) < IO

    val unsafe: UnsafeAPI
end TestConsole

object TestConsole extends Serializable:

    // Data holds the state of the TestConsole
    case class Data(input: List[String], output: Vector[String], errOutput: Vector[String])

    case class Test(
        consoleState: AtomicRef[TestConsole.Data],
        live: Live,
        annotations: Annotations,
        debugState: Local[Boolean]
    ) extends TestConsole:

        def clearInput: Unit < IO = consoleState.update(data => data.copy(input = List.empty))

        def clearOutput: Unit < IO = consoleState.update(data => data.copy(output = Vector.empty))

        def clearOutputErr: Unit < IO = consoleState.update(data => data.copy(errOutput = Vector.empty))

        def debug[A](comp: A < IO): A < IO = debugState.locally(true)(comp)

        def feedLines(lines: String*): Unit < IO =
            consoleState.update(data => data.copy(input = lines.toList ::: data.input))

        def output: Vector[String] < IO = consoleState.get.map(_.output)

        def outputErr: Vector[String] < IO = consoleState.get.map(_.errOutput)

        override def print(line: => Any): Unit < (IO & Abort[IOException]) =
            succeed(unsafe.print(line)) *>
                live.provide(Console.print(line)) // assuming when(_) is available as an extension
                    .when(debugState.get)

        override def printError(line: => Any): Unit < (IO & Abort[IOException]) =
            succeed(unsafe.printError(line)) *>
                live.provide(Console.printError(line)).when(debugState.get)

        override def printLine(line: => Any): Unit < (IO & Abort[IOException]) =
            annotations.annotate(TestAnnotation.output, Chunk(ConsoleIO.Output(line.toString))) *>
                succeed(unsafe.printLine(line)) *>
                live.provide(Console.printLine(line)).when(debugState.get)

        override def printLineError(line: => Any): Unit < (IO & Abort[IOException]) =
            Kyo.pure(unsafe.printLineError(line)) *>
                live.provide(Console.printLineError(line)).when(debugState.get)

        def save: (Unit < IO) < IO =
            for
                data <- consoleState.get
            yield consoleState.set(data)

        override val unsafe: UnsafeAPI = new UnsafeAPI:
            override def print(line: Any): Unit =
                consoleState.unsafe.update { data =>
                    Data(data.input, data.output :+ line.toString, data.errOutput)
                }
            override def printError(line: Any): Unit =
                consoleState.unsafe.update { data =>
                    Data(data.input, data.output, data.errOutput :+ line.toString)
                }
            override def printLine(line: Any): Unit =
                consoleState.unsafe.update { data =>
                    Data(data.input, data.output :+ s"$line\n", data.errOutput)
                }
            override def printLineError(line: Any): Unit =
                consoleState.unsafe.update { data =>
                    Data(data.input, data.output, data.errOutput :+ s"$line\n")
                }
            override def readLine(): String =
                consoleState.unsafe.modify { data =>
                    data.input match
                        case head :: tail => head -> Data(tail, data.output, data.errOutput)
                        case Nil          => throw new EOFException("There is no more input left to read")
                }
    end Test

    def make(data: Data, debug: Boolean = true): Layer[TestConsole, Live & Annotations] =
        Layer.scoped {
            for
                live        <- service[Live]
                annotations <- service[Annotations]
                ref         <- succeed(Var.unsafe.make(data))
                debugRef    <- Local.make(debug)
                test = Test(ref, live, annotations, debugRef)
                _ <- withConsoleScoped(test)
            yield test
        }

    val any: Layer[TestConsole, Any] =
        Layer.environment[TestConsole]

    val debug: Layer[TestConsole, Live & Annotations] =
        make(Data(Nil, Vector(), Vector()), true)

    val silent: Layer[TestConsole, Live & Annotations] =
        make(Data(Nil, Vector(), Vector()), false)

    def testConsoleWith[A](f: TestConsole => A): A < Env[TestConsole] =
        service[TestConsole].map(f)

    def clearInput: Unit < IO                = testConsoleWith(_.clearInput)
    def clearOutput: Unit < IO               = testConsoleWith(_.clearOutput)
    def debug[A](comp: A < IO): A < IO       = testConsoleWith(_.debug(comp))
    def feedLines(lines: String*): Unit < IO = testConsoleWith(_.feedLines(lines*))
    def output: Vector[String] < IO          = testConsoleWith(_.output)
    def outputErr: Vector[String] < IO       = testConsoleWith(_.outputErr)
    def save: (Unit < IO) < IO               = testConsoleWith(_.save)
    def silent[A](comp: A < IO): A < IO      = testConsoleWith(_.silent(comp))
end TestConsole
