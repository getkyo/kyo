package kyo.internal

/** Naming convention for scheduled loop iterations and their durable sleeps.
  *
  * Scheduled loops checkpoint each iteration as a separate step in the store. This object is the one place the scheme is written down, and
  * both sides of the interpreter's scheduled-loop arm derive their names from it: the side that WRITES a checkpoint and the side that
  * reads one back on resume. A reader that only has to attribute a recorded path to the node that owns it walks up through the reserved
  * characters instead, since a fan-out's item is the same shape as an iteration.
  *
  * Given a loop named "sum":
  *   - iteration 0 step: "sum#0"
  *   - iteration 0 sleep: "sum##0"
  *   - iteration 1 step: "sum#1"
  *   - etc.
  */
private[kyo] object IterationName:

    /** Step name for iteration `n` of loop `base`. */
    def step(base: String, n: Int): String = s"$base#$n"

    /** Sleep name for iteration `n` of loop `base`. */
    def sleep(base: String, n: Int): String = s"$base##$n"

end IterationName
