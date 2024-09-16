package kyo.grpc

import kyo.*
import scala.concurrent.Future

object GrpcRequest:

    // TODO: I suspect we might need to handle the StatusException and StatusRuntimeException here.
    def fromFuture[Response: Flat](f: Future[Response])(using Frame): Response < Async =
        Fiber.fromFuture(f).map(_.get)

end GrpcRequest
