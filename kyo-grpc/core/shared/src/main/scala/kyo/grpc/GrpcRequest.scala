package kyo.grpc

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kyo.*
import kyo.scheduler.IOPromise
import kyo.scheduler.top.Status
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.*

type GrpcRequest = Async & Abort[GrpcRequest.Errors]

object GrpcRequest:

    type Errors = StatusException | StatusRuntimeException

    def fromFuture[Response: Flat](f: Future[Response])(using Frame): Response < GrpcRequest =
        // TODO: Is there a better way to do this?
        for
            p <- IO {
                val p = new IOPromise[Errors, Response]()
                f.onComplete {
                    case Success(v) =>
                        p.complete(Result.success(v))
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

end GrpcRequest
