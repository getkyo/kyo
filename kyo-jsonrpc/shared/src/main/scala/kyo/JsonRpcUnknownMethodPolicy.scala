package kyo

/** Controls how the endpoint responds to incoming requests and notifications for methods
  * that have no registered handler.
  *
  * Three preset values cover the most common cases:
  *  - [[JsonRpcUnknownMethodPolicy.minimal]]: reply `MethodNotFound` on requests, silently drop notifications.
  *  - [[JsonRpcUnknownMethodPolicy.lsp]]: same as `minimal` but silently ignores notifications whose method name
  *    starts with `$/` (the LSP meta-method prefix).
  *  - [[JsonRpcUnknownMethodPolicy.strict]]: reply `MethodNotFound` on requests, reject unknown notifications.
  *
  * For custom silent-ignore predicates supply a value for [[ignoreUnknownNotification]]:
  * {{{
  * JsonRpcUnknownMethodPolicy.minimal.copy(
  *   ignoreUnknownNotification = _.startsWith("$/")
  * )
  * }}}
  *
  * Set via [[JsonRpcHandler.Config.unknownMethod]].
  *
  * @param onUnknownRequest
  *   action when an incoming request has no registered handler.
  * @param onUnknownNotification
  *   action when an incoming notification has no registered handler, unless overridden by
  *   [[ignoreUnknownNotification]].
  * @param ignoreUnknownNotification
  *   predicate called with the notification method name. When it returns `true` the notification is
  *   silently discarded regardless of [[onUnknownNotification]]. Default: `_ => false` (never silently
  *   ignore).
  * @see [[JsonRpcHandler.Config]]
  */
// Hub.scala:22 smart-constructor pattern; users select .minimal / .lsp / .strict or copy with custom predicate
final case class JsonRpcUnknownMethodPolicy private[kyo] (
    onUnknownRequest: JsonRpcUnknownMethodPolicy.UnknownAction,
    onUnknownNotification: JsonRpcUnknownMethodPolicy.UnknownAction,
    ignoreUnknownNotification: String => Boolean
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
        ignoreUnknownNotification = _ => false
    )

    val lsp: JsonRpcUnknownMethodPolicy = JsonRpcUnknownMethodPolicy(
        onUnknownRequest = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Drop,
        ignoreUnknownNotification = _.startsWith("$/")
    )

    val strict: JsonRpcUnknownMethodPolicy = JsonRpcUnknownMethodPolicy(
        onUnknownRequest = UnknownAction.ReplyMethodNotFound,
        onUnknownNotification = UnknownAction.Reject,
        ignoreUnknownNotification = _ => false
    )
end JsonRpcUnknownMethodPolicy
