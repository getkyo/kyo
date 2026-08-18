package kyo.proto

import scala.annotation.nowarn
import scala.annotation.targetName

/** The outcome of one turn of a loop: continue with the state to carry, or done with a final value. An outcome is a value. */
object Loop:

    /** One state value carried into the next turn. */
    sealed abstract class Continue[A]:
        private[proto] def _1: A

    /** Two state values carried into the next turn. */
    sealed abstract class Continue2[A, B]:
        private[proto] def _1: A
        private[proto] def _2: B

    /** Continue with one state value, or done with a value of `O`. */
    opaque type Outcome[A, O] = O | Continue[A]

    /** Continue with two state values, or done with a value of `O`. */
    opaque type Outcome2[A, B, O] = O | Continue2[A, B]

    private val continueUnit: Continue[Unit] =
        new Continue[Unit]:
            val _1 = ()

    /** Continue with no state. */
    inline def continue[O]: Outcome[Unit, O] = continueUnit

    /** Continue with one state value. */
    @nowarn("msg=anonymous")
    inline def continue[A, O](inline v: A): Outcome[A, O] =
        new Continue[A]:
            val _1 = v

    /** Continue with two state values. */
    @nowarn("msg=anonymous")
    inline def continue[A, B, O](inline v1: A, inline v2: B): Outcome2[A, B, O] =
        new Continue2[A, B]:
            val _1 = v1
            val _2 = v2

    /** Done with no value. */
    @targetName("done0")
    inline def done[A]: Outcome[A, Unit] = ()

    /** Done with a value. */
    @targetName("done1")
    inline def done[A, O](inline v: O): Outcome[A, O] = v

    /** Done with a value, for a two-state loop. */
    @targetName("done2")
    inline def done[A, B, O](inline v: O): Outcome2[A, B, O] = v

end Loop
