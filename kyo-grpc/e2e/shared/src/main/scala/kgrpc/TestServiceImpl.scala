package kgrpc

import io.grpc.*
import kgrpc.test.*
import kgrpc.test.given
import kyo.*
import kyo.Result
import kyo.Result.Panic
import kyo.grpc.*

object TestServiceImpl extends TestService:
    override def oneToOne(request: Request): Response < GrpcResponse =
        request match
            case Request.Empty => Abort.fail(Status.INVALID_ARGUMENT.asException())
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, _, _)     => Kyo.pure(Echo(message))
                    case Cancel(code, _, _, _)  => Abort.fail(Status.fromCodeValue(code).asException)
                    case Fail(message, _, _, _) => Abort.panic(new Exception(message))

    override def oneToMany(request: Request): Stream[Response, GrpcResponse] < GrpcResponse =
        request match
            case Request.Empty => Stream.empty[Response]
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, count, _) =>
                        stream((1 to count).map(n => Echo(s"$message $n")))
                    case Cancel(code, _, true, _) =>
                        Abort.fail(Status.fromCodeValue(code).asException)
                    case Cancel(code, after, _, _) =>
                        val echos = (after to 1 by -1).map(n => Kyo.pure(Echo(s"Cancelling in $n")))
                        stream(echos :+ Abort.fail(Status.fromCodeValue(code).asException))
                    case Fail(message, _, true, _) =>
                        Abort.panic(new Exception(message))
                    case Fail(message, after, _, _) =>
                        val echos = (after to 1 by -1).map(n => Kyo.pure(Echo(s"Failing in $n")))
                        stream(echos :+ Abort.panic(new Exception(message)))

    override def manyToOne(requests: Stream[Request, GrpcRequest]): Response < GrpcResponse =
        val result = requests.runFold(Maybe.empty[String])((acc, request) =>
            for
                response <- oneToOne(request)
                nextAcc <- response.asNonEmpty.get match
                    case Echo(message, _) => acc.map(_ + " " + message).orElse(Maybe(message))
            yield nextAcc
        ).map(maybeMessage => Echo(maybeMessage.getOrElse("")))
        GrpcRequest.mergeErrors(result)
    end manyToOne

    override def manyToMany(requests: Stream[Request, GrpcRequest]): Stream[Response, GrpcResponse] < GrpcResponse =
        // TODO: There should be an easier way to do this.
        // TODO: Test failing at the outer level
        // TODO: Map oneToMany
        Stream(GrpcRequest.mergeErrors(requests.map(oneToOne).emit))

    private def stream(responses: Seq[Response < GrpcResponse]): Stream[Response, GrpcResponse] =
        // TODO: Tidy up.
        val a: Chunk[Response] < GrpcResponse                = Kyo.collect(responses)
        val b: Unit < (Emit[Chunk[Response]] & GrpcResponse) = a.map(c => Emit.value(c))
        Stream(b)
//        Stream(Kyo.collect(responses).map: r =>
//            Emit.valueWith(r))

    // Stream[Response, GrpcResponse](
    //     Emit.andMap(Chunk.empty[Response]) { ack =>
    //         Loop(responses, ack) { (responses, ack) =>
    //             ack match
    //                 case Stop => Loop.done(Stop)
    //                 case Continue(n) =>
    //                     val (init, tail) = responses.splitAt(n)
    //                     if init.isEmpty then Loop.done(Stop)
    //                     else Kyo.collect(init).map(Emit.andMap(_)(ack => Loop.continue(tail, ack)))
    //         }
    //     }
    // )

//        def emit(remaining: Seq[Response < GrpcResponse])(ack: Ack): Ack < (Emit[Chunk[Response]] & GrpcResponse) =
//            ack match
//                case Stop => Stop
//                case Continue(_) =>
//                    remaining match
//                        case head +: tail => head.map(response => Emit.andMap(Chunk(response))(emit(tail)(_)))
//                        case _            => Stop
//
//        Stream(Emit.andMap(Chunk.empty)(emit(responses)))
    end stream

end TestServiceImpl
