package kyo

/** Configures how the endpoint sends and receives request cancellation notifications.
  *
  * A `JsonRpcCancellationPolicy` specifies the method name, parameter encoding/decoding, whether the
  * cancelled request still expects a reply, and which methods are protected from cancellation.
  *
  * Set via [[JsonRpcHandler.Config.cancellation]].
  *
  * @param cancelMethod
  *   the notification method name used for cancellation.
  * @param encodeParams
  *   encodes a `(requestId, reason)` pair into the notification params value.
  * @param decodeParams
  *   decodes an inbound notification params value back to the target request id.
  * @param expectReplyForCancelledRequest
  *   when `true`, the server still sends a reply after cancellation; when `false`, the handler
  *   is interrupted and no reply is expected.
  * @param cancelledError
  *   the error surfaced to the caller when their request is cancelled. Falls back to
  *   `-32800 "Request cancelled"` when `Absent`.
  * @param protectedMethods
  *   method names that cannot be cancelled; cancel attempts for these are silently ignored.
  * @see [[JsonRpcHandler.Config]]
  */
final case class JsonRpcCancellationPolicy(
    cancelMethod: String,
    encodeParams: JsonRpcCancellationPolicy.ParamsEncoder,
    decodeParams: JsonRpcCancellationPolicy.ParamsDecoder,
    expectReplyForCancelledRequest: Boolean,
    cancelledError: Maybe[JsonRpcError],
    protectedMethods: Set[String]
) derives CanEqual

object JsonRpcCancellationPolicy:
    type ParamsEncoder = (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync
    type ParamsDecoder = Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync
end JsonRpcCancellationPolicy
