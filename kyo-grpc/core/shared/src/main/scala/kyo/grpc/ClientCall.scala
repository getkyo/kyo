package kyo.grpc

import io.grpc.{CallOptions, Channel, Metadata, MethodDescriptor, Status, StatusException}
import kyo.*
import kyo.grpc.*
import kyo.grpc.internal.UnaryClientCallListener
import scalapb.grpc.ClientCalls

type GrpcRequestCompletion = Unit < (Env[RequestEnd] & Async)

private[grpc] type GrpcResponsesAwaitingCompletion[Responses] = Result[GrpcFailure, Responses] < (Emit[GrpcRequestCompletion] & Async)

private[grpc] type GrpcRequestsWithHeaders[Requests] = Requests < (Emit[GrpcRequestCompletion] & Async)

type GrpcRequests[Requests] = Requests < (Env[Metadata] & Emit[GrpcRequestCompletion] & Async)

type GrpcRequestsInit[Requests] = GrpcRequests[Requests] < (Emit[RequestStart] & Async)

/** Provides client-side gRPC call implementations for different RPC patterns.
  *
  * This object contains methods for executing unary, client streaming, server streaming, and bidirectional streaming gRPC calls using Kyo's
  * effect system.
  */
object ClientCall:

    /** Executes a unary gRPC call.
      *
      * A unary call sends a single request and receives a single response.
      *
      * @param channel
      *   the gRPC channel to use for the call
      * @param method
      *   the method descriptor defining the RPC method
      * @param options
      *   call options for configuring the request
      * @param request
      *   the request message to send
      * @tparam Request
      *   the type of the request message
      * @tparam Response
      *   the type of the response message
      * @return
      *   the response message pending [[Grpc]]
      */
    def unary[Request, Response](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: GrpcRequestsInit[Request]
    )(using Frame): Response < Grpc =
        // TODO: This has effects.
        val call = channel.newCall(method, options)

        // TODO: Handle cancellation properly
        //call.cancel()

        def start(options: RequestStart) =
            for
                responsePromise <- Promise.init[Response, Abort[StatusException]]
                headersPromise <- Promise.init[Metadata, Any]
                completionPromise <- Promise.init[RequestEnd, Any]
                readySignal <- Signal.initRef[Boolean](false)
                listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)
                _ <- Sync.defer(call.start(listener, options.headers.getOrElse(Metadata())))
                _ <- Sync.defer(options.messageCompression.foreach(call.setMessageCompression))
            yield listener
        end start

        def processHeaders(listener: UnaryClientCallListener[Response], requestEffect: GrpcRequests[Request]): GrpcRequestsWithHeaders[Request] =
            for
                headers <- listener.headersPromise.get
            yield Env.run(headers)(requestEffect)

        def sendAndReceive(listener: UnaryClientCallListener[Response], requestEffect: GrpcRequestsWithHeaders[Request]): GrpcResponsesAwaitingCompletion[Response] =
            for
                // We ignore the ready signal here as we want the request ready as soon as possible,
                // and we will only buffer at most one request.
                request <- requestEffect
                _ <- Sync.defer(call.request(1))
                _ <- Sync.defer(call.sendMessage(request))
                _ <- Sync.defer(call.halfClose())
                result <- listener.responsePromise.getResult
            yield result
        end sendAndReceive

        def processCompletion(listener: UnaryClientCallListener[Response])(completionEffect: GrpcResponsesAwaitingCompletion[Response]): Response < Grpc =
            val done = Emit.runForeach(completionEffect): handler =>
                listener.completionPromise.get.map: end =>
                    Env.run(end)(handler)
            done.map(Abort.get)
        end processCompletion

        val run =
            RequestStart.run(request).map: (options, requestEffect) =>
                for
                    listener <- start(options)
                    response <- (for
                        requestWithHeaders <- processHeaders(listener, requestEffect)
                        response <- sendAndReceive(listener, requestWithHeaders)
                    yield response).handle(processCompletion(listener))
                yield response

        Abort.recoverOrThrow((status: Status) => Abort.fail(status.asException))(run)
    end unary

    /** Executes a client streaming gRPC call.
      *
      * A client streaming call sends multiple requests via a stream and receives a single response. The client can send multiple messages
      * over time, and the server responds with a single message after processing all requests.
      *
      * @param channel
      *   the gRPC channel to use for the call
      * @param method
      *   the method descriptor defining the RPC method
      * @param options
      *   call options for configuring the request
      * @param requests
      *   a stream of request messages to send
      * @tparam Request
      *   the type of the request messages
      * @tparam Response
      *   the type of the response message
      * @return
      *   the response message pending [[Grpc]]
      */
    def clientStreaming[Request: Tag, Response](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requests: Stream[Request, Grpc]
    )(using Frame, Tag[Emit[Chunk[Request]]]): Response < Grpc =
