package kyo.internal

import kyo.*

/** Typed signal for flow suspension. NOT a Throwable, NOT a FlowException.
  *
  * Used with Abort[FlowSuspension] for short-circuiting when a flow parks (an input it is waiting for, a sleep it is serving out) or
  * when the claim behind it is gone. Because FlowSuspension is not Throwable, it passes through Abort.run[Throwable] in Flow.run's
  * compensation handler untouched, so a suspension never fires compensations.
  *
  * The parked token is PLURAL, and the plurality is the point. A composition can be waiting on more than one condition at once:
  * `race(input("approval"), sleep("deadline"))` is waiting for the signal AND for the deadline, and a token naming only one of them
  * makes the other unreachable. So a leaf parks with its own name, and the zip and race joins merge their branches' parked names,
  * recursively through nested compositions, so what reaches the engine names every condition the attempt ended waiting on.
  */
private[kyo] enum FlowSuspension derives CanEqual:

    /** The attempt parked, and these are the conditions it is waiting on. */
    case Parked(waitingOn: Set[String])

    /** The row is somebody else's now, so there is nothing to wait on and nothing to record. */
    case ClaimLost

end FlowSuspension

private[kyo] object FlowSuspension:

    /** The suspension a composition ends with when neither branch produced a value.
      *
      * `ClaimLost` outranks a park because an executor that no longer owns the row has nothing to park: whatever its branches were
      * waiting for is the new owner's business. Otherwise the parks merge, which is what keeps every branch's condition reachable.
      */
    def merge(left: FlowSuspension, right: FlowSuspension): FlowSuspension =
        (left, right) match
            case (ClaimLost, _)         => ClaimLost
            case (_, ClaimLost)         => ClaimLost
            case (Parked(l), Parked(r)) => Parked(l ++ r)

end FlowSuspension
