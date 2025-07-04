package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import scala.language.future

/** An 'inbound', server-side observer that receives a stream of requests and sends a stream of responses.
  *
  * @param f
  *   a function that takes a stream of requests and returns a stream of responses
  * @param requestChannel
  *   a channel for receiving requests
  * @param responseObserver
  *   the observer that will receive the responses
  * @tparam Request
  *   the type of the request messages
  * @tparam Response
  *   the type of the response messages
  */
class BidiRequestStreamObserver[Request, Response] private (
    f: Stream[Request, Grpc] => Stream[Response, Grpc] < Grpc,
    requestChannel: StreamChannel[Request, GrpcFailure],
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]], Tag[Emit[Chunk[Response]]]) extends StreamObserver[Request]:

    private val responses = Stream.unwrap(f(requestChannel.stream))

    /** Only run this once.
      */
    private val start: Unit < Async =
        StreamNotifier.notifyObserver(responses, responseObserver)

    override def onNext(request: Request): Unit =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.put(request)).getOrThrow

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val error = StreamNotifier.throwableToStatusException(throwable)
        val fail  = requestChannel.error(error)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow
    end onError

    override def onCompleted(): Unit =
        Sync.Unsafe.evalOrThrow(requestChannel.closeProducer)

end BidiRequestStreamObserver

object BidiRequestStreamObserver:

    /** Initializes a [[BidiRequestStreamObserver]].
      *
      * When the pending [[Sync]] is run, it will start the background processing of requests and sending of responses. It will complete when
      * all responses have been sent to the `responseObserver`, and it has been completed. It will abort if an error occurs while processing
      * the requests or sending the responses and the `responseObserver` will receive the error.
      *
      * @param f
      *   the function that processes the stream of requests and produces a stream of responses
      * @param responseObserver
      *   the observer that will receive the responses
      * @tparam Request
      *   the type of the request messages
      * @tparam Response
      *   the type of the response messages
      * @return
      *   an instance of `BidiRequestStreamObserver` pending [[Sync]]
      */
    def init[Request, Response](
        f: Stream[Request, Grpc] => Stream[Response, Grpc] < Grpc,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]], Tag[Emit[Chunk[Response]]]): BidiRequestStreamObserver[Request, Response] < Sync =
        for
            requestChannel <- StreamChannel.init[Request, GrpcFailure]
            observer = BidiRequestStreamObserver(f, requestChannel, responseObserver)
            _ <- Fiber.run(observer.start)
        yield observer
    end init

end BidiRequestStreamObserver
