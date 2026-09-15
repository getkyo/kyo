package linkcheck

// Outside the kyo package, as an application is.
import kyo.*

/** Writes and reads back a temporary file: the path from `Path` to Node's file system. */
object SystemPath extends KyoApp:
    run {
        Abort.run[Any] {
            Path.run {
                Scope.run {
                    Path.temp("linkcheck").map(path => path.write("kyo").andThen(path.read))
                }
            }
        }.map(result => Console.printLine(Report(result)))
    }
end SystemPath
