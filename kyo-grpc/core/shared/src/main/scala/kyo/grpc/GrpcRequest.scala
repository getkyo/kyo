package kyo.grpc

import io.grpc.{StatusException, StatusRuntimeException}
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
        // TODO: Is there a better way to do this?
        for
            p <- IO {
                val p = new IOPromise[Errors, Response]()
                f.onComplete {
                    case Success(v) =>
                        p.complete(Result.succeed(v))
                    case Failure(ex: Errors) =>
                        p.complete(Result.fail(ex))
                    case Failure(ex: StatusRuntimeException) =>
                        p.complete(Result.fail(StreamNotifier.throwableToStatusException(ex)))
                    case Failure(ex) =>
                        p.complete(Result.panic(ex))
                }(using ExecutionContext.parasitic)
                p
            }
            response <- Async.get(p)
        yield response
    end fromFuture

    def run[Request, S](request: Request < (GrpcRequest & S))(using Frame): Result[Errors, Request] < (Async & S) =
        Abort.run[StatusException](request)

end GrpcRequest
