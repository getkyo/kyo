package kyobench

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

/** Compiles one fixture file with an in-process dotc against one kernel's classes per benchmark
  * invocation. The corpus classpath is this process's own classpath (kyo-data and its
  * dependencies, no kernel) plus the kernel's classes directory, so only the kernel varies.
  * JMH supplies the methodology: a forked JVM per (fixture, kernel) combination isolates all
  * shared-JVM state, warmup iterations bring the compiler's own code to steady state, and
  * per-iteration output makes progress observable. A compile with diagnostics fails the run, so
  * a broken fixture cannot masquerade as a fast one. Fixtures live outside the kyo package so
  * Frame derivation is the real per-site macro cost, and each isolates one compile-cost driver.
  *
  * `fixtures/` is the shared corpus and is what both kernels compile, so the measured text is
  * identical wherever the two kernels agree on the surface a fixture uses. Where they do not,
  * `fixtures-kernel2/` holds an override with the same computation shape written against the
  * newer surface, and the override is what kernel2 compiles. Only HandleSites needs one today:
  * the older kernel's `handle` takes a single clause and the result is driven with `.eval`,
  * while kernel2 spells the same region as `handleCont` with a done clause under `Eval`. A
  * delta on an overridden fixture carries that difference in it and is read accordingly.
  *
  * Quick in-process loop for diagnosis: pass -f 0 to skip forking.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 8, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class CompileBench:

    @Param(Array(
        "Baseline",
        "EffectRowGenerics",
        "FlatMapChains",
        "ForCompDeep25",
        "ForCompShallow",
        "ForComprehensions",
        "HandleSites",
        "MapChain10",
        "MapChainDeep100",
        "MapChainWide100",
        "NestedMaps",
        "SuspendSites",
        "TagDerivation"
    ))
    var fixture: String = ""

    private var oldFixturePath: String = ""
    private var newFixturePath: String = ""
    private var oldCp: String          = ""
    private var newCp: String          = ""
    private var outOld: File           = null
    private var outNew: File           = null

    @Setup
    def setup(): Unit =
        // the forked JVM starts in the project directory; the repo root is
        // the closest ancestor holding the fixtures
        val root =
            Iterator.iterate(new File(".").getAbsoluteFile)(_.getParentFile)
                .takeWhile(_ != null)
                .find(d => new File(d, "kyo-compile-bench/fixtures").isDirectory)
                .getOrElse(sys.error("repo root not found from " + new File(".").getAbsolutePath))
        val file = new File(root, s"kyo-compile-bench/fixtures/$fixture.scala")
        require(file.isFile, s"missing fixture: $file")
        oldFixturePath = file.getAbsolutePath
        // the shared fixture unless the surface diverges, in which case the override is the
        // same computation shape written against kernel2
        val override2 = new File(root, s"kyo-compile-bench/fixtures-kernel2/$fixture.scala")
        newFixturePath = (if override2.isFile then override2 else file).getAbsolutePath
        val base = sys.props("java.class.path")
        def kernelCp(dir: String): String =
            val d = new File(root, dir)
            require(d.isDirectory, s"missing kernel classes: $d")
            base + File.pathSeparator + d.getAbsolutePath
        end kernelCp
        oldCp = kernelCp("kyo-kernel/jvm/target/scala-3.8.4/classes")
        newCp = kernelCp("kyo-kernel2/jvm/target/scala-3.8.4/classes")
        outOld = Files.createTempDirectory("kyocb-old").toFile
        outNew = Files.createTempDirectory("kyocb-new").toFile
    end setup

    private def compile(cp: String, out: File, path: String): Unit =
        val rep = dotty.tools.dotc.Main.process(Array("-classpath", cp, "-d", out.getAbsolutePath, path))
        require(!rep.hasErrors, s"$fixture failed to compile")

    @Benchmark
    def oldKernel(): Unit = compile(oldCp, outOld, oldFixturePath)

    @Benchmark
    def newKernel(): Unit = compile(newCp, outNew, newFixturePath)

end CompileBench
