package kyo.grpc

import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kyo.*

// TODO: Rename.
object ServerHandler:

    def unary[Request, Response: Flat](f: Request => Response < GrpcResponse)(using Frame): ServerCallHandler[Request, Response] =
        ServerCalls.asyncUnaryCall { (request, observer) =>
            // TODO: Is it safe to discard the Fiber here?
            IO.run(Async.run(Abort.run[StatusException](f(request)).map(completeObserver(observer, _)))).eval
        }

    // Adapted from scalapb.grpc.Grpc#completeObserver.
    private def completeObserver[Response](observer: StreamObserver[Response], result: Result[StatusException, Response]): Unit =
        // TODO: Why is there a partial match warning here?
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
