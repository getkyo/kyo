package kyo

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
end UnknownMethodPolicy
