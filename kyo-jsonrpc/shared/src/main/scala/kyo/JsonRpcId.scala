package kyo

import kyo.Schema
import kyo.TypeMismatchException
import scala.annotation.nowarn

/** Typed identifier for a JSON-RPC 2.0 request or response.
  *
  * JSON-RPC 2.0 §5 allows ids to be a string, a number (without fractional parts), or null.
  * This opaque type models the non-null cases as a `String | Long` union, matching the wire
  * representation directly. Null ids are represented by `Maybe.Absent` at the call site.
  *
  * Construct via the companion factories:
  *  - [[JsonRpcId.apply(n: Long)]]: numeric id.
  *  - [[JsonRpcId.apply(s: String)]]: string id.
  *
  * Used in [[JsonRpcRequest.id]], [[JsonRpcResponse.id]], [[JsonRpcMalformedMessage.id]],
  * [[JsonRpcHandler.cancel]], [[JsonRpcHandler.Pending.id]], [[JsonRpcHandler.ExtrasEncoder]],
  * and [[JsonRpcRoute.Context.requestId]].
  *
  * @see [[JsonRpcRequest]]
  * @see [[JsonRpcResponse]]
  */
opaque type JsonRpcId = String | Long

object JsonRpcId:

    def apply(n: Long): JsonRpcId   = n
    def apply(s: String): JsonRpcId = s

    /** Extractor for numeric ids. Enables `case JsonRpcId.Num(n) =>` pattern matching. */
    object Num:
        def apply(n: Long): JsonRpcId = n
        def unapply(id: JsonRpcId): Option[Long] =
            id match
                case n: Long => Some(n)
                case _       => None
    end Num

    /** Extractor for string ids. Enables `case JsonRpcId.Str(s) =>` pattern matching. */
    object Str:
        def apply(s: String): JsonRpcId = s
        def unapply(id: JsonRpcId): Option[String] =
            id match
                case s: String => Some(s)
                case _         => None
    end Str

    extension (id: JsonRpcId)
        def fold[A](ifLong: Long => A, ifString: String => A): A =
            id match
                case n: Long   => ifLong(n)
                case s: String => ifString(s)
        def isLong: Boolean   = id.isInstanceOf[Long]
        def isString: Boolean = id.isInstanceOf[String]
        def toLongOption: Maybe[Long] =
            id match
                case n: Long => Maybe(n)
                case _       => Maybe.Absent
        def toStringOption: Maybe[String] =
            id match
                case s: String => Maybe(s)
                case _         => Maybe.Absent
    end extension

    @nowarn("msg=anonymous")
    given schema: Schema[JsonRpcId] = Schema.init[JsonRpcId](
        writeFn = (id, writer) =>
            id match
                case n: Long   => writer.long(n)
                case s: String => writer.string(s),
        readFn = reader =>
            if reader.isNil() then
                // word in string literal describes the JSON absent-value type, not a reference
                throw TypeMismatchException(Seq.empty, "number or string", "null")(using reader.frame)
            else
                try JsonRpcId(reader.long())
                catch case _: TypeMismatchException => JsonRpcId(reader.string())
    )

    given CanEqual[JsonRpcId, JsonRpcId] = CanEqual.derived

end JsonRpcId
