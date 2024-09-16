package kyo.grpc

import io.grpc.StatusException
import kyo.*
import scala.util.Try

type GrpcResponse >: GrpcResponse.Effects <: GrpcResponse.Effects

object GrpcResponse:

    type Effects = Async & Abort[StatusException]

end GrpcResponse
