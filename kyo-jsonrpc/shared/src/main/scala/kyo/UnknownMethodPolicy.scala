package kyo

/** Controls how the endpoint responds to incoming requests and notifications for methods
  * that have no registered handler.
  *
  * Three preset values cover the most common cases:
  *  - [[UnknownMethodPolicy.minimal]]: reply `MethodNotFound` on requests, silently drop notifications.
  *  - [[UnknownMethodPolicy.lsp]]: same as `minimal` but also allows `$/`-prefixed LSP meta-methods.
  *  - [[UnknownMethodPolicy.strict]]: reply `MethodNotFound` on requests, reject unknown notifications.
  *
  * Set via [[JsonRpcEndpoint.Config.unknownMethod]].
  *
  * @see [[JsonRpcEndpoint.Config]]
  */
// Hub.scala:22 smart-constructor pattern; users select .minimal / .lsp / .strict
final case class UnknownMethodPolicy private[kyo] (
    onUnknownRequest: UnknownMethodPolicy.UnknownAction,
    onUnknownNotification: UnknownMethodPolicy.UnknownAction,
    dollarPrefixOverride: Boolean
) derives CanEqual

object UnknownMethodPolicy:
    enum UnknownAction derives CanEqual:
        case ReplyMethodNotFound
        case Drop
        case Reject
    end UnknownAction

    val minimal: UnknownMethodPolicy = UnknownMethodPolicy(
        onUnknownRequest = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Drop,
        dollarPrefixOverride = false
    )

    val lsp: UnknownMethodPolicy = UnknownMethodPolicy(
        onUnknownRequest = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Drop,
        dollarPrefixOverride = true
    )

    val strict: UnknownMethodPolicy = UnknownMethodPolicy(
        onUnknownRequest = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Reject,
        dollarPrefixOverride = false
    )
end UnknownMethodPolicy
