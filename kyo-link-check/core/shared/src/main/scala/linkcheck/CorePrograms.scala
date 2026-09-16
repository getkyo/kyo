package linkcheck

// Outside the kyo package, as an application is.
import kyo.*

/** The smallest kyo-core program: one suspended computation, evaluated. */
object CoreMin:
    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        println(Sync.Unsafe.evalOrThrow(Sync.defer(42)))
end CoreMin

/** A program that reads the clock, logs, and prints a timestamp: the path from `Clock` and `Log` through `Instant.show`. */
object CoreLog:
    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val shown = Sync.Unsafe.evalOrThrow(Clock.now.map(now => Log.info(s"now ${now.show}").andThen(now.show)))
        println(shown)
    end main
end CoreLog

/** The smallest `KyoApp`: the entry point an application starts from, running one print.
  *
  * It sits between [[CoreMin]], which runs a computation with no runtime around it, and [[CoreReadLine]], which is a `KyoApp` that also
  * reads standard input. What the three sizes separate is how much of a link is the application runtime and how much is the program.
  */
object CoreApp extends KyoApp:
    run {
        Console.printLine("app")
    }
end CoreApp

/** Reads a line from standard input (empty when `linkCheck` runs it): the path from `Console.readLine` to Node's stdin. */
object CoreReadLine extends KyoApp:
    run {
        Abort.run[Any](Console.readLine).map(result => Console.printLine(Report(result)))
    }
end CoreReadLine

/** How the programs that reach Node report: the value on success, otherwise the kind of failure. On a host without Node the report must be
  * a typed or explicit failure, never a `ReferenceError` or a module load error.
  */
object Report:
    def apply[A](result: Result[Any, A]): String =
        result match
            case Result.Success(value) => value.toString
            case Result.Failure(error) => s"failure ${error.getClass.getSimpleName}"
            case Result.Panic(error)   => s"panic ${error.getClass.getSimpleName}"
end Report
