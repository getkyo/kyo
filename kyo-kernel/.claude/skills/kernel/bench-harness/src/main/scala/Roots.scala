import kyo.*

/** Where the suite finds the repository and its build output.
  *
  * Absolute paths were scattered across three test files, one of them naming a Metals session's bloop
  * directory whose name is unique to a single editor session on a single machine. That makes a suite
  * that cannot run anywhere else, which is a slow way of having no suite.
  *
  * Derived from this source file's own location, with an override for anyone running from elsewhere.
  */
object Roots:

    /** The bench-harness directory holding these sources. */
    val harness: Path =
        Maybe(java.lang.System.getProperty("bench.harness"))
            .map(Path(_))
            .getOrElse(Path(java.lang.System.getProperty("user.dir")))

    /** The repository worktree root, five levels above the harness directory. */
    val repo: Path =
        Maybe(java.lang.System.getProperty("bench.repo"))
            .map(Path(_))
            .getOrElse(harness / ".." / ".." / ".." / ".." / "..")

    /** Compiled classes from an ordinary sbt build, in whichever worktree holds them. */
    val classes: Path =
        Maybe(java.lang.System.getProperty("bench.classes"))
            .map(Path(_))
            .getOrElse(Path("/Users/fwbrasil/workspace/kyo/.claude/worktrees/bench-sweep/kyo-kernel/jvm/target/scala-3.8.4/classes"))

end Roots
