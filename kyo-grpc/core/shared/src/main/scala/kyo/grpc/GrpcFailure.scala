package kyo.grpc

import io.grpc.*
import scala.util.chaining.scalaUtilChainingOps

/** A failure that occurred while sending or receiving a gRPC message.
  *
  * These are typically created from a [[io.grpc.Status]] via `asException`.
  *
  * @see
  *   [[StatusException]]
  */
type GrpcFailure = StatusException

object GrpcFailure:

    /** Converts a [[Throwable]] to a [[GrpcFailure]].
      *
      * Conversions are handled as follows:
      *   - [[StatusException]]: Returns the exception unchanged.
      *   - [[StatusRuntimeException]]: Converts to `StatusException` while preserving status, trailers, and stack trace.
      *   - Other exceptions: Uses [[Status#fromThrowable(java.lang.Throwable)]] which attempts to find a gRPC status from nested causes,
      *     defaulting to [[Status.UNKNOWN]] status if none is found.
      *
      * @param t
      *   The `Throwable` to convert
      * @return
      *   A `GrpcFailure` suitable for gRPC error reporting
      */
    def fromThrowable(t: Throwable): GrpcFailure =
        t match
            case e: StatusException        => e
            case e: StatusRuntimeException => StatusException(e.getStatus, e.getTrailers).tap(_.setStackTrace(e.getStackTrace))
            case _                         => Status.fromThrowable(t).asException()

end GrpcFailure
