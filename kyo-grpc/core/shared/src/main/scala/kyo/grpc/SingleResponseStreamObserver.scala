package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import kyo.grpc.*

// TODO: Should this extend one of the other StreamObservers?
class SingleResponseStreamObserver[Response](promise: Promise[GrpcResponse.Errors, Response])(using Frame, AllowUnsafe)
    extends StreamObserver[Response]:

    override def onNext(value: Response): Unit =
        Abort.run(IO.Unsafe.run(promise.completeDiscard(Success(value)))).eval.getOrThrow

    override def onError(throwable: Throwable): Unit =
        val result = throwable match
            case ex: StatusException => Failure(ex)
            case other               => Panic(other)
        Abort.run(IO.Unsafe.run(promise.completeDiscard(result))).eval.getOrThrow
    end onError

    override def onCompleted(): Unit =
        Abort.run(IO.Unsafe.run(promise.completeDiscard(Failure(StatusException(Status.CANCELLED))))).eval.getOrThrow

end SingleResponseStreamObserver
