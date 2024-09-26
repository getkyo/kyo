package kyo.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.MethodDescriptor
import kyo.*

object ClientCall:

    def unary[Request, Response: Flat](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: Request
    )(using Frame): Response < GrpcRequest =
        val future = scalapb.grpc.ClientCalls.asyncUnaryCall(channel, method, options, request)
        GrpcRequest.fromFuture(future)
    end unary

    def clientStreaming[Request, Response: Flat](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requests: Stream[Request, GrpcRequest]
    )(using Frame): Response < GrpcRequest =
        ???
    end clientStreaming

    def serverStreaming[Request, Response: Flat](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: Request
    )(using Frame): Stream[Response, GrpcRequest] =
        ???
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
