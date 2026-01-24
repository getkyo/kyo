package kyo.grpc

import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import kyo.*
import org.scalactic.TripleEquals.*
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

class GrpcTest extends Test:

    "fromFuture" - {
        "successful Future" in run {
            val future          = Future.successful("test result")
            val grpcComputation = Grpc.fromFuture(future)

            Abort.run[GrpcFailure](grpcComputation).map: result =>
                assert(result === Result.succeed("test result"))
        }

        "failed Future with StatusException" in run {
            val statusException = Status.INVALID_ARGUMENT.withDescription("Invalid input").asException()
            val future          = Future.failed(statusException)
            val grpcComputation = Grpc.fromFuture(future)

            Abort.run[GrpcFailure](grpcComputation).map: result =>
                assert(result.isFailure && (result.failure.get eq statusException))
        }

        "failed Future with StatusRuntimeException" in run {
            val status   = Status.UNAVAILABLE.withDescription("Service unavailable")
            val metadata = new Metadata()
            metadata.put(Metadata.Key.of("retry-after", Metadata.ASCII_STRING_MARSHALLER), "30")
            val runtimeException = status.asRuntimeException(metadata)
            val future           = Future.failed(runtimeException)
            val grpcComputation  = Grpc.fromFuture(future)

            Abort.run[GrpcFailure](grpcComputation).map: result =>
                assert(result.isFailure)
                val failure = result.failure.get
                assert(failure.getStatus === status)
                assert(failure.getTrailers === metadata)
                assert(failure.getStackTrace sameElements runtimeException.getStackTrace)
        }

        "failed Future with other exception" in run {
            val originalException = new IllegalArgumentException("Custom error")
            val future            = Future.failed(originalException)
            val grpcComputation   = Grpc.fromFuture(future)

            Abort.run[GrpcFailure](grpcComputation).map: result =>
                assert(result.isFailure)
                val failure = result.failure.get
                assert(failure.getStatus.getCode === Status.Code.UNKNOWN)
                assert(failure.getStatus.getCause === originalException)
        }

        "failed Future with nested StatusException" in run {
            val innerStatusException = Status.PERMISSION_DENIED.withDescription("Access denied").asException()
            val wrapperException     = new RuntimeException("Wrapper", innerStatusException)
            val future               = Future.failed(wrapperException)
            val grpcComputation      = Grpc.fromFuture(future)

            Abort.run[GrpcFailure](grpcComputation).map: result =>
                assert(result.isFailure)
                val failure = result.failure.get
                assert(failure.getStatus.getCode === Status.Code.PERMISSION_DENIED)
                assert(failure.getStatus.getDescription === "Access denied")
        }
    }

    "type verification" - {
        "Grpc type alias" in {
            val grpcEffect: String < Grpc                          = "test"
            val asyncEffect: String < (Async & Abort[GrpcFailure]) = grpcEffect
            val _: String < Grpc                                   = asyncEffect
            succeed
        }
    }

end GrpcTest
