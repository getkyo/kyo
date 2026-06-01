package kyo.internal

import java.io.File
import java.nio.file.Paths
import kyo.*

/** Shared classpath fixtures for kyo-tasty real-classpath fidelity tests.
  *
  * Every fidelity test that loads a real classpath MUST call `withClasspath` or one of the subset helpers. This is the canonical discovery
  * point for sbt-managed test jars and class dirs (HARD RULE 1: real classpath, not synthetic fixtures).
  *
  * Discovery uses `java.class.path` as the source of truth. The sbt test runner populates that property with the full transitive
  * test-classpath before launching the test JVM, so every jar and class dir needed by the tests is reachable.
  */
private[kyo] object TestClasspaths:

    private def hasCompiled(dir: File): Boolean =
        def walk(f: File): Boolean =
            if f.isDirectory then
                val children = f.listFiles()
                children != null && children.exists(walk)
            else f.getName.endsWith(".tasty") || f.getName.endsWith(".class")
        walk(dir)
    end hasCompiled

    /** All test-classpath entries that look like compiled Scala output: `.jar` files or directories that contain `.tasty` / `.class` files. */
    lazy val all: Seq[String] =
        val raw = sys.props.getOrElse("java.class.path", "").split(File.pathSeparator).toIndexedSeq
        raw.filter: p =>
            val f = new File(p)
            f.exists && ((f.isFile && p.endsWith(".jar")) || (f.isDirectory && hasCompiled(f)))
        .toSeq
    end all

    /** Subset: the kyo-tasty compiled classes directory or jar from the test classpath. */
    lazy val kyoTasty: Seq[String] =
        all.filter: p =>
            (p.contains("/kyo-tasty/jvm/target/") && p.endsWith("/classes")) ||
                (p.contains("/kyo-tasty_3-") && p.endsWith(".jar"))

    /** Subset: kyo-data compiled classes directory or jar.
      *
      * kyo-data is the canonical kyo type carrier (Maybe, Result, Duration, Chunk).
      */
    lazy val kyoData: Seq[String] =
        all.filter: p =>
            (p.contains("/kyo-data") && p.endsWith("/classes")) ||
                (p.contains("/kyo-data") && p.endsWith(".jar"))

    /** Subset: the scala-library jar that contains `.tasty` files.
      *
      * In Scala 3.x the jar is named `scala-library-3.N.N.jar` or `scala-library_3-N.N.N.jar` (sbt naming convention varies by version).
      */
    lazy val scalaLibrary: Seq[String] =
        sys.props
            .getOrElse("java.class.path", "")
            .split(File.pathSeparator)
            .find: p =>
                val name = Paths.get(p).getFileName.toString
                (name.startsWith("scala-library-3") || name.startsWith("scala-library_3")) && name.endsWith(".jar")
            .toSeq

    /** The canonical scala-library jar path. Throws if not discoverable. */
    lazy val scala3LibraryJar: String =
        scalaLibrary.headOption.getOrElse(
            throw new RuntimeException(
                "Cannot locate scala-library-3*.jar on java.class.path; " +
                    "ensure sbt forks the test JVM with the full classpath"
            )
        )

    /** Subset: kyo-core compiled classes directory, when available on the test classpath.
      *
      * kyo-core is a transitive dependency of kyo-tasty that uses context functions (`?=>`) internally (AllowUnsafe ?=>, Safepoint ?=>).
      * Including it extends the real-classpath coverage to include ContextFunctionN usage sites.
      */
    lazy val kyoCore: Seq[String] =
        all.filter: p =>
            (p.contains("/kyo-core") && p.endsWith("/classes")) ||
                (p.contains("/kyo-core") && p.endsWith(".jar"))

    /** A standard 3-root combo used by most fidelity tests: kyo-tasty + kyo-data + scala-library. */
    lazy val standard: Seq[String] = kyoTasty ++ kyoData ++ scalaLibrary

    /** A broader combo that adds kyo-core to the standard set, enabling ContextFunctionN coverage. */
    lazy val standardWithKyoCore: Seq[String] = standard ++ kyoCore

    /** Load a `Tasty.Classpath` from the given roots using `ErrorMode.SoftFail`.
      *
      * Returns a Kyo effect that initialises the classpath within the surrounding `Async & Scope & Abort[TastyError]` context. Call inside
      * a `run { ... }` test body.
      *
      * The `withClasspath` name is the canonical pattern that every `*FidelityTest.scala` uses (INV-001 TDD-real-classpath discipline).
      */
    def withClasspath(roots: Seq[String] = standard)(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        Tasty.Classpath.init(roots, Tasty.ErrorMode.SoftFail)

end TestClasspaths
