package kyo.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.MethodDescriptor
import kyo.*
import scala.collection.convert.StreamExtensions.StreamUnboxer
import scalapb.grpc.*

object ClientCall:

    def unary[Request, Response: Flat](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: Request
    )(using Frame): Response < GrpcRequest =
        val future = ClientCalls.asyncUnaryCall(channel, method, options, request)
        GrpcRequest.fromFuture(future)
    end unary

    def clientStreaming[Request: Flat: Tag, Response: Flat](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requests: Stream[Request, GrpcRequest]
    )(using Frame): Response < GrpcRequest =
        for
            promise          <- Promise.init[GrpcResponse.Errors, Response]
            responseObserver <- IO.Unsafe(SingleResponseStreamObserver(promise))
            requestObserver = ClientCalls.asyncClientStreamingCall(channel, method, options, responseObserver)
            _        <- StreamNotifier.notifyObserver(requests, requestObserver)
            response <- promise.get
        yield response

    def serverStreaming[Request, Response: Flat: Tag](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: Request
    )(using Frame): Stream[Response, GrpcRequest] =
        val responses =
            for
                responseChannel  <- StreamChannel.init[Response, GrpcResponse.Errors]
                responseObserver <- IO.Unsafe(ResponseStreamObserver(responseChannel))
                // TODO: Do we have to cancel the observer returned here?
                _ = ClientCalls.asyncServerStreamingCall(channel, method, options, request, responseObserver)
            yield responseChannel.stream
        Stream.embed(responses)
    end serverStreaming

    def bidiStreaming[Request, Response: Flat](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requests: Stream[Request, GrpcRequest]
    )(using Frame): Stream[Response, GrpcRequest] =
        ???
    end bidiStreaming

end ClientCall
