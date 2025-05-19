package kyo.grpc

import io.grpc.*
import org.scalactic.*
import org.scalactic.TripleEquals.*

given statusEquality: Equality[Status] with
    def areEqual(a: Status, b: Any): Boolean =
        b match
            case b: Status => a.getCode === b.getCode && a.getDescription === b.getDescription && a.getCause === b.getCause
            case _         => false
end statusEquality

given metadataEquality: Equality[Metadata] with
    def areEqual(a: Metadata, b: Any): Boolean =
        b match
            case b: Metadata => a.toString === b.toString
            case _ => false
end metadataEquality

given statusExceptionEquality: Equality[StatusException] with
    def areEqual(a: StatusException, b: Any): Boolean =
        b match
            case b: StatusException => a.getStatus === b.getStatus && a.getTrailers === b.getTrailers
            case _                  => false
end statusExceptionEquality
