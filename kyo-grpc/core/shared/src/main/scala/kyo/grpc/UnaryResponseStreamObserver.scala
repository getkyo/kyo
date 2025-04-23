package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import kyo.grpc.*

// TODO: Should this extend one of the other StreamObservers?
class UnaryResponseStreamObserver[Response](promise: Promise[GrpcResponse.Errors, Response])(using Frame, AllowUnsafe)
    extends StreamObserver[Response]:

    override def onNext(value: Response): Unit =
        val complete = promise.completeDiscard(Success(value))
        Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow
    end onNext

    override def onError(throwable: Throwable): Unit =
        val result = throwable match
            case ex: StatusException => Failure(ex)
            case other               => Panic(other)
        val complete = promise.completeDiscard(result)
        Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow
    end onError

    override def onCompleted(): Unit =
        val complete = promise.completeDiscard(Failure(StatusException(Status.CANCELLED)))
        Abort.run(IO.Unsafe.run(complete)).eval.getOrThrow
    end onCompleted

end UnaryResponseStreamObserver
