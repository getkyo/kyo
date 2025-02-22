package zio.test.results

import java.io.IOException
import zio.*

private[test] trait ResultFileOps:
    def write(content: => String, append: Boolean): ZIO[Any, IOException, Unit]

private[test] object ResultFileOps:
    val live: ZLayer[Any, Nothing, ResultFileOps] =
        ZLayer.succeed(
            Json()
        )

    private[test] case class Json() extends ResultFileOps:
        def write(content: => String, append: Boolean): ZIO[Any, IOException, Unit] =
            ZIO.unit
end ResultFileOps
