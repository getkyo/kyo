package kyo.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.MethodDescriptor
import kyo.*
import scalapb.grpc.*

object ClientCall:

    def unary[Request, Response](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: Request
    )(using Frame): Response < GrpcRequest =
        val future = ClientCalls.asyncUnaryCall(channel, method, options, request)
        GrpcRequest.fromFuture(future)
    end unary

    def clientStreaming[Request: Tag, Response](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requests: Stream[Request, GrpcRequest]
    )(using Frame, Tag[Emit[Chunk[Request]]]): Response < GrpcRequest =
        for
            promise          <- Promise.init[GrpcResponse.Errors, Response]
            responseObserver <- Sync.Unsafe(UnaryResponseStreamObserver(promise))
            requestObserver = ClientCalls.asyncClientStreamingCall(channel, method, options, responseObserver)
            _        <- StreamNotifier.notifyObserver(requests, requestObserver)
            response <- promise.get
        yield response

    def serverStreaming[Request, Response: Tag](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: Request
    )(using Frame, Tag[Emit[Chunk[Response]]]): Stream[Response, GrpcRequest] =
        val responses =
            for
                responseChannel <- StreamChannel.init[Response, GrpcResponse.Errors]
                // TODO: Do we have to cancel the observer returned here?
                responseObserver <- Sync.Unsafe(ResponseStreamObserver(responseChannel))
                _ = ClientCalls.asyncServerStreamingCall(channel, method, options, request, responseObserver)
            yield responseChannel.stream
        Stream.unwrap(responses)
    end serverStreaming

    def bidiStreaming[Request: Tag, Response: Tag](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requests: Stream[Request, GrpcRequest]
    )(using Frame, Tag[Emit[Chunk[Request]]], Tag[Emit[Chunk[Response]]]): Stream[Response, GrpcRequest] =
        val responses =
            for
                responseChannel <- StreamChannel.init[Response, GrpcResponse.Errors]
                // TODO: Do we have to cancel the observer returned here?
                responseObserver <- Sync.Unsafe(ResponseStreamObserver(responseChannel))
                requestObserver = ClientCalls.asyncBidiStreamingCall(channel, method, options, responseObserver)
                _ <- StreamNotifier.notifyObserver(requests, requestObserver)
            yield responseChannel.stream
        Stream.unwrap(responses)
    end bidiStreaming

end ClientCall
