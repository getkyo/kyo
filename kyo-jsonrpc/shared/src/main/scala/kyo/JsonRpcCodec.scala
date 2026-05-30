package kyo

import kyo.Abort
import kyo.Frame
import kyo.Structure
import kyo.Sync

/** Encodes and decodes [[JsonRpcEnvelope]] values to and from structural `Structure.Value` trees.
  *
  * Two built-in codec instances are provided:
  *  - [[JsonRpcCodec.Strict2_0]]: strict JSON-RPC 2.0 encoding; the `"jsonrpc":"2.0"` field
  *    is required on decode and emitted on encode.
  *  - [[JsonRpcCodec.Cdp]]: Chrome DevTools Protocol dialect without the `"jsonrpc"` version
  *    field.
  *
  * Set via [[JsonRpcEndpoint.Config.codec]]. Custom implementations can be provided by
  * implementing this trait.
  *
  * @see [[JsonRpcEndpoint.Config]]
  * @see [[JsonRpcTransport.fromWire]]
  */
trait JsonRpcCodec:
    def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
    def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync
end JsonRpcCodec

object JsonRpcCodec:
    val Strict2_0: JsonRpcCodec = internal.codec.JsonRpcCodecImpl.Strict2_0
    val Cdp: JsonRpcCodec       = internal.codec.JsonRpcCodecImpl.Cdp
end JsonRpcCodec
