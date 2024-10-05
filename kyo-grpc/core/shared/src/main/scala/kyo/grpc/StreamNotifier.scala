package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kyo.*
import scala.util.chaining.scalaUtilChainingOps

private[grpc] object StreamNotifier:

    def notifyObserver[A: Flat, E <: Throwable: SafeClassTag, S](
        value: A < (Abort[E] & S),
        observer: StreamObserver[A]
    )(using Frame): Unit < (IO & S) =
        Abort.run[E](value).map {
            case Result.Success(value) => IO {
                    observer.onNext(value)
                    observer.onCompleted()
                }
            // TODO: Why the unchecked warning here?
            case result: Result.Error[E] => notifyError(result, observer)
        }

    def notifyObserver[A: Flat: Tag, E <: Throwable: SafeClassTag, S](
        values: Stream[A, Abort[E] & S],
        observer: StreamObserver[A]
    )(using Frame): Unit < (IO & S) =
        Abort.run[E](values.runForeach(observer.onNext)).map(notifyCompleteOrError(_, observer))

    private def notifyCompleteOrError[E <: Throwable](
        complete: Result[E, Unit],
        requestObserver: StreamObserver[?]
    )(using Frame): Unit < IO =
        complete match
            case Result.Success(_) => IO(requestObserver.onCompleted())
            // TODO: Why the unchecked warning here?
            case result: Result.Error[E] => notifyError(result, requestObserver)
        end match
    end notifyCompleteOrError

    private def notifyError[E <: Throwable](
        result: Result.Error[E],
        requestObserver: StreamObserver[?]
    )(using Frame): Unit < IO =
        IO {
            // TODO: Why the non-exhaustive match here?
            result match
                case Result.Fail(s: E) => requestObserver.onError(s)
                case Result.Panic(t)   => requestObserver.onError(throwableToStatusException(t))
        }

    // TODO: This doesn't belong here.
    def throwableToStatusException(t: Throwable): StatusException =
        t match
            case e: StatusException        => e
            case e: StatusRuntimeException => StatusException(e.getStatus()).tap(_.setStackTrace(e.getStackTrace))
            case _                         => Status.INTERNAL.withDescription(t.getMessage).withCause(t).asException()

end StreamNotifier
