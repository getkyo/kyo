package kyo

import kyo.Abort
import kyo.Async
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Schema
import kyo.Structure

sealed trait JsonRpcMethod[+S]:
    def name: String
    def kind: JsonRpcMethod.Kind
    private[kyo] def schemaIn: Schema[?]
    private[kyo] def schemaOut: Schema[?]
    private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using Frame): Structure.Value < (Async & Abort[JsonRpcError])
end JsonRpcMethod

object JsonRpcMethod:
    enum Kind derives CanEqual:
        case Request, Notification

    def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, HandlerCtx) => Out < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S] =
        val capturedName                   = name
        val capturedSchemaIn: Schema[In]   = summon[Schema[In]]
        val capturedSchemaOut: Schema[Out] = summon[Schema[Out]]
        val ev                             = summon[(Async & Abort[JsonRpcError]) <:< S]
        new RequestMethod(capturedName, capturedSchemaIn, capturedSchemaOut, handler, ev)
    end apply

    def apply[In: Schema, Out: Schema, S](name: String)(handler: In => Out < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S] =
        apply[In, Out, S](name)((in, _ctx) => handler(in))

    def notification[In: Schema, S](name: String)(handler: (In, HandlerCtx) => Unit < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S] =
        val capturedName                 = name
        val capturedSchemaIn: Schema[In] = summon[Schema[In]]
        val ev                           = summon[(Async & Abort[JsonRpcError]) <:< S]
        new NotificationMethod(capturedName, capturedSchemaIn, handler, ev)
    end notification

    final private class RequestMethod[In, Out, S](
        val name: String,
        in: Schema[In],
        out: Schema[Out],
        handler: (In, HandlerCtx) => Out < S,
        ev: (Async & Abort[JsonRpcError]) <:< S
    ) extends JsonRpcMethod[S]:
        val kind                              = Kind.Request
        private[kyo] val schemaIn: Schema[?]  = in
        private[kyo] val schemaOut: Schema[?] = out

        private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using
            fr: Frame
        ): Structure.Value < (Async & Abort[JsonRpcError]) =
            val computation: Structure.Value < (S & Abort[JsonRpcError]) =
                Structure.decode[In](params)(using in, fr) match
                    case Result.Success(decoded) =>
                        Abort.run[JsonRpcError](handler(decoded, ctx)).map:
                            case Result.Success(result) =>
                                Structure.encode[Out](result)(using out, fr)
                            case Result.Failure(err) =>
                                Abort.fail(err)
                            case Result.Panic(t) =>
                                Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
                    case Result.Failure(e) =>
                        Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                    case Result.Panic(t) =>
                        Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
            ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(computation)
        end handle
    end RequestMethod

    final private class NotificationMethod[In, S](
        val name: String,
        in: Schema[In],
        handler: (In, HandlerCtx) => Unit < S,
        ev: (Async & Abort[JsonRpcError]) <:< S
    ) extends JsonRpcMethod[S]:
        val kind                              = Kind.Notification
        private[kyo] val schemaIn: Schema[?]  = in
        private[kyo] val schemaOut: Schema[?] = summon[Schema[Unit]]

        private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using
            fr: Frame
        ): Structure.Value < (Async & Abort[JsonRpcError]) =
            val computation: Structure.Value < (S & Abort[JsonRpcError]) =
                Structure.decode[In](params)(using in, fr) match
                    case Result.Success(decoded) =>
                        Abort.run[JsonRpcError](handler(decoded, ctx)).map:
                            case Result.Success(_) =>
                                (Structure.Value.Null: Structure.Value)
                            case Result.Failure(err) =>
                                Abort.fail(err)
                            case Result.Panic(t) =>
                                Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
                    case Result.Failure(e) =>
                        Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                    case Result.Panic(t) =>
                        Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
            ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(computation)
        end handle
    end NotificationMethod
end JsonRpcMethod
