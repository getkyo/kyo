package kyo.grpc

import io.grpc.{Metadata, ServerCall}
import kyo.*

type GrpcMeta = Env[Metadata] & Emit[ResponseOptions]

type GrpcHandler[Requests, Responses] = Requests => Responses < (Grpc & Emit[Metadata])

type GrpcHandlerInit[Requests, Responses] = GrpcHandler[Requests, Responses] < GrpcMeta
