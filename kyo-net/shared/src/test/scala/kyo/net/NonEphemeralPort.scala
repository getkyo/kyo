package kyo.net

import kyo.*

/** Binds a test listener to a port below every platform's ephemeral range (Linux starts at 32768, macOS and Windows at 49152), for a test
  * that closes the listener and then needs that port back: re-binding it, or expecting a connect to it to be refused.
  *
  * A port from port 0 is ephemeral, so once its listener closes, any `bind(0)` or outbound connect on the host can be handed it before the
  * test uses it again, and the re-bind fails as address in use although the listener did release it. A port outside the range is never
  * handed out by the OS, so the one assumption left is that nothing else binds the same fixed port in that window.
  *
  * A port the first bind cannot take only retries the choice, up to 100 times: one in use, or one Windows reserves (Hyper-V and WinNAT
  * exclusions fail with EACCES). Whatever the test asserts after that first bind is not retried.
  */
object NonEphemeralPort:

    /** Runs `bind` with a non-ephemeral port, choosing another while `retry` accepts the failure. */
    def bind[A, E, S](retry: E => Boolean)(bind: Int => A < (Async & Abort[E] & S))(using
        ConcreteTag[E],
        Frame
    ): A < (Async & Abort[E] & S) =
        Loop(0) { attempt =>
            val port = 20000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(12000)
            Abort.run[E](bind(port)).map {
                case Result.Success(a)                              => Loop.done(a)
                case Result.Failure(e) if retry(e) && attempt < 100 => Loop.continue(attempt + 1)
                case other                                          => Abort.get(other).map(Loop.done)
            }
        }
end NonEphemeralPort
