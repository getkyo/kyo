package kyo.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import kyo.*
import kyo.grpc.RequestOptions.DefaultResponseCapacity
import kyo.grpc.internal.mergeIfDefined

/** Client-side call options emitted while preparing a generated gRPC request.
  *
  * `Metadata` values are merged synchronously before the call starts so generated request code does not share mutable metadata across
  * concurrent call boundaries.
  */
final case class RequestOptions(
    headers: Maybe[Metadata] = Maybe.empty,
    messageCompression: Maybe[Boolean] = Maybe.empty,
    responseCapacity: Maybe[Int] = Maybe.empty
):

    def combine(that: RequestOptions)(using Frame): RequestOptions < Sync =
        this.headers.mergeIfDefined(that.headers).map: mergedHeaders =>
            RequestOptions(
                headers = mergedHeaders,
                messageCompression = that.messageCompression.orElse(this.messageCompression),
                responseCapacity = that.responseCapacity.orElse(this.responseCapacity)
            )
    end combine

    def responseCapacityOrDefault: Int = responseCapacity.getOrElse(DefaultResponseCapacity)

end RequestOptions

object RequestOptions:

    val DefaultRequestBuffer: Int    = 8
    val DefaultResponseCapacity: Int = 8

    def run[A, S](v: A < (Emit[RequestOptions] & S))(using Frame): (RequestOptions, A) < (Sync & S) =
        Emit.runFold[RequestOptions](RequestOptions())(_.combine(_))(v)

end RequestOptions
