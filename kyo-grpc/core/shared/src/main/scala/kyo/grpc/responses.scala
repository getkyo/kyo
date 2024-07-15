package kyo.grpc

import io.grpc.StatusException
import kyo.*

import scala.util.Try

type GrpcResponses >: GrpcResponses.Effects <: GrpcResponses.Effects

object GrpcResponses:
    type Effects = Fibers & Aborts[StatusException]

    def init[Response: Flat](t: => Response < GrpcResponses): Fiber[Response] < IOs =
        def pendingFibers: Try[Response] < Fibers =
            Aborts.run[StatusException].apply[StatusException, Response, Fibers, StatusException, Any](t).map(_.toTry)

        Fibers.init(pendingFibers).map(_.transform(_.fold(Fiber.fail, Fiber.value)))
    end init
end GrpcResponses
