package kyo.grpc

import io.grpc.*
import org.scalactic.TripleEquals.*

class CallClosedTest extends Test:

    "construction" - {
        "creates CallClosed with status and trailers" in {
            val status   = Status.OK
            val trailers = SafeMetadata.empty
            val result   = CallClosed(status, trailers)

            assert(result.status === status)
            assert(result.trailers === trailers)
        }

        "creates CallClosed with non-OK status" in {
            val status   = Status.CANCELLED.withDescription("Request cancelled")
            val trailers = SafeMetadata.empty
            val result   = CallClosed(status, trailers)

            assert(result.status.getCode === Status.Code.CANCELLED)
            assert(result.status.getDescription === "Request cancelled")
            assert(result.trailers === trailers)
        }

        "creates CallClosed with metadata in trailers" in {
            val status   = Status.OK
            val trailers = SafeMetadata.empty.add("test-key", "test-value")
            val result   = CallClosed(status, trailers)

            assert(result.status === status)
            assert(result.trailers.getStrings("test-key") === Seq("test-value"))
        }
    }

    "equality" - {
        "equals itself" in {
            val status   = Status.OK
            val trailers = SafeMetadata.empty
            val result   = CallClosed(status, trailers)

            assert(result === result)
        }

        "equals another CallClosed with same status and trailers" in {
            val status   = Status.OK
            val trailers = SafeMetadata.empty
            val result1  = CallClosed(status, trailers)
            val result2  = CallClosed(status, trailers)

            assert(result1 === result2)
        }

        "not equals CallClosed with different status" in {
            val trailers = SafeMetadata.empty
            val result1  = CallClosed(Status.OK, trailers)
            val result2  = CallClosed(Status.CANCELLED, trailers)

            assert(result1 !== result2)
        }

        "not equals CallClosed with different trailers" in {
            val status  = Status.OK
            val result1 = CallClosed(status, SafeMetadata.empty.add("key", "v1"))
            val result2 = CallClosed(status, SafeMetadata.empty.add("key", "v2"))

            assert(result1 !== result2)
        }
    }

    "copy" - {
        "creates copy with modified status" in {
            val status1  = Status.OK
            val status2  = Status.CANCELLED
            val trailers = SafeMetadata.empty
            val original = CallClosed(status1, trailers)
            val copied   = original.copy(status = status2)

            assert(copied.status === status2)
            assert(copied.trailers === trailers)
            assert(original.status === status1)
        }

        "creates copy with modified trailers" in {
            val status    = Status.OK
            val trailers1 = SafeMetadata.empty
            val trailers2 = SafeMetadata.empty.add("test-key", "test-value")
            val original  = CallClosed(status, trailers1)
            val copied    = original.copy(trailers = trailers2)

            assert(copied.status === status)
            assert(copied.trailers === trailers2)
            assert(copied.trailers.getStrings("test-key") === Seq("test-value"))
            assert(original.trailers === trailers1)
        }

        "creates copy with all fields modified" in {
            val status1   = Status.OK
            val status2   = Status.CANCELLED
            val trailers1 = SafeMetadata.empty
            val trailers2 = SafeMetadata.empty.add("key", "val")
            val original  = CallClosed(status1, trailers1)
            val copied    = original.copy(status = status2, trailers = trailers2)

            assert(copied.status === status2)
            assert(copied.trailers === trailers2)
            assert(original.status === status1)
            assert(original.trailers === trailers1)
        }
    }

    "field access" - {
        "provides access to status field" in {
            val status   = Status.DEADLINE_EXCEEDED.withDescription("Timeout")
            val trailers = SafeMetadata.empty
            val result   = CallClosed(status, trailers)

            assert(result.status.getCode === Status.Code.DEADLINE_EXCEEDED)
            assert(result.status.getDescription === "Timeout")
        }

        "provides access to trailers field" in {
            val status   = Status.OK
            val trailers = SafeMetadata.empty.add("key1", "value1").add("key2", "value2")
            val result   = CallClosed(status, trailers)

            assert(result.trailers.getStrings("key1") === Seq("value1"))
            assert(result.trailers.getStrings("key2") === Seq("value2"))
        }
    }

    "different status codes" - {
        "works with OK status" in {
            val result = CallClosed(Status.OK, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.OK)
        }

        "works with CANCELLED status" in {
            val result = CallClosed(Status.CANCELLED, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.CANCELLED)
        }

        "works with UNKNOWN status" in {
            val result = CallClosed(Status.UNKNOWN, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.UNKNOWN)
        }

        "works with INVALID_ARGUMENT status" in {
            val result = CallClosed(Status.INVALID_ARGUMENT, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.INVALID_ARGUMENT)
        }

        "works with DEADLINE_EXCEEDED status" in {
            val result = CallClosed(Status.DEADLINE_EXCEEDED, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.DEADLINE_EXCEEDED)
        }

        "works with NOT_FOUND status" in {
            val result = CallClosed(Status.NOT_FOUND, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.NOT_FOUND)
        }

        "works with ALREADY_EXISTS status" in {
            val result = CallClosed(Status.ALREADY_EXISTS, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.ALREADY_EXISTS)
        }

        "works with PERMISSION_DENIED status" in {
            val result = CallClosed(Status.PERMISSION_DENIED, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.PERMISSION_DENIED)
        }

        "works with RESOURCE_EXHAUSTED status" in {
            val result = CallClosed(Status.RESOURCE_EXHAUSTED, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.RESOURCE_EXHAUSTED)
        }

        "works with FAILED_PRECONDITION status" in {
            val result = CallClosed(Status.FAILED_PRECONDITION, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.FAILED_PRECONDITION)
        }

        "works with ABORTED status" in {
            val result = CallClosed(Status.ABORTED, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.ABORTED)
        }

        "works with OUT_OF_RANGE status" in {
            val result = CallClosed(Status.OUT_OF_RANGE, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.OUT_OF_RANGE)
        }

        "works with UNIMPLEMENTED status" in {
            val result = CallClosed(Status.UNIMPLEMENTED, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.UNIMPLEMENTED)
        }

        "works with INTERNAL status" in {
            val result = CallClosed(Status.INTERNAL, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.INTERNAL)
        }

        "works with UNAVAILABLE status" in {
            val result = CallClosed(Status.UNAVAILABLE, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.UNAVAILABLE)
        }

        "works with DATA_LOSS status" in {
            val result = CallClosed(Status.DATA_LOSS, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.DATA_LOSS)
        }

        "works with UNAUTHENTICATED status" in {
            val result = CallClosed(Status.UNAUTHENTICATED, SafeMetadata.empty)
            assert(result.status.getCode === Status.Code.UNAUTHENTICATED)
        }
    }

    "status with cause and description" - {
        "preserves status description" in {
            val status   = Status.INTERNAL.withDescription("Internal server error")
            val trailers = SafeMetadata.empty
            val result   = CallClosed(status, trailers)

            assert(result.status.getDescription === "Internal server error")
        }

        "preserves status cause" in {
            val cause    = new RuntimeException("Original error")
            val status   = Status.INTERNAL.withCause(cause)
            val trailers = SafeMetadata.empty
            val result   = CallClosed(status, trailers)

            assert(result.status.getCause === cause)
        }

        "preserves both description and cause" in {
            val cause    = new RuntimeException("Original error")
            val status   = Status.INTERNAL.withDescription("Internal server error").withCause(cause)
            val trailers = SafeMetadata.empty
            val result   = CallClosed(status, trailers)

            assert(result.status.getDescription === "Internal server error")
            assert(result.status.getCause === cause)
        }
    }
end CallClosedTest
