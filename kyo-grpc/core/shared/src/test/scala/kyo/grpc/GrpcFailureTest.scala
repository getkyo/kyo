package kyo.grpc

import io.grpc.*
import kyo.*
import org.scalactic.TripleEquals.*

class GrpcFailureTest extends Test:

    "fromThrowable" - {
        "preserves existing StatusException" in {
            val original = Status.ALREADY_EXISTS.withDescription("Already exists").asException()
            val result   = GrpcFailure.fromThrowable(original)

            assert(result eq original)
        }

        "converts StatusRuntimeException to StatusException" in {
            val status   = Status.INVALID_ARGUMENT.withDescription("Invalid argument")
            val metadata = new Metadata()
            metadata.put(Metadata.Key.of("test-key", Metadata.ASCII_STRING_MARSHALLER), "test-value")
            val original = status.asRuntimeException(metadata)
            val result   = GrpcFailure.fromThrowable(original)

            assert(result.getStatus === status)
            assert(result.getTrailers === metadata)
            assert(result.getStackTrace === original.getStackTrace)
        }

        "converts other exceptions to StatusException with UNKNOWN status" in {
            val original = new IllegalArgumentException("Invalid argument")
            val result   = GrpcFailure.fromThrowable(original)

            assert(result.getStatus.getCode === Status.Code.UNKNOWN)
            assert(result.getStatus.getDescription === null)
            assert(result.getStatus.getCause === original)
        }

        "extracts nested StatusException from wrapped exception" in {
            val innerStatusException = Status.PERMISSION_DENIED.withDescription("Access denied").asException()
            val wrapperException     = new RuntimeException("Wrapper", innerStatusException)
            val result               = GrpcFailure.fromThrowable(wrapperException)

            assert(result.getStatus.getCode === Status.Code.PERMISSION_DENIED)
            assert(result.getStatus.getDescription === "Access denied")
        }

        "extracts nested StatusRuntimeException from wrapped exception" in {
            val status                = Status.UNAVAILABLE.withDescription("Service unavailable")
            val metadata              = new Metadata()
            val innerRuntimeException = status.asRuntimeException(metadata)
            val wrapperException      = new IllegalStateException("Wrapper", innerRuntimeException)
            val result                = GrpcFailure.fromThrowable(wrapperException)

            assert(result.getStatus.getCode === Status.Code.UNAVAILABLE)
            assert(result.getStatus.getDescription === "Service unavailable")
        }

        "handles deeply nested gRPC exceptions" in {
            val innerStatusException = Status.NOT_FOUND.withDescription("Resource not found").asException()
            val middleException      = new IllegalArgumentException("Middle", innerStatusException)
            val outerException       = new RuntimeException("Outer", middleException)
            val result               = GrpcFailure.fromThrowable(outerException)

            assert(result.getStatus.getCode === Status.Code.NOT_FOUND)
            assert(result.getStatus.getDescription === "Resource not found")
        }

        "defaults to UNKNOWN when no gRPC exception in cause chain" in {
            val innerException  = new IllegalArgumentException("Inner")
            val middleException = new RuntimeException("Middle", innerException)
            val outerException  = new IllegalStateException("Outer", middleException)
            val result          = GrpcFailure.fromThrowable(outerException)

            assert(result.getStatus.getCode === Status.Code.UNKNOWN)
            assert(result.getStatus.getCause === outerException)
        }

        "preserves original exception metadata for StatusRuntimeException" in {
            val status   = Status.DEADLINE_EXCEEDED.withDescription("Request timeout")
            val metadata = new Metadata()
            metadata.put(Metadata.Key.of("retry-info", Metadata.ASCII_STRING_MARSHALLER), "delay=1000ms")
            metadata.put(Metadata.Key.of("request-id", Metadata.ASCII_STRING_MARSHALLER), "req-123")
            val original = status.asRuntimeException(metadata)
            val result   = GrpcFailure.fromThrowable(original)

            assert(result.getStatus === status)
            assert(result.getTrailers === metadata)
            assert(result.getTrailers.get(Metadata.Key.of("retry-info", Metadata.ASCII_STRING_MARSHALLER)) === "delay=1000ms")
            assert(result.getTrailers.get(Metadata.Key.of("request-id", Metadata.ASCII_STRING_MARSHALLER)) === "req-123")
        }
    }

    "type verification" - {
        "GrpcFailure type alias" in {
            val statusException: StatusException = Status.INTERNAL.asException()
            val grpcFailure: GrpcFailure         = statusException
            val _: StatusException               = grpcFailure
            succeed
        }
    }

end GrpcFailureTest
