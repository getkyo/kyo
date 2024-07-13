package kyo.grpc

import io.grpc.{ServerCallHandler, Status, StatusException, StatusRuntimeException}
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.IOs

import scala.util.Try

object ServerHandler:

    def unary[Request, Response: Flat](f: Request => Response < GrpcResponses): ServerCallHandler[Request, Response] =
        ServerCalls.asyncUnaryCall((request, observer) =>
            IOs.run(
                for {
                    fiber <- GrpcResponses.init(f(request))
                    response <- fiber.onComplete { reply =>
                        IOs.attempt(reply).map(completeObserver(observer))
                    }
                } yield response
            )
        )

    // Copied from scalapb.grpc.Grpc#completeObserver.
    private def completeObserver[T](observer: StreamObserver[T])(t: Try[T]): Unit =
        t.map(observer.onNext) match
            case scala.util.Success(_) =>
                observer.onCompleted()
            case scala.util.Failure(s: StatusException) =>
                observer.onError(s)
            case scala.util.Failure(s: StatusRuntimeException) =>
                observer.onError(s)
            case scala.util.Failure(e) =>
                observer.onError(
                    Status.INTERNAL.withDescription(e.getMessage).withCause(e).asException()
                )

end ServerHandler
