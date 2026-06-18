package kyo.internal

import java.io.File
import java.nio.file.Paths
import kyo.*

/** Shared classpath fixtures for kyo-tasty real-classpath fidelity tests.
  *
  * Every fidelity test that loads a real classpath MUST call `withClasspath` or one of the subset helpers. This is the canonical discovery
  * point for sbt-managed test jars and class dirs.
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
        raw.filter { p =>
            val f = new File(p)
            f.exists && ((f.isFile && p.endsWith(".jar")) || (f.isDirectory && hasCompiled(f)))
        }
            .toSeq
    end all

    /** Subset: the kyo-tasty compiled classes directory or jar from the test classpath. */
    lazy val kyoTasty: Seq[String] =
        all.filter { p =>
            (p.contains("/kyo-tasty/jvm/target/") && p.endsWith("/classes")) ||
            (p.contains("/kyo-tasty_3-") && p.endsWith(".jar"))
        }

    /** Subset: kyo-data compiled classes directory or jar.
      *
      * kyo-data is the canonical kyo type carrier (Maybe, Result, Duration, Chunk).
      */
    lazy val kyoData: Seq[String] =
        all.filter { p =>
            (p.contains("/kyo-data") && p.endsWith("/classes")) ||
            (p.contains("/kyo-data") && p.endsWith(".jar"))
        }

    /** Subset: the scala-library jar that contains `.tasty` files.
      *
      * In Scala 3.x the jar is named `scala-library-3.N.N.jar` or `scala-library_3-N.N.N.jar` (sbt naming convention varies by version).
      */
    lazy val scalaLibrary: Seq[String] =
        sys.props
            .getOrElse("java.class.path", "")
            .split(File.pathSeparator)
            .find { p =>
                val name = Paths.get(p).getFileName.toString
                (name.startsWith("scala-library-3") || name.startsWith("scala-library_3")) && name.endsWith(".jar")
            }
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
        all.filter { p =>
            (p.contains("/kyo-core") && p.endsWith("/classes")) ||
            (p.contains("/kyo-core") && p.endsWith(".jar"))
        }

    /** Subset: the internal fixtures compiled classes directory, when available on the test classpath.
      *
      * The internal fixtures sub-project provides the cross-platform fixture TASTy files (PlainClass, SomeCaseClass, Animal, Vehicle, etc.) that mirror the
      * embedded fixture set used by JS/Native TestClasspaths. Including it in standard ensures JVM fidelity tests have access to the same
      * fixture classes that JS/Native sees via the embedded bytes.
      */
    lazy val kyoTastyFixtures: Seq[String] =
        all.filter { p =>
            (p.contains("/kyo-tasty/fixtures") && p.endsWith("/classes")) ||
            (p.contains("/kyo-tasty-fixtures-internal") && p.endsWith(".jar"))
        }

    /** A standard 3-root combo used by most fidelity tests: kyo-tasty + kyo-data + scala-library + internal fixtures. */
    lazy val standard: Seq[String] = kyoTasty ++ kyoData ++ scalaLibrary ++ kyoTastyFixtures

    /** A broader combo that adds kyo-core to the standard set, enabling ContextFunctionN coverage. */
    lazy val standardWithKyoCore: Seq[String] = standard ++ kyoCore

    // Coordinates the single, narrowed force of the process-wide `Tasty.global` singleton.
    private val globalForceLock        = new AnyRef
    @volatile private var globalForced = false

    /** Force `Tasty.global` exactly once, under a `java.class.path` narrowed to the scala-library jar.
      *
      * `Tasty.global` is a lazy val that cold-loads the entire `java.class.path` on first access
      * (`PlatformFallback.initFallback`). In the forked test JVM that property is the full transitive test
      * classpath (every kyo module, scala3, zio, cats-effect, Spark, Play, ...); decoding all of it eagerly
      * exhausts the test heap. The forcing leaf then OOMs, `initFallback` swallows the error and caches
      * `Binding.empty`, and every later non-empty assertion against the JVM fallback reads 0 symbols (or the
      * leaf goes STUCK and times out). Because the lazy val is forced at most once per process, the FIRST
      * access fixes the binding for the whole run.
      *
      * Every JVM test that reads `Tasty.global`, directly or through the fallback path of `Tasty.classpath` /
      * a `Tasty.*` query evaluated outside a `withClasspath` scope, MUST call this first so the one real force
      * happens under the narrowed classpath. Idempotent and thread-safe.
      */
    def forceGlobalNarrowed(): Unit =
        if !globalForced then
            globalForceLock.synchronized {
                if !globalForced then
                    // Resolve the narrow root and pre-load every classpath fixture from the FULL property
                    // before narrowing, so a concurrent suite computing a `TestClasspaths` root cannot observe
                    // the narrowed value during the force window.
                    val narrowed = scala3LibraryJar
                    discard(all)
                    discard(standardWithKyoCore)
                    val saved = java.lang.System.getProperty("java.class.path")
                    discard(java.lang.System.setProperty("java.class.path", narrowed))
                    try discard(Tasty.global)
                    finally
                        if saved == null then discard(java.lang.System.clearProperty("java.class.path"))
                        else discard(java.lang.System.setProperty("java.class.path", saved))
                    end try
                    globalForced = true
                end if
            }
    end forceGlobalNarrowed

    /** Run `f` in a fresh classpath scope loaded from `roots` using `ErrorMode.SoftFail`.
      *
      * Delegates to `Tasty.withClasspath`, which handles `Scope.run` internally. Call inside a `run {. }` test
      * body. Inside `f`, use `Tasty.*` query operations; they read the active binding from `Tasty.bindingLocal`.
      */
    def withClasspath[A, S](roots: Seq[String] = standard)(f: => A < S)(using Frame): A < (Async & Abort[TastyError] & S) =
        Tasty.withClasspath(roots)(f)

end TestClasspaths
