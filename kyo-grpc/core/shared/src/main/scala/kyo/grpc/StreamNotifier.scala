package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kyo.*
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal

/** Helpers for notifying gRPC [[StreamObserver]]s with Kyo computation results.
  *
  * This provides methods to bridge between Kyo's effect system and gRPC's streaming interface, handling both single values and streams
  * while properly managing errors and exceptions.
  */
private[kyo] object StreamNotifier:

    /** Notifies a gRPC [[StreamObserver]] with the result of a single Kyo computation.
      *
      * When the computation completes with:
      *   - `Success`: It calls [[[StreamObserver#onNext]] and then [[StreamObserver#onCompleted]]
      *   - `Error`: Converts the exception to a [[StatusException]] and calls [[StreamObserver#onError]]
      *
      * If `onNext` throws an exception then this is handled in the same way as an `Error`. It is not propagated any further.
      *
      * @param value
      *   The Kyo computation to evaluate and send to the observer
      * @param observer
      *   The gRPC `StreamObserver` to notify with the result
      * @tparam A
      *   The type of the value produced by the computation
      * @tparam E
      *   The error type that extends `Throwable`
      * @tparam S
      *   The effect types present in the computation
      * @return
      *   A `Unit` computation that performs the notification, pending [[Sync]] and `S` effects
      */
    def notifyObserver[A, E <: Throwable: SafeClassTag, S](
        value: A < (Abort[E] & S),
        observer: StreamObserver[A]
    )(using Frame): Unit < (Sync & S) =
        Abort.run[Throwable](value.map(notifyNext(_, observer)))
            .map(notifyCompleteOrError(_, observer))

    /** Notifies a gRPC [[StreamObserver]] with the elements of a Kyo [[Stream]].
      *
      * This method consumes all elements in the stream and forwards them to the observer by calling [[StreamObserver#onNext]].
      *
      * When the stream completes with:
      *   - `Success`: It calls [[StreamObserver#onCompleted]]
      *   - `Error`: Converts the exception to a [[StatusException]] and calls [[StreamObserver#onError]]
      *
      * If `onNext` throws an exception then it aborts consuming the stream and is then handled in the same way as an `Error`. It is not
      * propagated any further.
      *
      * @param values
      *   The Kyo `Stream` containing values to send to the observer
      * @param observer
      *   The gRPC `StreamObserver` to notify with the stream elements
      * @tparam A
      *   The type of the values in the stream
      * @tparam E
      *   The error type that extends `Throwable`
      * @tparam S
      *   The effect types present in the stream computation
      * @return
      *   A `Unit` computation that performs the stream notification, pending [[Sync]] and `S` effects
      */
    def notifyObserver[A, E <: Throwable: SafeClassTag, S](
        values: Stream[A, Abort[E] & S],
        observer: StreamObserver[A]
    )(using Frame, Tag[Emit[Chunk[A]]]): Unit < (Sync & S) =
        Abort.run[Throwable](values.foreach(notifyNext(_, observer)))
            .map(notifyCompleteOrError(_, observer))

    private def notifyNext[A](value: A, observer: StreamObserver[A])(using Frame): Unit < (Sync & Abort[Throwable]) =
        Sync.defer(Abort.catching[Throwable](observer.onNext(value)))

    private def notifyCompleteOrError[E <: Throwable](
        result: Result[E, Unit],
        observer: StreamObserver[?]
    )(using Frame): Unit < Sync =
        result match
            case _: Result.Success[Unit] @unchecked => Sync.defer(observer.onCompleted())
            case Result.Error(t)                    => Sync.defer(observer.onError(GrpcFailure.fromThrowable(t)))

end StreamNotifier
