package kyo.internal

import java.io.EOFException
import java.io.IOException
import kyo.AllowUnsafe
import kyo.Result

/** The JS and Wasm half of `Console.live`'s standard-input read.
  *
  * Node has no `System.in`, so `scala.Console.in` is `null` there and the JVM's `scala.Console.in.readLine()` throws. On a Scala.js build
  * that surfaced as `UndefinedBehaviorError: java.lang.NullPointerException`, and on Wasm as an exception Node printed as
  * `[Object: null prototype] {}` with no name and no stack. Either way a program reading a line died the moment it read one, and a stdio
  * server looked to its host like a transport that had hung rather than one that had failed.
  *
  * [[NodeLineReader]] supplies the read, over `fs.readSync` on descriptor 0. Every failure is reported as an `IOException` carrying the
  * underlying error, which is what keeps it inside the `Abort[IOException]` `Console.readLine` declares instead of escaping as a raw JS
  * throw that no handler can convert into a clean shutdown.
  */
private[kyo] object ConsolePlatformSpecific:

    /** Read one line from standard input, without its line terminator. Fails with `EOFException` at end of input. */
    def readLine()(using AllowUnsafe): Result[IOException, String] =
        Result.catching[Throwable](NodeLineReader.stdin.readLine())
            .mapFailure(cause => new IOException(s"Console.readLine could not read standard input: $cause", cause))
            .flatMap(_.toResult(Result.fail(new EOFException("Console.readLine reached the end of standard input."))))

end ConsolePlatformSpecific
