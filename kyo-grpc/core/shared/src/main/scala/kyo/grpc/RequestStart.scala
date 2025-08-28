package kyo.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import kyo.*
import kyo.grpc.internal.mergeIfDefined

// TODO: What to call this?
// TODO: Is this safe? Metadata is not thread-safe. We use it in Vars but I think that is OK?
// TODO: Provide nicer Metadata.
final case class RequestStart(
    headers: Maybe[Metadata] = Maybe.empty,
    messageCompression: Maybe[Boolean] = Maybe.empty
):

    def combine(that: RequestStart)(using Frame): RequestStart < Sync =
        this.headers.mergeIfDefined(that.headers).map: mergedHeaders =>
            RequestStart(
                headers = mergedHeaders,
                messageCompression = that.messageCompression.orElse(this.messageCompression)
            )
    end combine

end RequestStart

object RequestStart:

    val DefaultRequestBuffer: Int = 8

    def run[A, S](v: A < (Emit[RequestStart] & S))(using Frame): (RequestStart, A) < (Sync & S) =
        Emit.runFold[RequestStart](RequestStart())(_.combine(_))(v)

end RequestStart
