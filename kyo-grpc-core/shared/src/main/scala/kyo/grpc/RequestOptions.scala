package kyo.grpc

import kyo.*
import kyo.grpc.RequestOptions.DefaultResponseCapacity

final case class RequestOptions(
    headers: SafeMetadata = SafeMetadata.empty,
    messageCompression: Maybe[Boolean] = Maybe.empty,
    responseCapacity: Maybe[Int] = Maybe.empty
):

    def combine(that: RequestOptions)(using Frame): RequestOptions < Sync =
        Sync.defer:
            RequestOptions(
                headers = this.headers.merge(that.headers),
                messageCompression = that.messageCompression.orElse(this.messageCompression),
                responseCapacity = that.responseCapacity.orElse(this.responseCapacity)
            )
    end combine

    def responseCapacityOrDefault: Int = responseCapacity.getOrElse(DefaultResponseCapacity)

end RequestOptions

object RequestOptions:

    // TODO: What are sensible defaults?
    val DefaultRequestBuffer: Int    = 8
    val DefaultResponseCapacity: Int = 8

    def run[A, S](v: A < (Emit[RequestOptions] & S))(using Frame): (RequestOptions, A) < (Sync & S) =
        Emit.runFold[RequestOptions](RequestOptions())(_.combine(_))(v)

end RequestOptions
