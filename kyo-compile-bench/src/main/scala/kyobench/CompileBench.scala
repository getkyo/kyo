package kyobench

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

/** Compiles one fixture file with an in-process dotc against the kernel's classes per benchmark
  * invocation. The corpus classpath is this process's own classpath (kyo-data and its
  * dependencies, no kernel) plus the kernel's classes directory. JMH supplies the methodology: a
  * forked JVM per fixture isolates all shared-JVM state, warmup iterations bring the compiler's
  * own code to steady state, and per-iteration output makes progress observable. A compile with
  * diagnostics fails the run, so a broken fixture cannot masquerade as a fast one. Fixtures live
  * outside the kyo package so Frame derivation is the real per-site macro cost, and each isolates
  * one compile-cost driver.
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

    private var fixturePath: String = ""
    private var cp: String          = ""
    private var out: File           = null

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
        fixturePath = file.getAbsolutePath
        val classes = new File(root, "kyo-kernel/jvm/target/scala-3.8.4/classes")
        require(classes.isDirectory, s"missing kernel classes: $classes")
        cp = sys.props("java.class.path") + File.pathSeparator + classes.getAbsolutePath
        out = Files.createTempDirectory("kyocb").toFile
    end setup

    @Benchmark
    def kernel(): Unit =
        val rep = dotty.tools.dotc.Main.process(Array("-classpath", cp, "-d", out.getAbsolutePath, fixturePath))
        require(!rep.hasErrors, s"$fixture failed to compile")
    end kernel

end CompileBench
