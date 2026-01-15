package kyo.grpc

import io.grpc.{Metadata, Status, StatusException}

final case class CallClosed(status: Status, trailers: Metadata) {
    def asException: GrpcFailure = StatusException(status, trailers)
}
