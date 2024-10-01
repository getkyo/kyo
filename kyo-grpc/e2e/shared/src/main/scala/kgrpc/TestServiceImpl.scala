package kgrpc

import io.grpc.*
import kgrpc.test.*
import kgrpc.test.given
import kyo.*
import kyo.Emit.Ack.*
import kyo.grpc.*

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
            case Request.Empty => Stream.empty[Response]
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, count, _) =>
                        stream(Seq.fill(count)(Echo(message)))
                    case Cancel(code, after, _) =>
                        stream((0 until after).map(i => Echo(s"Cancelling in ${after - i}")))
                            .concat(Stream.init(Abort.fail(Status.fromCodeValue(code).asException)))
                    case Fail(message, after, _) =>
                        stream((0 until after).map(i => Echo(s"Failing in ${after - i}")))
                            .concat(Stream.init(Abort.panic(new Exception(message))))

    override def manyToOne(requests: Stream[Request, GrpcRequest]): Response < GrpcResponse =
        requests.runFold(Maybe.empty[String])((acc, request) =>
            oneToOne(request).map(_.asNonEmpty.get match
                case Echo(message, _) => acc.map(_ + " " + message).orElse(Maybe(message))
            )
        ).map(maybeMessage => Echo(maybeMessage.getOrElse("")))

    override def manyToMany(requests: Stream[Request, GrpcRequest]): Stream[Response, GrpcResponse] =
        ???

    private def stream(responses: Seq[Response < GrpcResponse]): Stream[Response, GrpcResponse] =
        Stream[Response, GrpcResponse](
            Emit.andMap(Chunk.empty[Response]) { ack =>
                Loop(responses, ack) { (responses, ack) =>
                    ack match
                        case Stop => Loop.done(Stop)
                        case Continue(n) =>
                            val (init, tail) = responses.splitAt(n)
                            if init.isEmpty then Loop.done(Stop)
                            else Kyo.collect(init).map(Emit.andMap(_)(ack => Loop.continue(tail, ack)))
                }
            }
        )

end TestServiceImpl
