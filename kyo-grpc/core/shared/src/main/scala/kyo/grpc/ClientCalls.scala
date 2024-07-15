package kyo.grpc

import io.grpc.{CallOptions, Channel, MethodDescriptor}
import kyo.*

object ClientCalls:

    def asyncUnaryCall[Request, Response: Flat](
        channel: Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        request: Request
    ): Response < GrpcRequests =
        val future = scalapb.grpc.ClientCalls.asyncUnaryCall(channel, method, options, request)
        GrpcRequests.fromFuture(future)

end ClientCalls
