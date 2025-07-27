package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

private[kyo] object ServerCallHandlers:

    def errorStatus(error: Result.Error[Throwable])(using Frame): Status < Var[ServerCallOptions] =
        val t = error.failureOrPanic
        val status = Status.fromThrowable(t)
        Maybe(Status.trailersFromThrowable(t)) match
            case Maybe.Absent => status
            case Maybe.Present(trailers) =>
                Var.update[ServerCallOptions](_.mergeTrailers(trailers))
                    .andThen(status)

end ServerCallHandlers
