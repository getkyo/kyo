package kyo.grpc

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kyo.*
import kyo.Result.Panic
import kyo.scheduler.IOPromise
import kyo.scheduler.top.Status
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.*
import scala.util.chaining.scalaUtilChainingOps

type GrpcRequest = Async & Abort[GrpcRequest.Errors]

object GrpcRequest:

    // TODO: We should really get rid of StatusRuntimeException from here and combine the two effects.
    type Errors = StatusException | StatusRuntimeException

    def fromFuture[Response: Flat](f: Future[Response])(using Frame): Response < GrpcRequest =
        // TODO: Is there a better way to do this?
        for
            p <- IO {
                val p = new IOPromise[Errors, Response]()
                f.onComplete {
                    case Success(v) =>
                        p.complete(Result.succeed(v))
                    case Failure(ex: Errors) =>
                        p.complete(Result.fail(ex))
                    case Failure(ex) =>
                        p.complete(Result.panic(ex))
                }(ExecutionContext.parasitic)
                p
            }
            response <- Async.get(p)
        yield response
    end fromFuture

    def run[Request: Flat, S](request: Request < (GrpcRequest & S))(using Frame): Result[Errors, Request] < (Async & S) =
        Abort.run[StatusRuntimeException](Abort.run[StatusException](request)).map(_.flatten)

    def mergeErrors[Request: Flat, S](request: Request < (GrpcRequest & S))(using Frame): Request < (Async & Abort[StatusException] & S) =
        // TODO: This ought to be easier (still).
        Abort.run[StatusRuntimeException](request).map(_.foldFailureOrThrow[Request < Abort[StatusException]]({ ex =>
            Abort.fail(StatusException(ex.getStatus, ex.getTrailers).tap(_.setStackTrace(ex.getStackTrace)))
        })(identity))

end GrpcRequest
