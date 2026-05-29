// flow-allow: PUBLIC id ADT referenced by JsonRpcEndpoint.cancel, Pending.id, ExtrasEncoder, HandlerCtx.requestId
package kyo

import kyo.Schema
import kyo.Structure
import kyo.TypeMismatchException
import scala.annotation.nowarn

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
                // flow-allow: word in string literal describes the JSON absent-value type, not a reference
                throw TypeMismatchException(Seq.empty, "number or string", "null")(using reader.frame)
            else
                try JsonRpcId.Num(reader.long())
                catch case _: TypeMismatchException => JsonRpcId.Str(reader.string())
    )
end JsonRpcId
