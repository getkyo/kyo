package kyo.kernel.bench

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

/** Compiles one fixture file with an in-process dotc per benchmark invocation. The classpath is
  * this forked JVM's own, so fixtures compile against exactly the kernel classes the bench runs
  * with, and a fork per fixture isolates shared-JVM state. A compile with errors fails the
  * run, so a broken fixture cannot masquerade as a fast one. Fixtures live outside the kyo package
  * so Frame derivation is the real per-site macro cost, and each isolates one compile-cost driver.
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
    private val cp: String          = sys.props("java.class.path")
    private val out: File           = Files.createTempDirectory("kyocb").toFile

    @Setup
    def setup(): Unit =
        fixturePath = CompileBench.fixture("fixtures", fixture).getAbsolutePath

    @Benchmark
    def kernel(): Unit =
        val rep = dotty.tools.dotc.Main.process(Array("-classpath", cp, "-d", out.getAbsolutePath, fixturePath))
        require(!rep.hasErrors, s"$fixture failed to compile")
    end kernel

end CompileBench

object CompileBench:

    private val resources = "kyo-kernel/jvm/src/jmh/resources"

    /** A fixture file by set and name, resolved from the closest ancestor of the working directory holding the fixture sets, since the
      * forked JVM starts in the project directory.
      */
    def fixture(set: String, name: String): File =
        val root =
            Iterator.iterate(new File(".").getAbsoluteFile)(_.getParentFile)
                .takeWhile(_ != null)
                .find(d => new File(d, s"$resources/fixtures").isDirectory)
                .getOrElse(sys.error("repo root not found from " + new File(".").getAbsolutePath))
        val file = new File(root, s"$resources/$set/$name.scala")
        require(file.isFile, s"missing fixture: $file")
        file
    end fixture

end CompileBench
