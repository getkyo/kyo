import sbt.*

/** The build-wide task concurrency caps, read from the environment.
  *
  * `SBT_TASK_LIMIT` caps how many tasks run at once (default: one per core). `SBT_COMPILE_LIMIT` raises that cap for compile
  * tasks alone: up to `compile` tasks may run together when every task beyond the first `task` of them is a compile. Unset, or
  * not above `SBT_TASK_LIMIT`, it changes nothing, so dropping it restores the plain `SBT_TASK_LIMIT` schedule exactly.
  *
  * Only compiles get the wider cap because the memory reasons for `SBT_TASK_LIMIT=1` on CI are linking and test forks: a Scala.js or
  * Wasm link holds the whole program's IR in the driver heap, the Native optimizer and its forked clang jobs sit beside it, and test
  * forks carry their own heaps. A compile holds one module's trees. So while any task carrying one of the `exclusive` tags runs, the
  * total stays at `task`.
  */
object TaskLimits {

    private def env(name: String): Option[Int] = sys.env.get(name).filter(_ != "0").map(_.toInt)

    val task: Int = env("SBT_TASK_LIMIT").getOrElse(java.lang.Runtime.getRuntime.availableProcessors())

    val compile: Int = env("SBT_COMPILE_LIMIT").fold(task)(_ max task)

    /** Plain `Tags.limitAll(task)` when the limits are equal. The custom rule must stay monotonic (adding a task never turns an invalid
      * set valid), which sbt's scheduler assumes: every clause only tightens as a count grows.
      */
    def rule(exclusive: Seq[Tags.Tag]): Tags.Rule =
        if (compile == task) Tags.limitAll(task)
        else
            Tags.customLimit { tags =>
                def count(tag: Tags.Tag): Int = tags.getOrElse(tag, 0)
                val all                       = count(Tags.All)
                all <= compile && all - count(Tags.Compile) <= task && (all <= task || exclusive.forall(count(_) == 0))
            }
}
