package kyo.kernel

/** The marker a handler clause's continuation carries, and what it forbids.
  *
  * The continuation carries the regions that sat between handler and suspension, a bracket among them; the handler releases what they carry
  * at the region's own end, where the continuation is consumed (once for a single-shot clause, once after all resumptions for a repeated
  * one). It is valid only on this fiber, inside the clause, so everything derived from it carries `Region.NoEscape`, which stops the two
  * accidental exits: a crossing demands an `Isolate` and the derivation refuses the marker, and a holder typed without it rejects it (the row
  * being contravariant). Handing it back to the region compiles, since the region's currency carries and discharges the marker.
  * `ArrowEffect.handleFirst` is the deliberate opposite: its clause receives an unmarked continuation. The marker is phantom: rows carry no
  * runtime value, and the discharge is a cast on the row alone.
  */
object Region:

    /** The effect a confined computation carries, bounded by the effect no `Isolate` can be derived for. */
    type NoEscape = Isolate.Disallowed

    /** Removes the marker from a value the region has taken back (the clause's answer). The value is consumed on this fiber inside the scope
      * that owns what the continuation carries.
      */
    private[kyo] inline def discharge[A, S](v: A < (S & NoEscape)): A < S =
        v.asInstanceOf[A < S]

    /** Takes a continuation out of its confinement, making the caller responsible for it. Ordinary code never needs this (a clause hands its
      * continuation back to its region, a peel receives an unmarked one); it exists for the kernel's own tests, which reach runtime behaviour
      * their types forbid. What the caller takes on is what the marker stated: a
      * continuation carried past its region is refused when it re-enters a bracket the region already released (`kyo.Closed`), and one carried
      * to another fiber can have that bracket released under it.
      */
    private[kernel] inline def leak[I, O, S](cont: Arrow[I, O, S & NoEscape]): Arrow[I, O, S] =
        cont.asInstanceOf[Arrow[I, O, S]]

end Region
