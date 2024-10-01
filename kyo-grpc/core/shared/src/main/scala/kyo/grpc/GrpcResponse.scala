package kyo.grpc

import io.grpc.StatusException
import kyo.*

type GrpcResponse = Async & Abort[StatusException]
