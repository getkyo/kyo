package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

private[kyo] object ServerCallHandlers:

    // TODO: Inline this.
    def errorStatus(error: Result.Error[Throwable]): Status =
        val t = error.failureOrPanic
        Status.fromThrowable(t)

end ServerCallHandlers
