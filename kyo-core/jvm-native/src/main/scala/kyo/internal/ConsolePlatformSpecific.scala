package kyo.internal

import java.io.EOFException
import java.io.IOException
import kyo.AllowUnsafe
import kyo.Maybe
import kyo.Result

/** The JVM and Native half of `Console.live`'s standard-input read.
  *
  * Both platforms have a real `System.in`, so this is `scala.Console.in.readLine()` and the buffering behind it. Node has neither, which is
  * why the read is platform-specific at all; see the `js-wasm` sibling.
  */
private[kyo] object ConsolePlatformSpecific:

    /** Read one line from standard input, without its line terminator. Fails with `EOFException` at end of input. */
    def readLine()(using AllowUnsafe): Result[IOException, String] =
        Result.catching[IOException](Maybe(scala.Console.in.readLine()))
            .flatMap(_.toResult(Result.fail(new EOFException("Console.readLine reached the end of standard input."))))

end ConsolePlatformSpecific
