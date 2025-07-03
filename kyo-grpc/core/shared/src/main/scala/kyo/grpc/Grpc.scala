package kyo.grpc

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kyo.*
import kyo.Result.Panic
import kyo.scheduler.IOPromise
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.*

type Grpc = Async & Abort[Grpc.Errors]

object Grpc:

    type Errors = StatusException

    def fromFuture[Response](f: Future[Response])(using Frame): Response < Grpc =
        Fiber.fromFuture(f).map(_.getResult).map:
            case Success(response: Response) => response
            case Result.Error(ex: Errors) => Abort.fail(ex)
            case Result.Error(ex: StatusRuntimeException) => Abort.fail(StreamNotifier.throwableToStatusException(ex))
            case Result.Error(t) => Abort.panic(t)

    def run[Request, S](request: Request < (Grpc & S))(using Frame): Result[Errors, Request] < (Async & S) =
        Abort.run[StatusException](request)

end Grpc
