package kyo.grpc

import io.grpc.StatusException
import kyo.*

type GrpcResponse = Async & Abort[GrpcResponse.Errors]

// TODO: Merge this with GrpcRequest.
object GrpcResponse:
    type Errors = StatusException
