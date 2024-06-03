package kyo.grpc

import io.grpc.StatusException
import kyo.*
import scala.util.Try

type GrpcResponses >: GrpcResponses.Effects <: GrpcResponses.Effects

object GrpcResponses:
    type Effects = Fibers & Aborts[StatusException]

    def init[T: Flat](t: => T < GrpcResponses): Fiber[T] =
        def pendingFibers: Try[T] < Fibers =
            Aborts.run[StatusException].apply[StatusException, T, Fibers, StatusException, Any](t).map(_.toTry)

        IOs.run {
            Fibers.init(pendingFibers).map(_.transform(_.fold(Fiber.fail, Fiber.value)))
        }
