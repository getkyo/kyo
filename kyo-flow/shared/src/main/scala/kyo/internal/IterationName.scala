package kyo.internal

/** Naming convention for scheduled loop iterations and their durable sleeps.
  *
  * Scheduled loops checkpoint each iteration as a separate step in the store. This object centralizes the naming scheme so the interpreter
  * (Flow.run) and progress tracker (Progress.build) share a single source of truth.
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

    /** Whether `completed` is an iteration step of loop `base`. */
    def isIteration(completed: String, base: String): Boolean =
        completed.startsWith(s"$base#")

end IterationName
