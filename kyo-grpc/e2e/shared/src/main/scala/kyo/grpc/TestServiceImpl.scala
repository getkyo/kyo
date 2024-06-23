package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.test.{*, given}

object TestServiceImpl extends TestService:
    override def unary(request: Request): Response < GrpcResponses =
        request match
            case Request.Empty => IOs.fail("Empty request")
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Echo(message, _) => EchoEcho(message)
                    case Abort(code, _)   => Aborts.fail(Status.fromCodeValue(code).asException)
                    case Fail(message, _) => IOs.fail(message)

end TestServiceImpl