//        for
//            promise <- Promise.init[GrpcFailure, Response]
//            responseObserver = UnaryResponseStreamObserver(promise)
//            requestObserver <- Sync.defer(ClientCalls.asyncClientStreamingCall(channel, method, options, responseObserver))
//            _               <- StreamNotifier.notifyObserver(requests, requestObserver)
//            response        <- promise.get
//        yield response
        ???

    /** Executes a server streaming gRPC call.
      *
      * A server streaming call sends a single request and receives multiple responses via a stream. The client sends one message, and the
      * server responds with a stream of messages over time.
      *
      * @param channel
      *   the gRPC channel to use for the call
      * @param method
      *   the method descriptor defining the RPC method
      * @param options
      *   call options for configuring the request
      * @param request
      *   the request message to send
      * @tparam Request
      *   the type of the request message
      * @tparam Response
      *   the type of the response messages
      * @return
      *   a stream of response messages pending [[Grpc]]
      */
    def serverStreaming[Request, Response: Tag](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: GrpcRequestsInit[Request]
    )(using Frame, Tag[Emit[Chunk[Response]]]): Stream[Response, Grpc] =
        ???
//        val responses: Stream[Response, Abort[GrpcFailure] & Async] < (Sync & Scope) =
//            for
//                responseChannel <- StreamChannel.initUnscoped[Response, GrpcFailure]
//                responseObserver = ResponseStreamObserver(responseChannel)
//                _ <- Sync.defer(ClientCalls.asyncServerStreamingCall(channel, method, options, request, responseObserver))
//            yield responseChannel.stream
//        Stream.unwrap(responses)
    end serverStreaming

    /** Executes a bidirectional streaming gRPC call.
      *
      * A bidirectional streaming call allows both client and server to send multiple messages via streams. Both sides can send messages
      * independently and asynchronously, enabling full-duplex communication patterns.
      *
      * @param channel
      *   the gRPC channel to use for the call
      * @param method
      *   the method descriptor defining the RPC method
      * @param options
      *   call options for configuring the request
      * @param requests
      *   a stream of request messages to send
      * @tparam Request
      *   the type of the request messages
      * @tparam Response
      *   the type of the response messages
      * @return
      *   a stream of response messages pending [[Grpc]]
      */
    def bidiStreaming[Request: Tag, Response: Tag](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requests: Stream[Request, Grpc]
    )(using Frame, Tag[Emit[Chunk[Request]]], Tag[Emit[Chunk[Response]]]): Stream[Response, Grpc] =
        ???
//        val responses =
//            for
//                responseChannel  <- StreamChannel.init[Response, GrpcFailure]
//                responseObserver <- Sync.Unsafe(ResponseStreamObserver(responseChannel))
//                requestObserver = ClientCalls.asyncBidiStreamingCall(channel, method, options, responseObserver)
//                _ <- StreamNotifier.notifyObserver(requests, requestObserver)
//            yield responseChannel.stream
//        Stream.unwrap(responses)
    end bidiStreaming

end ClientCall
