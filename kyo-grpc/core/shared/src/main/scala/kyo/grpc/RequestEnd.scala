package kyo.grpc

import io.grpc.Metadata
import io.grpc.Status

final case class RequestEnd(status: Status, trailers: Metadata)
