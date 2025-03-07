package kyo.test

/** Describes a strategy for evaluating multiple effects, potentially in parallel. There are three possible execution strategies:
  * `Sequential`, `Parallel`, and `ParallelN`.
  */
sealed abstract class ExecutionStrategy

object ExecutionStrategy:

    /** Execute effects sequentially.
      */
    case object Sequential extends ExecutionStrategy

    /** Execute effects in parallel.
      */
    case object Parallel extends ExecutionStrategy

    /** Execute effects in parallel, up to the specified number of concurrent fibers.
      */
    final case class ParallelN(n: Int) extends ExecutionStrategy
end ExecutionStrategy
