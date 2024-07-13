package kyo.grpc

import kyo.*

import scala.concurrent.Future

type GrpcRequests >: GrpcRequests.Effects <: GrpcRequests.Effects

object GrpcRequests:
    type Effects = Fibers

    def fromFuture[T: Flat](f: Future[T]): T < GrpcRequests =
        Fibers.fromFuture(f)
end GrpcRequests
