package kyo.grpc

import kyo.*

import scala.concurrent.Future

type GrpcRequests >: GrpcRequests.Effects <: GrpcRequests.Effects

object GrpcRequests:
    type Effects = Fibers

    def fromFuture[Response: Flat](f: Future[Response]): Response < GrpcRequests =
        Fibers.fromFuture(f)
end GrpcRequests
