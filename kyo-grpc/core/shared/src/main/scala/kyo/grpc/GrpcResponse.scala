package kyo.grpc

import io.grpc.StatusException
import kyo.*

type GrpcResponse = Async & Abort[GrpcResponse.Errors]

object GrpcResponse:
    type Errors = StatusException
