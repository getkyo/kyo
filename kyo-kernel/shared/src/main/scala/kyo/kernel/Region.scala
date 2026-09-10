package kyo.kernel

/** The marker a handler clause's continuation carries, and what it forbids.
  *
  * A clause's continuation carries the regions that sat between the handler and the suspension, a bracket among them, and the handler
  * releases what they carry when the clause returns. So it is valid only on this fiber, inside that clause.
  *
  * Everything derived from it carries `Region.NoEscape` in its row, which stops the two accidental exits: a crossing demands an
  * `Isolate` for the row and the derivation refuses the marker, and a holder typed without the marker does not accept it, the row being
  * contravariant. Handing it back to the region compiles, since the region's currency carries the marker and discharges it.
  *
  * `ArrowEffect.handleFirst` is the deliberate opposite and does not go through here: its clause receives an unmarked continuation.
  *
  * The marker has no runtime presence: rows are phantom, and the discharge is a cast on the row alone.
  */
object Region:

    /** The effect a confined computation carries. Named for what it forbids, bounded by the effect no `Isolate` can be derived for. */
    type NoEscape = Isolate.Disallowed

    /** Removes the marker from a value the region has taken back: the clause's answer, entering the region it belongs to.
      *
      * The value is consumed on this fiber, inside the scope that owns what the continuation carries, which is what the marker stood for.
      * The row is phantom, so this is a representation assertion on the row alone.
      */
    private[kyo] inline def discharge[A, S](v: A < (S & NoEscape)): A < S =
        v.asInstanceOf[A < S]

    /** Takes a continuation out of its confinement, making the caller responsible for it.
      *
      * Ordinary code never needs this: a clause hands its continuation back to its region, and a peel receives an unmarked one. It is
      * here for the kernel's own tests, which reach the runtime behaviour their types would otherwise forbid, and it is spelled out so
      * those sites are one grep away. What the caller takes on is what the marker stated: a continuation carried past its region is
      * refused when it re-enters a bracket the region already released (`kyo.Closed`), and one carried to another fiber can have that
      * bracket released under it.
      */
    private[kernel] inline def leak[I, O, S](cont: Arrow[I, O, S & NoEscape]): Arrow[I, O, S] =
        cont.asInstanceOf[Arrow[I, O, S]]

end Region
