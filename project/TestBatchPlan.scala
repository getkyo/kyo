import sbt.*
import sbt.Keys.*
import sbt.internal.BuildDependencies
import scala.collection.mutable

/** Computes dependency-aware, size-balanced test batches for a platform and writes them to
  * `target/test-batches-<platform>.txt` (one batch per line, space-separated sbt project ids).
  *
  * Each batch is meant to run in its OWN sbt process (see `scripts/test-batched.sh`). sbt compiles
  * in-process and G1 does not uncommit an idle driver heap, so the ONLY thing that returns the
  * accumulated compile heap to the OS is process exit. Splitting the work across fresh JVMs is what
  * keeps the driver from over-committing the memory-constrained CI runners.
  *
  * Weight = test-source LOC per module (a build-time proxy for compile cost; no compile needed).
  * Modules are packed under `KYO_BATCH_BUDGET` (default 110000) test-LOC; a single module heavier
  * than the budget gets its own batch (e.g. kyo-tasty).
  *
  * The only cross-batch dependency constraint is `test->test` (currently just kyo-schema ->
  * kyo-data): modules are topologically ordered so a `test->test` dependency lands in an
  * earlier-or-equal batch. Every other test-scoped edge is `test->compile`, satisfied by the shared
  * main-compile pass the runner performs before the batches.
  *
  * Completeness is asserted: every enumerated test project lands in exactly one batch, so a newly
  * added module can never be silently skipped.
  */
object TestBatchPlan {

    private val platformNames = Set("JVM", "JS", "Native", "Wasm")
    private def log(m: String): Unit = println(s"[testBatchPlan] $m")
    private def budget: Int          = sys.env.get("KYO_BATCH_BUDGET").map(_.toInt).getOrElse(110000)

    def command: Command = Command.args("testBatchPlan", "<platform>") { (state, args) =>
        val platform  = args.flatMap(a => platformNames.find(_.equalsIgnoreCase(a))).headOption.getOrElse("JVM")
        val extracted = Project.extract(state)
        val structure = extracted.structure
        val data      = structure.data
        val scala3    = extracted.get(scalaVersion)
        val bd        = extracted.get(buildDependencies)
        val excluded  = Set("kyoJVM", "kyoJS", "kyoNative", "kyoWasm")

        val testable = structure.allProjectRefs.filter { ref =>
            val name = ref.project
            !excluded.contains(name) &&
            matchesPlatform(name, platform) &&
            (ref / crossScalaVersions).get(data).getOrElse(Nil).contains(scala3)
        }
        val testableSet = testable.toSet

        // Weight: total lines across this project's Test source directories.
        def loc(f: File): Int = try IO.readLines(f).size
        catch { case _: Throwable => 0 }
        val weight: Map[ProjectRef, Int] = testable.map { ref =>
            val dirs = (ref / Test / unmanagedSourceDirectories).get(data).getOrElse(Nil)
            ref -> dirs.flatMap(d => (d ** "*.scala").get).map(loc).sum
        }.toMap

        // test->test edges: the dependent's test needs the dependency's test compiled first.
        val testTestDeps: Map[ProjectRef, Set[ProjectRef]] = testable.map { ref =>
            val deps = bd.classpath.getOrElse(ref, Nil).collect {
                case d if d.configuration.exists(_.contains("test->test")) && testableSet(d.project) => d.project
            }.toSet
            ref -> deps
        }.toMap

        val ordered = topoSort(testable, testTestDeps, weight)

        // First-fit packing: a module goes in the earliest batch that has room AND is no earlier than
        // any of its test->test dependencies' batches. Oversize modules get a solo batch.
        val binOf      = mutable.LinkedHashMap[ProjectRef, Int]()
        val binWeight  = mutable.ArrayBuffer[Int]()
        val binMembers = mutable.ArrayBuffer[mutable.ListBuffer[ProjectRef]]()
        def newBin(): Int = { binWeight += 0; binMembers += mutable.ListBuffer.empty[ProjectRef]; binWeight.size - 1 }

        for (m <- ordered) {
            val w      = weight.getOrElse(m, 0)
            val minBin = testTestDeps(m).flatMap(binOf.get).foldLeft(0)(_ max _)
            val placed =
                if (w > budget) newBin()
                else
                    (minBin until binMembers.size)
                        .find(i => binWeight(i) + w <= budget && binMembers(i).forall(x => weight.getOrElse(x, 0) <= budget))
                        .getOrElse(newBin())
            binOf(m) = placed
            binWeight(placed) += w
            binMembers(placed) += m
        }

        val missing = testableSet -- binOf.keySet
        require(missing.isEmpty, s"[testBatchPlan] modules not placed in any batch: ${missing.map(_.project).mkString(", ")}")

        val batches = binMembers.filter(_.nonEmpty).map(_.toList).toList
        val out     = file(s"target/test-batches-$platform.txt")
        IO.write(out, batches.map(_.map(_.project).mkString(" ")).mkString("\n") + "\n")

        log(s"platform=$platform budget=$budget test-LOC=${weight.values.sum} projects=${testable.size} batches=${batches.size}")
        batches.zipWithIndex.foreach { case (b, i) =>
            log(f"  batch ${i + 1}%2d  ${b.map(x => weight.getOrElse(x, 0)).sum}%7d LOC  ${b.map(_.project).mkString(", ")}")
        }
        log(s"written to $out")

        // Non-primary Scala versions (2.13 cross-builds, 2.12 plugins). These module sets are small and
        // do not over-commit, so the runner tests them un-batched via testKyo after the batched primary run.
        val scala2 = structure.allProjectRefs
            .flatMap(r => (r / crossScalaVersions).get(data).getOrElse(Nil))
            .filter(_.startsWith("2."))
            .distinct
            .sorted
        val scala2Out = file(s"target/test-batches-$platform-scala2.txt")
        IO.write(scala2Out, if (scala2.isEmpty) "" else scala2.mkString("\n") + "\n")
        log(s"non-primary scala versions: ${if (scala2.isEmpty) "(none)" else scala2.mkString(", ")}")
        state
    }

    /** Stable topological order: a project's test->test dependencies come before it; ties break by
      * descending weight then name (so the packer sees heavy modules first for tighter packing).
      */
    private def topoSort(
        refs: Seq[ProjectRef],
        deps: Map[ProjectRef, Set[ProjectRef]],
        weight: Map[ProjectRef, Int]
    ): Seq[ProjectRef] = {
        val visited = mutable.LinkedHashSet[ProjectRef]()
        def order(rs: Iterable[ProjectRef]): Seq[ProjectRef] =
            rs.toSeq.sortBy(r => (-weight.getOrElse(r, 0), r.project))
        def visit(r: ProjectRef): Unit =
            if (!visited.contains(r)) {
                order(deps.getOrElse(r, Set.empty)).foreach(visit)
                visited += r
            }
        order(refs).foreach(visit)
        visited.toSeq
    }

    private def matchesPlatform(name: String, platform: String): Boolean = platform match {
        case "JVM"    => !name.endsWith("JS") && !name.endsWith("Native") && !name.endsWith("Wasm")
        case "JS"     => name.endsWith("JS")
        case "Native" => name.endsWith("Native")
        case "Wasm"   => name.endsWith("Wasm")
        case _        => false
    }
}
