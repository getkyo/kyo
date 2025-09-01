package kyo.grpc

import io.grpc.Metadata
import io.grpc.Status

final case class CallClosed(status: Status, trailers: Metadata)
