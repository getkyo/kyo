package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kyo.*
import scala.util.chaining.scalaUtilChainingOps

private[grpc] object StreamNotifier:

    def notifyObserver[A, E <: Throwable: SafeClassTag, S](
        value: A < (Abort[E] & S),
        observer: StreamObserver[A]
    )(using Frame): Unit < (IO & S) =
        Abort.run[E](value).map {
            case Result.Success(value) =>
                for
                    _ <- Log.debug(s"StreamNotifier - Sending next value to observer: $value")
                    _ <- IO(observer.onNext(value))
                    _ <- Log.debug("StreamNotifier - Completing observer")
                    _ <- IO(observer.onCompleted())
                yield ()
            // TODO: Why the unchecked warning here?
            case result: Result.Error[E] => notifyError(result, observer)
        }

    def notifyObserver[A: Tag, E <: Throwable: SafeClassTag, S](
        values: Stream[A, Abort[E] & S],
        observer: StreamObserver[A]
    )(using Frame): Unit < (IO & S) =
        def handleValue(value: A) =
            for
                _ <- Log.debug(s"StreamNotifier - Sending next value to observer: $value")
                _ <- IO(observer.onNext(value))
            yield ()
        Abort.run[E](values.foreach(handleValue)).map(notifyCompleteOrError(_, observer))
    end notifyObserver

    private def notifyCompleteOrError[E <: Throwable](
        complete: Result[E, Unit],
        observer: StreamObserver[?]
    )(using Frame): Unit < IO =
        complete match
            case Result.Success(_) =>
                for
                    _ <- Log.debug("StreamNotifier - Completing observer")
                    _ <- IO(observer.onCompleted())
                yield ()
            // TODO: Why the unchecked warning here?
            case result: Result.Error[E] => notifyError(result, observer)
        end match
    end notifyCompleteOrError

    private def notifyError[E <: Throwable](
        result: Result.Error[E],
        requestObserver: StreamObserver[?]
    )(using Frame): Unit < IO =
        // TODO: Why the non-exhaustive match here?
        result match
            case Result.Failure(s: E) =>
                for
                    _ <- Log.debug(s"StreamNotifier - Sending error to observer: $s")
                    _ <- IO(requestObserver.onError(s))
                yield ()
            case Result.Panic(t)      =>
                for
                    _ <- Log.debug(s"StreamNotifier - Sending error to observer: $t")
                    _ <- IO(requestObserver.onError(throwableToStatusException(t)))
                yield ()
    end notifyError

    // TODO: This doesn't belong here.
    def throwableToStatusException(t: Throwable): StatusException =
        t match
            case e: StatusException        => e
            case e: StatusRuntimeException => StatusException(e.getStatus()).tap(_.setStackTrace(e.getStackTrace))
            case _                         => Status.INTERNAL.withDescription(t.getMessage).withCause(t).asException()

end StreamNotifier
