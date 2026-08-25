import Model.*
import kyo.*

/** Phase 2 of the QA plan: the guards, driven against real repositories.
  *
  * A guard that has never refused anything is a comment. Each case here makes the tool face the situation it is supposed to reject.
  */
object QaGuards extends KyoApp:

    def check(name: String, cond: Boolean, detail: String = ""): Unit =
        println(if cond then s"  ok   $name" else s"  FAIL $name${if detail.nonEmpty then s"  <- $detail" else ""}")

    /** Runs an effect and reports whether it failed, and with what message. */
    def refusal[A](v: => A < (Async & Bench.Fail))(using Frame): Maybe[String] < Async =
        Abort.run[Throwable](v).map {
            case Result.Success(_) => Maybe.empty
            case Result.Failure(e) => Maybe(e.toString)
            case Result.Panic(e)   => Maybe(e.toString)
        }

    run {
        val primary  = Path("/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus")
        val throwaway = Path("/Users/fwbrasil/workspace/kyo/.claude/worktrees/bench-sweep")

        for
            _ <- Console.printLine("P2.1 a worktree on a branch is refused")
            r1 <- refusal(Bench.requireThrowaway(primary))
            _  = check("refused", r1.isDefined, "accepted the primary worktree")
            _  = check("names the requirement", r1.exists(_.contains("detached")), r1.getOrElse(""))

            _  <- Console.printLine("P2.2 a detached throwaway worktree is accepted")
            r2 <- refusal(Bench.requireThrowaway(throwaway))
            _   = check("accepted", r2.isEmpty, r2.getOrElse(""))

            _  <- Console.printLine("P2.3 a dirty worktree is refused")
            dirty = throwaway / "qa-dirty-marker.txt"
            _  <- dirty.write("scratch")
            r3 <- refusal(Bench.requireClean(throwaway))
            _  <- dirty.remove
            _   = check("a stray untracked file alone does not block", r3.isEmpty, r3.getOrElse(""))

            edited = throwaway / "kyo-kernel/shared/src/main/scala/kyo/kernel/proto/Arrow.scala"
            before <- edited.read
            _      <- edited.append("\n// qa scratch\n")
            r4     <- refusal(Bench.requireClean(throwaway))
            _       = check("a tracked edit is refused", r4.isDefined, "accepted a dirty tree")
            _       = check("names the paths", r4.exists(_.contains("Arrow.scala")), r4.getOrElse(""))

            _  <- Console.printLine("P2.6 a source edit during a leg invalidates it")
            h1 <- Bench.treeHash(throwaway, Cli.protoPaths)
            _  <- edited.write(before)
            h2 <- Bench.treeHash(throwaway, Cli.protoPaths)
            _   = check("hash moves when sources move", h1 != h2, s"$h1 vs $h2")
            h3 <- Bench.treeHash(throwaway, Cli.protoPaths)
            _   = check("hash is stable when they do not", h2 == h3, s"$h2 vs $h3")

            _  <- Console.printLine("P2.7 declaredRows counts bare @Benchmark only")
            n  <- Bench.declaredRows(throwaway)
            _   = check("counts 15, not 16", n == 15, s"got $n")

            _  <- Console.printLine("P2.8 markers read from a real tree")
            ms <- Bench.readMarkers(throwaway, Cli.markerSpecs)
            _   = check("markers found", ms.exists(_.count > 0), ms.toString)
            _  <- Console.printLine(s"       ${ms.map(m => s"${m.name}=${m.count}").mkString(" ")}")

            _ <- Console.printLine("\nPHASE 2 DONE")
        yield ()
        end for
    }
end QaGuards
