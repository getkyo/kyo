package kyo.internal

import kyo.*

/** Typed signal for flow suspension. NOT a Throwable, NOT a FlowException.
  *
  * Used with Abort[FlowSuspension] for short-circuiting when a flow needs to park (sleep, input wait, claim lost). Because FlowSuspension
  * is not Throwable, it passes through Abort.run[Throwable] in Flow.run's compensation handler untouched — suspension never fires
  * compensations.
  */
private[kyo] enum FlowSuspension derives CanEqual:
    case Sleeping(name: String, until: Instant)
    case WaitingForInput(name: String)
    case ClaimLost
    case StepAlreadyCompleted(name: String)
end FlowSuspension
