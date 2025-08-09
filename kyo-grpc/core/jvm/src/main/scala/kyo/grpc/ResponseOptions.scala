package kyo.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import kyo.*
import kyo.grpc.mergeIfDefined

// TODO: What to call this?
// TODO: Is this safe? Metadata is not thread-safe. We use it in Vars but I think that is OK?
// TODO: Provide nicer Metadata.
final case class ResponseOptions(
    headers: Maybe[Metadata] = Maybe.empty,
    messageCompression: Maybe[Boolean] = Maybe.empty,
    compression: Maybe[String] = Maybe.empty,
    onReadyThreshold: Maybe[Int] = Maybe.empty,
    requestBuffer: Maybe[Int] = Maybe.empty
):

    def requestBufferOrDefault: Int =
        requestBuffer.getOrElse(ResponseOptions.DefaultRequestBuffer)

    def combine(that: ResponseOptions)(using Frame): ResponseOptions < Sync =
        this.headers.mergeIfDefined(that.headers).map: mergedHeaders =>
            ResponseOptions(
                headers = mergedHeaders,
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
            headers.foreach(call.sendHeaders)
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
