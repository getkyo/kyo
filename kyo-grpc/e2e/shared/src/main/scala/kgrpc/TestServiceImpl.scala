package kgrpc

import io.grpc.*
import kgrpc.test.*
import kgrpc.test.given
import kyo.*
import kyo.grpc.*
import kyo.Emit.Ack.*

object TestServiceImpl extends TestService:
    override def oneToOne(request: Request): Response < GrpcResponse =
        request match
            case Request.Empty => Abort.fail(Status.INVALID_ARGUMENT.asException())
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, _, _)  => Echo(message)
                    case Cancel(code, _, _)  => Abort.fail(Status.fromCodeValue(code).asException)
                    case Fail(message, _, _) => Abort.panic(new Exception(message))

    override def oneToMany(request: Request): Stream[Response, GrpcResponse] =
        request match
            case Request.Empty => Stream.empty[Response, GrpcResponse]
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, count, _)  => stream(Seq.fill(count)(Echo(message)))
                    case Cancel(code, after, _)  => Stream.
                    case Fail(message, after, _) => Abort.panic(new Exception(message))

    override def manyToOne(requests: Stream[Request, GrpcRequest]): Response < GrpcResponse =
        request match
            case Request.Empty => Abort.fail(Status.INVALID_ARGUMENT.asException())
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, _, _)  => Echo(message)
                    case Cancel(code, _, _)  => Abort.fail(Status.fromCodeValue(code).asException)
                    case Fail(message, _, _) => Abort.panic(new Exception(message))

    override def manyToMany(requests: Stream[Request, GrpcRequest]): Stream[Response, GrpcResponse] =
        request match
            case Request.Empty => Abort.fail(Status.INVALID_ARGUMENT.asException())
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, _, _)      => Echo(message)
                    case Cancel(code, after, _)  => Abort.fail(Status.fromCodeValue(code).asException)
                    case Fail(message, after, _) => Abort.panic(new Exception(message))

    private def stream(responses: Seq[Response < GrpcResponse]): Stream[Response, GrpcResponse] =
        Stream[Response, GrpcResponse](
            Emit.andMap(Chunk.empty[Response]) { ack =>
                Loop(responses, ack) { (c, ack) =>
                    ack match
                        case Stop => Loop.done(Stop)
                        case Continue(0) => Loop.done(ack)
                        case Continue(n) =>
                            if c.isEmpty then Loop.done(Ack.Continue())
                            else Emit.andMap(c.take(n))(ack => Loop.continue(c.dropLeft(n), ack))
                }
            }
        )

end TestServiceImpl
