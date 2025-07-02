package kyo.grpc

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kyo.*
import kyo.Result.Panic
import kyo.scheduler.IOPromise
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.*
import scala.util.chaining.scalaUtilChainingOps

type GrpcRequest = Async & Abort[GrpcRequest.Errors]

object GrpcRequest:

    type Errors = StatusException

    def fromFuture[Response](f: Future[Response])(using Frame): Response < GrpcRequest =
        Fiber.fromFuture(f).map(_.getResult).map:
            case Success(response: Response) => response
            case Result.Error(ex: Errors) => Abort.fail(ex)
            case Result.Error(ex: StatusRuntimeException) => Abort.fail(StreamNotifier.throwableToStatusException(ex))
            case Result.Error(t) => Abort.panic(t)

    def run[Request, S](request: Request < (GrpcRequest & S))(using Frame): Result[Errors, Request] < (Async & S) =
        Abort.run[StatusException](request)

end GrpcRequest
