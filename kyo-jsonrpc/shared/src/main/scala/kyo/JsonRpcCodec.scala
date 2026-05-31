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
  *  - [[JsonRpcCodec.Lenient]]: omits the `"jsonrpc"` version field on encode; accepts messages
  *    without it on decode.
  *
  * Set via [[JsonRpcHandler.Config.codec]]. Custom implementations can be provided by
  * implementing this trait.
  *
  * @see [[JsonRpcHandler.Config]]
  * @see [[JsonRpcTransport.fromWire]]
  */
trait JsonRpcCodec:
    def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
    def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync
end JsonRpcCodec

object JsonRpcCodec:
    val Strict2_0: JsonRpcCodec = internal.codec.JsonRpcCodecImpl.Strict2_0
    val Lenient: JsonRpcCodec   = internal.codec.JsonRpcCodecImpl.Lenient

    /** The default codec: strict JSON-RPC 2.0 encoding.
      * Matches the value used by [[JsonRpcHandler.Config.default]].
      */
    val default: JsonRpcCodec = Strict2_0
end JsonRpcCodec
