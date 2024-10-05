package kgrpc

import io.grpc.*
import kgrpc.test.*
import kgrpc.test.given
import kyo.*
import kyo.Emit.Ack.*
import kyo.Result
import kyo.Result.Panic
import kyo.grpc.*

object TestServiceImpl extends TestService:
    override def oneToOne(request: Request): Response < GrpcResponse =
        request match
            case Request.Empty => Abort.fail(Status.INVALID_ARGUMENT.asException())
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, _, _)  => Kyo.pure(Echo(message))
                    case Cancel(code, _, _)  => Abort.fail(Status.fromCodeValue(code).asException)
                    case Fail(message, _, _) => Abort.panic(new Exception(message))

    override def oneToMany(request: Request): Stream[Response, GrpcResponse] < GrpcResponse =
        request match
            case Request.Empty => Stream.empty[Response]
            case nonEmpty: Request.NonEmpty => nonEmpty match
                    case Say(message, count, _) =>
                        stream((1 to count).map(n => Echo(s"$message $n")))
                    case Cancel(code, after, _) =>
                        val echos = (0 until after).map(i => Kyo.pure(Echo(s"Cancelling in ${after - i}")))
                        stream(echos :+ Abort.fail(Status.fromCodeValue(code).asException))
                    // TODO: Test failing at the outer level
                    case Fail(message, after, _) =>
                        val echos = (0 until after).map(i => Kyo.pure(Echo(s"Failing in ${after - i}")))
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
        Stream(GrpcRequest.mergeErrors(requests.map(oneToOne).emit))

    private def stream(responses: Seq[Response < GrpcResponse]): Stream[Response, GrpcResponse] =
        Stream(Kyo.collect(responses).map(Emit(_)))

end TestServiceImpl
