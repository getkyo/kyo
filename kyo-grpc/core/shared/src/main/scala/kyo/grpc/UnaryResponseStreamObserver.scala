package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import kyo.grpc.*

class UnaryResponseStreamObserver[Response](promise: Promise[GrpcResponse.Errors, Response])(using Frame, AllowUnsafe)
    extends StreamObserver[Response]:

    override def onNext(value: Response): Unit =
        val complete = promise.completeDiscard(Success(value))
        Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow
    end onNext

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val result = Failure(StreamNotifier.throwableToStatusException(throwable))
        val complete = promise.completeDiscard(result)
        Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow
    end onError

    override def onCompleted(): Unit =
        val complete = promise.completeDiscard(Failure(StatusException(Status.CANCELLED)))
        Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow
    end onCompleted

end UnaryResponseStreamObserver
