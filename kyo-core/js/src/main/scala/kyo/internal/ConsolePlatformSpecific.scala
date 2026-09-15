package kyo.internal

import java.io.EOFException
import java.io.IOException
import kyo.Absent
import kyo.AllowUnsafe
import kyo.Present
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
  * throw that no handler can convert into a clean shutdown. A host without Node's `fs` module, such as a browser, has no standard input to
  * read, and gets the same kind of failure.
  */
private[kyo] object ConsolePlatformSpecific:

    /** Read one line from standard input, without its line terminator. Fails with `EOFException` at end of input. */
    def readLine()(using AllowUnsafe): Result[IOException, String] =
        CoreNodeFs.module match
            case Absent =>
                Result.fail(new IOException(
                    s"Console.readLine needs Node's fs module to read standard input (Node, Bun or Deno); this host is ${Platform.host}"
                ))
            case Present(fs) =>
                Result.catching[Throwable](NodeLineReader.stdin.readLine(fs))
                    .mapFailure(cause => new IOException(s"Console.readLine could not read standard input: $cause", cause))
                    .flatMap(_.toResult(Result.fail(new EOFException("Console.readLine reached the end of standard input."))))

end ConsolePlatformSpecific
