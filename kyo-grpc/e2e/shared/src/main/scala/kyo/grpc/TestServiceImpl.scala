package kyo.grpc

import kyo.*
import kyo.grpc.test.*

class TestServiceImpl extends TestService:
    override def unary(request: Request): Response < GrpcResponses =
        ???

end TestServiceImpl
