package kyo.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import kyo.*
import kyo.grpc.RequestOptions.DefaultResponseCapacity
import kyo.grpc.internal.mergeIfDefined

// TODO: What to call this?
// TODO: Is this safe? Metadata is not thread-safe. We use it in Vars but I think that is OK?
// TODO: Provide nicer Metadata.
final case class RequestOptions(
    headers: Maybe[Metadata] = Maybe.empty,
    messageCompression: Maybe[Boolean] = Maybe.empty,
    responseCapacity: Maybe[Int] = Maybe.empty
):

    // TODO: Delete?
    def combine(that: RequestOptions)(using Frame): RequestOptions < Sync =
        this.headers.mergeIfDefined(that.headers).map: mergedHeaders =>
            RequestOptions(
                headers = mergedHeaders,
                messageCompression = that.messageCompression.orElse(this.messageCompression),
                responseCapacity = that.responseCapacity.orElse(this.responseCapacity),
            )
    end combine

    def responseCapacityOrDefault: Int = responseCapacity.getOrElse(DefaultResponseCapacity)

end RequestOptions

object RequestOptions:

    // TODO: What are sensible defaults?
    val DefaultRequestBuffer: Int = 8
    val DefaultResponseCapacity: Int = 8

    def run[A, S](v: A < (Emit[RequestOptions] & S))(using Frame): (RequestOptions, A) < (Sync & S) =
        Emit.runFold[RequestOptions](RequestOptions())(_.combine(_))(v)

end RequestOptions
