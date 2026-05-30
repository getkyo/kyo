package kyo

import kyo.Schema
import kyo.Structure
import kyo.TypeMismatchException
import scala.annotation.nowarn

/** Typed identifier for a JSON-RPC request.
  *
  * JSON-RPC 2.0 §5 allows ids to be a string, a number, or null. This enum models the
  * non-null cases; a null id is represented by `Maybe.Absent` at the call site.
  *
  * Used in `JsonRpcEndpoint.cancel`, `JsonRpcEndpoint.Pending.id`, `ExtrasEncoder`, and
  * `HandlerCtx.requestId`.
  *
  * @see [[JsonRpcEnvelope.Request]]
  * @see [[JsonRpcEnvelope.Response]]
  */
enum JsonRpcId derives CanEqual:
    case Num(value: Long)
    case Str(value: String)
end JsonRpcId

object JsonRpcId:
    @nowarn("msg=anonymous")
    given schema: Schema[JsonRpcId] = Schema.init[JsonRpcId](
        writeFn = (id, writer) =>
            id match
                case JsonRpcId.Num(n) => writer.long(n)
                case JsonRpcId.Str(s) => writer.string(s),
        readFn = reader =>
            if reader.isNil() then
                // word in string literal describes the JSON absent-value type, not a reference
                throw TypeMismatchException(Seq.empty, "number or string", "null")(using reader.frame)
            else
                try JsonRpcId.Num(reader.long())
                catch case _: TypeMismatchException => JsonRpcId.Str(reader.string())
    )
end JsonRpcId
