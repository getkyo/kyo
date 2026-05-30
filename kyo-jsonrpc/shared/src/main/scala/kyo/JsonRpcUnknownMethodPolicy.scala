package kyo

/** Controls how the endpoint responds to incoming requests and notifications for methods
  * that have no registered handler.
  *
  * Three preset values cover the most common cases:
  *  - [[JsonRpcUnknownMethodPolicy.minimal]]: reply `MethodNotFound` on requests, silently drop notifications.
  *  - [[JsonRpcUnknownMethodPolicy.lsp]]: same as `minimal` but also allows `$/`-prefixed LSP meta-methods.
  *  - [[JsonRpcUnknownMethodPolicy.strict]]: reply `MethodNotFound` on requests, reject unknown notifications.
  *
  * Set via [[JsonRpcHandler.Config.unknownMethod]].
  *
  * @see [[JsonRpcHandler.Config]]
  */
// Hub.scala:22 smart-constructor pattern; users select .minimal / .lsp / .strict
final case class JsonRpcUnknownMethodPolicy private[kyo] (
    onUnknownRequest: JsonRpcUnknownMethodPolicy.UnknownAction,
    onUnknownNotification: JsonRpcUnknownMethodPolicy.UnknownAction,
    dollarPrefixOverride: Boolean
) derives CanEqual

object JsonRpcUnknownMethodPolicy:
    enum UnknownAction derives CanEqual:
        case ReplyMethodNotFound
        case Drop
        case Reject
    end UnknownAction

    val minimal: JsonRpcUnknownMethodPolicy = JsonRpcUnknownMethodPolicy(
        onUnknownRequest = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Drop,
        dollarPrefixOverride = false
    )

    val lsp: JsonRpcUnknownMethodPolicy = JsonRpcUnknownMethodPolicy(
        onUnknownRequest = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Drop,
        dollarPrefixOverride = true
    )

    val strict: JsonRpcUnknownMethodPolicy = JsonRpcUnknownMethodPolicy(
        onUnknownRequest = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Reject,
        dollarPrefixOverride = false
    )
end JsonRpcUnknownMethodPolicy
