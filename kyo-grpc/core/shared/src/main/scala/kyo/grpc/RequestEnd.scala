package kyo.grpc

import io.grpc.{Metadata, Status}

final case class RequestEnd(status: Status, trailers: Metadata)
