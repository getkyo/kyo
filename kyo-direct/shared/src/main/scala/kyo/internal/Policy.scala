package kyo.internal

import kyo.*

/** What a boundary does beyond the shared lowering rules.
  *
  * @param liftPure
  *   when the body has no sequencing marker, return `Kyo.lift[A, Any](body)` (a `direct` block keeps the type `A < Any`) or the body
  *   unchanged with its own type (a `.kyo` definition stays pure).
  * @param pureOnly
  *   when present, a body with a sequencing marker is rejected with this message (the `.kyo` field boundary).
  * @param shiftPendingLambdas
  *   whether a shift entry fires on a lambda already typed `A => B < S` with no marker inside it, which the `.kyo` plugin
  *   produces by wrapping every lambda body in a boundary; `direct` leaves such a call alone.
  */
final private[kyo] case class Policy(
    liftPure: Boolean,
    pureOnly: Maybe[String],
    shiftPendingLambdas: Boolean
)

private[kyo] object Policy:

    /** The policy of a `direct` block. */
    val direct: Policy = Policy(liftPure = true, pureOnly = Absent, shiftPendingLambdas = false)
end Policy
