package kyo.grpc

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kyo.*
import kyo.scheduler.IOPromise
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.*

type GrpcRequest >: GrpcRequest.Effects <: GrpcRequest.Effects

object GrpcRequest:

    type Effects = Async & Abort[Exceptions]

    type Exceptions = StatusException | StatusRuntimeException

    def fromFuture[Response: Flat](f: Future[Response])(using Frame): Response < GrpcRequest =
        // TODO: Is there a better way to do this?
        for
            p <- IO {
                val p = new IOPromise[Exceptions, Response]()
                f.onComplete {
                    case Success(v) =>
                        p.complete(Result.success(v))
                    case Failure(ex: Exceptions) =>
                        p.complete(Result.fail(ex))
                    case Failure(ex) =>
                        p.complete(Result.panic(ex))
                }(ExecutionContext.parasitic)
                p
            }
            response <- Async.get(p)
        yield response
    end fromFuture

end GrpcRequest
