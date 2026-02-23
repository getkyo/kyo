package kgrpc

import io.grpc.Status
import kgrpc.test.*
import kgrpc.test.given
import kyo.*
import kyo.grpc.*

object TestServiceImpl extends TestService:

    override def oneToOne(request: Request): Response < Grpc =
        requestToResponse(request)
    end oneToOne

    private def requestToResponse(request: Request): Response < Grpc =
        request match
            case Request.Empty => Abort.fail(Status.INVALID_ARGUMENT.asException)
            case nonEmpty: Request.NonEmpty =>
                nonEmpty match
                    case Success(message, _, _)       => Kyo.lift(Echo(message))
                    case Fail(message, code, _, _, _) => Abort.fail(Status.fromCodeValue(code).withDescription(message).asException)
                    case Panic(message, _, _, _)      => Abort.panic(new Exception(message))
                end match
        end match
    end requestToResponse

    override def oneToMany(request: Request): Stream[Response, Grpc] < Grpc =
        requestToResponses(request)
    end oneToMany

    private def requestToResponses(request: Request): Stream[Response, Grpc] < Grpc =
        request match
            case Request.Empty => Stream.empty[Response]
            case nonEmpty: Request.NonEmpty =>
                nonEmpty match
                    case Success(message, count, _) =>
                        stream((1 to count).map(n => Echo(s"$message $n")))
                    case Fail(message, code, _, true, _) =>
                        Abort.fail(Status.fromCodeValue(code).withDescription(message).asException)
                    case Fail(message, code, after, _, _) =>
                        val echos = (after to 1 by -1).map(n => Kyo.lift(Echo(s"Failing in $n")))
                        stream(echos :+ Abort.fail(Status.fromCodeValue(code).withDescription(message).asException))
                    case Panic(message, _, true, _) =>
                        Abort.panic(new Exception(message))
                    case Panic(message, after, _, _) =>
                        val echos = (after to 1 by -1).map(n => Kyo.lift(Echo(s"Panicing in $n")))
                        stream(echos :+ Abort.panic(new Exception(message)))
                end match
        end match
    end requestToResponses

    private def stream(responses: Seq[Response < Grpc]): Stream[Response, Grpc] =
        Stream:
            Kyo.foldLeft(responses)(()) { (_, response) =>
                response.map(r => Emit.value(Chunk(r)))
            }

    override def manyToOne(requests: Stream[Request, Grpc]): Response < Grpc =
        requests.fold(Maybe.empty[String])((acc, request) =>
            for
                response <- requestToResponse(request)
                nextAcc <- response.asNonEmpty.get match
                    case Echo(message, _) => acc.map(_ + " " + message).orElse(Maybe(message))
            yield nextAcc
        ).map(maybeMessage => Echo(maybeMessage.getOrElse("")))

    override def manyToMany(requests: Stream[Request, Grpc]): Stream[Response, Grpc] < Grpc =
        requests.flatMap(requestToResponses)

end TestServiceImpl
