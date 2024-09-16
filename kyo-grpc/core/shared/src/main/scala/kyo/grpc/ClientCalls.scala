package kyo.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.MethodDescriptor
import kyo.*

object ClientCalls:

    def asyncUnaryCall[Request, Response: Flat](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: Request
    )(using Frame): Response < Async =
        val future = scalapb.grpc.ClientCalls.asyncUnaryCall(channel, method, options, request)
        GrpcRequest.fromFuture(future)
    end asyncUnaryCall

end ClientCalls
