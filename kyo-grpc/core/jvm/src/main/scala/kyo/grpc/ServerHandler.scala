package kyo.grpc

import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kyo.*
import scala.util.Try

// TODO: Rename.
object ServerHandler:

    def unary[Request, Response: Flat](f: Request => Response < GrpcResponse)(using Frame): ServerCallHandler[Request, Response] =
        ServerCalls.asyncUnaryCall { (request, observer) =>
            IO.run(Async.run(f(request)).flatMap { fiber =>
                fiber.onComplete(completeObserver(observer))
            }).eval
        }

    // Adapted from scalapb.grpc.Grpc#completeObserver.
    private def completeObserver[Response](observer: StreamObserver[Response])(result: Result[StatusException, Response]): Unit =
        result.map(observer.onNext) match
            case Result.Success(_) =>
                observer.onCompleted()
            case Result.Fail(s: StatusException) =>
                observer.onError(s)
            case Result.Panic(e) =>
                observer.onError(
                    Status.INTERNAL.withDescription(e.getMessage).withCause(e).asException()
                )

end ServerHandler
