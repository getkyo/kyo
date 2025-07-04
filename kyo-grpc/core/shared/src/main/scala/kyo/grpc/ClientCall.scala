package kyo.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.MethodDescriptor
import kyo.*
import scalapb.grpc.ClientCalls

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
        request: Request
    )(using Frame): Response < Grpc =
        val future = ClientCalls.asyncUnaryCall(channel, method, options, request)
        Grpc.fromFuture(future)
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
        for
            promise          <- Promise.init[GrpcFailure, Response]
            responseObserver <- Sync.Unsafe(UnaryResponseStreamObserver(promise))
            requestObserver = ClientCalls.asyncClientStreamingCall(channel, method, options, responseObserver)
            _        <- StreamNotifier.notifyObserver(requests, requestObserver)
            response <- promise.get
        yield response

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
        request: Request
    )(using Frame, Tag[Emit[Chunk[Response]]]): Stream[Response, Grpc] =
        val responses =
            for
                responseChannel  <- StreamChannel.init[Response, GrpcFailure]
                responseObserver <- Sync.Unsafe(ResponseStreamObserver(responseChannel))
                _ = ClientCalls.asyncServerStreamingCall(channel, method, options, request, responseObserver)
            yield responseChannel.stream
        Stream.unwrap(responses)
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
        val responses =
            for
                responseChannel  <- StreamChannel.init[Response, GrpcFailure]
                responseObserver <- Sync.Unsafe(ResponseStreamObserver(responseChannel))
                requestObserver = ClientCalls.asyncBidiStreamingCall(channel, method, options, responseObserver)
                _ <- StreamNotifier.notifyObserver(requests, requestObserver)
            yield responseChannel.stream
        Stream.unwrap(responses)
    end bidiStreaming

end ClientCall
