package kgrpc

import io.grpc.*
import kgrpc.test.*
import kgrpc.test.given
import kyo.*
import kyo.grpc.*

object TestServiceImpl extends TestService:
    override def unary(request: Request): Response < GrpcResponse =
        request match
            case Request.Empty => Abort.fail(Status.INVALID_ARGUMENT.asException())
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Echo(message, _) => EchoEcho(message)
                    case Cancel(code, _)  => Abort.fail(Status.fromCodeValue(code).asException)
                    case Fail(message, _) => Abort.panic(new Exception(message))

    override def serverStreaming(request: Request): Response < GrpcResponse =
        request match
            case Request.Empty => Abort.fail(Status.INVALID_ARGUMENT.asException())
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Echo(message, _) => EchoEcho(message)
                    case Cancel(code, _)  => Abort.fail(Status.fromCodeValue(code).asException)
                    case Fail(message, _) => Abort.panic(new Exception(message))

end TestServiceImpl
