package kyo.grpc

import io.grpc.{Metadata, ServerCall}
import kyo.*
import kyo.grpc.internal.mergeIfDefined

// TODO: What to call this?
// TODO: Is this safe? Metadata is not thread-safe. We use it in Vars but I think that is OK?
// TODO: Provide nicer Metadata.
final case class RequestOptions(
    headers: Maybe[Metadata] = Maybe.empty,
    messageCompression: Maybe[Boolean] = Maybe.empty
):

    def combine(that: RequestOptions)(using Frame): RequestOptions < Sync =
        this.headers.mergeIfDefined(that.headers).map: mergedHeaders =>
            RequestOptions(
                headers = mergedHeaders,
                messageCompression = that.messageCompression.orElse(this.messageCompression)
            )
    end combine

end RequestOptions

object RequestOptions:

    val DefaultRequestBuffer: Int = 8

    def run[A, S](v: A < (Emit[RequestOptions] & S))(using Frame): (RequestOptions, A) < (Sync & S) =
        Emit.runFold[RequestOptions](RequestOptions())(_.combine(_))(v)

end RequestOptions
