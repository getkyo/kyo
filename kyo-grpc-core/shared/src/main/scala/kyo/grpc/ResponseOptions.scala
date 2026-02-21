package kyo.grpc

import io.grpc.ServerCall
import kyo.*

// TODO: What to call this?
final case class ResponseOptions(
    headers: SafeMetadata = SafeMetadata.empty,
    messageCompression: Maybe[Boolean] = Maybe.empty,
    compression: Maybe[String] = Maybe.empty,
    onReadyThreshold: Maybe[Int] = Maybe.empty,
    requestBuffer: Maybe[Int] = Maybe.empty
):

    def requestBufferOrDefault: Int =
        requestBuffer.getOrElse(ResponseOptions.DefaultRequestBuffer)

    def combine(that: ResponseOptions)(using Frame): ResponseOptions < Sync =
        Sync.defer:
            ResponseOptions(
                headers = this.headers.merge(that.headers),
                messageCompression = that.messageCompression.orElse(this.messageCompression),
                compression = that.compression.orElse(this.compression),
                onReadyThreshold = that.onReadyThreshold.orElse(this.onReadyThreshold),
                requestBuffer = that.requestBuffer.orElse(this.requestBuffer)
            )
    end combine

    def sendHeaders(call: ServerCall[?, ?])(using Frame): Unit < Sync =
        Sync.defer:
            // These may only be called once and must be called before sendMessage.
            messageCompression.foreach(call.setMessageCompression)
            compression.foreach(call.setCompression)
            onReadyThreshold.foreach(call.setOnReadyThreshold)
            // Headers must be sent even if empty.
            call.sendHeaders(headers.toJava)
    end sendHeaders

end ResponseOptions

object ResponseOptions:

    val DefaultRequestBuffer: Int = 8

    def run[A, S](v: A < (Emit[ResponseOptions] & S))(using Frame): (ResponseOptions, A) < (Sync & S) =
        Emit.runFold[ResponseOptions](ResponseOptions())(_.combine(_))(v)

    def runSend[A, S](call: ServerCall[?, ?])(v: A < (Emit[ResponseOptions] & S))(using Frame): A < (Sync & S) =
        run(v).map: (options, a) =>
            options.sendHeaders(call).andThen(a)

end ResponseOptions
