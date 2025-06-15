package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*

/** An 'inbound', server-side observer that receives a stream of requests and sends a single response.
  *
  * This implementation is not thread-safe but is thread-compatible as per the [[StreamObserver]] requirements.
  *
  * @param f
  *   a function that takes a stream of requests and returns a single response
  * @param requestChannel
  *   a channel for receiving requests
  * @param responseObserver
  *   the observer that will receive the response
  * @tparam Request
  *   the type of the request messages
  * @tparam Response
  *   the type of the response message
  */
class RequestStreamObserver[Request, Response](
    f: Stream[Request, GrpcRequest] => Response < GrpcResponse,
    requestChannel: StreamChannel[Request, GrpcRequest.Errors],
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]]) extends StreamObserver[Request]:

    private val response = f(requestChannel.stream)

    /** Only run this once.
      */
    private val start: Unit < Async =
        StreamNotifier.notifyObserver(response, responseObserver)

    override def onNext(request: Request): Unit =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.put(request)).getOrThrow

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val error = StreamNotifier.throwableToStatusException(throwable)
        val fail  = requestChannel.error(error)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow
    end onError

    override def onCompleted(): Unit =
        IO.Unsafe.evalOrThrow(requestChannel.closeProducer)

end RequestStreamObserver

object RequestStreamObserver:

    /** Initializes a [[RequestStreamObserver]].
      *
      * When the pending [[IO]] is run, it will start the background processing of requests and sending of the response. It will complete
      * when the response has been sent to the `responseObserver`, and it has been completed. It will abort if an error occurs while
      * processing the requests or sending the response and the `responseObserver` will receive the error.
      *
      * @param f
      *   the function that processes the stream of requests and produces a single response
      * @param responseObserver
      *   the observer that will receive the response
      * @tparam Request
      *   the type of the request messages
      * @tparam Response
      *   the type of the response message
      * @return
      *   an instance of `RequestStreamObserver` pending [[IO]]
      */
    def init[Request, Response](
        f: Stream[Request, GrpcRequest] => Response < GrpcResponse,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]]): RequestStreamObserver[Request, Response] < IO =
        for
            requestChannel <- StreamChannel.init[Request, GrpcRequest.Errors]
            observer = RequestStreamObserver(f, requestChannel, responseObserver)
            _ <- Async.run(observer.start)
        yield observer
    end init

end RequestStreamObserver
