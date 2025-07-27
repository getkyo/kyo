package kyo.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import kyo.*

// TODO: What to call this?
// TODO: Is this safe? Metadata is not thread-safe. We use it in Vars but I think that is OK?
final case class ServerCallOptions(
    headers: Metadata = Metadata(),
    trailers: Metadata = Metadata(),
    messageCompression: Maybe[Boolean] = Maybe.empty,
    compression: Maybe[String] = Maybe.empty,
    onReadyThreshold: Maybe[Int] = Maybe.empty
):
    
    def mergeTrailers(trailers: Metadata): ServerCallOptions =
        this.trailers.merge(trailers)
        this
    end mergeTrailers

    def sendHeaders(call: ServerCall[?, ?])(using Frame): Unit < Sync =
        Sync.defer:
            messageCompression.foreach(call.setMessageCompression)
            compression.foreach(call.setCompression)
            onReadyThreshold.foreach(call.setOnReadyThreshold)
            // May only be called once and must be called before sendMessage.
            call.sendHeaders(headers)
    end sendHeaders

end ServerCallOptions
