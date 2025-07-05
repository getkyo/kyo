package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*

private[kyo] class RequestStreamObserver[Request](
    requestChannel: StreamChannel[Request, GrpcFailure]
)(using Frame, AllowUnsafe) extends StreamObserver[Request]:

    override def onNext(request: Request): Unit =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(
            // In cases where the channel was closed early we ignore additional elements since they will never be consumed.
            // The StreamNotifier will have called onNext and onComplete on the responseObserver so the caller should detect this and stop
            // invoking onNext but there could be a delay so ignore additional requests until that happens.
            Abort.recover[Closed](_ => ())(
                requestChannel.put(request)
            )
        ).getOrThrow

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val error = GrpcFailure.fromThrowable(throwable)
        val fail  = requestChannel.error(error)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow
    end onError

    override def onCompleted(): Unit =
        Sync.Unsafe.evalOrThrow(requestChannel.closeProducer)

end RequestStreamObserver

private[kyo] object RequestStreamObserver:

    /** An 'inbound', server-side observer that receives a stream of requests and sends a single response.
      *
      * This creates a stream from the `requestChannel` and provides that to the function. Requests are forwarded to the channel. Once it
      * receives the response it forwards it on to the `responseObserver`.
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

    /** Initializes a [[RequestStreamObserver]].
      *
      * When the pending [[Sync]] is run, it will start the background processing of requests and sending of the response. It will complete
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
      *   an instance of `RequestStreamObserver` pending `Sync`
      */
    def one[Request, Response](
        f: Stream[Request, Grpc] => Response < Grpc,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]]): RequestStreamObserver[Request] < Sync =
        for
            requestChannel <- StreamChannel.init[Request, GrpcFailure]
            response = f(requestChannel.stream)
            _ <- Fiber.run(
                StreamNotifier.notifyObserver(response, responseObserver)
                    // Make sure to close the channel explicitly in case the function produced a result without consuming the entire stream.
                    .andThen(requestChannel.close)
            )
        yield RequestStreamObserver(requestChannel)
    end one

    /** An 'inbound', server-side observer that receives a stream of requests and sends a stream of responses.
      *
      * This creates a stream from the `requestChannel` and provides that to the function. Requests are forwarded to the channel. Each
      * response is then forwarded on to the `responseObserver`.
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

    /** Initializes a [[RequestStreamObserver]].
      *
      * When the pending [[Sync]] is run, it will start the background processing of requests and sending of responses. It will complete
      * when all responses have been sent to the `responseObserver`, and it has been completed. It will abort if an error occurs while
      * processing the requests or sending the responses and the `responseObserver` will receive the error.
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
      *   an instance of `RequestStreamObserver` pending `Sync`
      */
    def many[Request, Response](
        f: Stream[Request, Grpc] => Stream[Response, Grpc] < Grpc,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]], Tag[Emit[Chunk[Response]]]): RequestStreamObserver[Request] < Sync =
        for
            requestChannel <- StreamChannel.init[Request, GrpcFailure]
            responses = Stream.unwrap(f(requestChannel.stream))
            _ <- Fiber.run(
                StreamNotifier.notifyObserver(responses, responseObserver)
                    // Make sure to close the channel explicitly in case the function produced a result without consuming the entire stream.
                    .andThen(requestChannel.close)
            )
        yield RequestStreamObserver(requestChannel)
    end many

end RequestStreamObserver
