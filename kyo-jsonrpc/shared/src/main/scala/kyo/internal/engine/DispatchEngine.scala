package kyo.internal.engine

import kyo.*

private[kyo] object DispatchEngine:

    /** Dispatches `params` to the named route in `methodMap`. Returns Absent for unknown names. */
    def dispatch(
        methodMap: Map[String, JsonRpcRoute[?, ?, ?]],
        name: String,
        params: Structure.Value,
        ctx: JsonRpcRoute.Context
    )(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])] =
        // stdlib Map.get() returns scala.Option; match arms are interop at protocol dispatch boundary
        methodMap.get(name) match
            case Some(route) => Present(route.handle(params, ctx))
            case None        => Absent
        end match
    end dispatch

end DispatchEngine
