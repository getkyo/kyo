package kyo.grpc

import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException

final case class CallClosed(status: Status, trailers: Metadata):
    def asException: GrpcFailure = StatusException(status, trailers)
