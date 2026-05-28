package kyo

import kyo.Abort
import kyo.Frame
import kyo.Structure
import kyo.Sync

trait JsonRpcCodec:
    def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
    def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync
end JsonRpcCodec

object JsonRpcCodec:
    val Strict2_0: JsonRpcCodec = internal.JsonRpcCodecImpl.Strict2_0
    val Cdp: JsonRpcCodec       = internal.JsonRpcCodecImpl.Cdp

    private[kyo] val cdpReservedKeys: Set[String] =
        Set("id", "method", "params", "result", "error", "jsonrpc")
end JsonRpcCodec
