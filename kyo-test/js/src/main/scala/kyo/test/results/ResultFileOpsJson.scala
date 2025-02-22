package kyo.test.results

import java.io.IOException
import kyo.*

private[test] trait ResultFileOps:
    def write(content: => String, append: Boolean): Unit < (Env[Any] & Abort[IOException])

private[test] object ResultFileOps:
    val live: Layer[ResultFileOps, Any] =
        Layer(
            Json()
        )

    private[test] case class Json() extends ResultFileOps:
        def write(content: => String, append: Boolean): Unit < (Env[Any] & Abort[IOException]) =
            ()
end ResultFileOps
