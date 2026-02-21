package kyo.grpc

import io.grpc.{Status, StatusException}

final case class CallClosed(status: Status, trailers: SafeMetadata) {
    def asException: StatusException = status.asException(trailers.toJava)
}
