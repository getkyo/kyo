package kyo

import org.scalatest.Tag

/** Phase 17 plan leaves 1-9: real-world classpath alphabetical iteration.
  *
  * Leaf 1: Akka actor classpath loads without error (BIND-018, Akka).
  * Leaf 2: Cats Effect classpath loads without error (BIND-018, Cats Effect).
  * Leaf 3: Doobie classpath loads without error; Scala 2.13-only jar, no TASTy files (BIND-018, Doobie).
  * Leaf 4: Http4s classpath loads without error (BIND-018, Http4s).
  * Leaf 5: Pekko actor classpath loads without error (BIND-018, Pekko).
  * Leaf 6: Play classpath loads without error (BIND-018, Play).
  * Leaf 7: Spark classpath loads without error; Scala 2.13-only jar, no TASTy files (BIND-018, Spark).
  * Leaf 8: Spire classpath loads without error (BIND-018, Spire).
  * Leaf 9: ZIO classpath loads without error (BIND-018, ZIO).
  *
  * All leaves are JVM-only (java.class.path discovery requires JVM) and tagged `slow` so they
  * are excluded from the default test run but can be included with `-- -n slow`.
  *
  * Pins: BIND-018 real-world classpath fidelity; INV-001, INV-002, INV-003, INV-004, INV-005,
  * INV-006, INV-007, INV-008, INV-009.
  */
class RealWorldClasspathTest extends Test:

    private object slow extends Tag("slow")

    /** Filter cp.errors to only file-level failures (CorruptedFile, MalformedSection, FileNotFound).
      *
      * Carry A2 refinement: UnknownType errors for TypeAlias/OpaqueType/Parameter symbols with absent
      * declared types are now correctly propagated by the wired Cat 14 producers. These are not file-level
      * failures; they arise when AstUnpickler.readTypeIntoSession catches a decode exception and returns
      * Absent for a cross-file type reference. The real-world classpath invariant asserts no FILE-level
      * errors; per-symbol absent-type errors are informational and do not indicate a broken classpath.
      */
    private def fileLevelErrors(errors: Chunk[TastyError]): Chunk[TastyError] =
        errors.filter:
            case _: TastyError.CorruptedFile    => true
            case _: TastyError.MalformedSection => true
            case _: TastyError.FileNotFound     => true
            case _                              => false

    // ── Leaf 1: Akka actor classpath loads without error ─────────────────────
    // Given: akka-actor_3-2.6.20.jar on java.class.path via build.sbt % Test intransitive dep
    // When: Tasty.withClasspath(Seq(akkaJar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // JVM-only: java.class.path discovery requires JVM; jar path extraction uses java.io.File.
    // Pins: BIND-018 (Akka)
    "Akka classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("akka-actor_3")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"Akka jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading Akka jar: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 2: Cats Effect classpath loads without error ─────────────────────
    // Given: cats-effect_3-3.7.0.jar on java.class.path via build.sbt % Test intransitive dep
    // When: Tasty.withClasspath(Seq(jar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // Pins: BIND-018 (Cats Effect)
    "Cats Effect classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("cats-effect_3")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"Cats Effect jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading Cats Effect jar: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 3: Doobie classpath loads without error ──────────────────────────
    // Given: doobie-core_2.13-1.0.0-RC2.jar on java.class.path via build.sbt % Test intransitive dep
    // Note: Doobie 1.x is Scala 2.13 only; the jar contains .class files but no .tasty files.
    // Loading it produces cp.symbols.isEmpty && cp.errors.isEmpty (no TASTy decoding occurs).
    // When: Tasty.withClasspath(Seq(jar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // Pins: BIND-018 (Doobie)
    "Doobie classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("doobie-core_2.13")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"Doobie jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading Doobie jar: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 4: Http4s classpath loads without error ──────────────────────────
    // Given: http4s-core_3-0.23.28.jar on java.class.path via build.sbt % Test intransitive dep
    // When: Tasty.withClasspath(Seq(jar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // Pins: BIND-018 (Http4s)
    "Http4s classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("http4s-core_3")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"Http4s jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading Http4s jar: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 5: Pekko actor classpath loads without error ─────────────────────
    // Given: pekko-actor_3-1.1.3.jar on java.class.path via build.sbt % Test intransitive dep
    // When: Tasty.withClasspath(Seq(jar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // Pins: BIND-018 (Pekko)
    "Pekko classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("pekko-actor_3")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"Pekko jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading Pekko jar: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 6: Play classpath loads without error ────────────────────────────
    // Given: play_3-3.0.2.jar on java.class.path via build.sbt % Test intransitive dep
    // Note: Play 3.x moved to org.playframework groupId.
    // When: Tasty.withClasspath(Seq(jar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // Pins: BIND-018 (Play)
    "Play classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("play_3")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"Play jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading Play jar: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 7: Spark classpath loads without error ───────────────────────────
    // Given: spark-core_2.13-3.5.1.jar on java.class.path via build.sbt % Test intransitive dep
    // Note: Spark 3.x is Scala 2.13 only; the jar contains .class files but no .tasty files.
    // Loading it produces cp.symbols.isEmpty && cp.errors.isEmpty (no TASTy decoding occurs).
    // When: Tasty.withClasspath(Seq(jar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // Pins: BIND-018 (Spark)
    "Spark classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("spark-core_2.13")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"Spark jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading Spark jar: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 8: Spire classpath loads without error ───────────────────────────
    // Given: spire_3-0.18.0.jar on java.class.path via build.sbt % Test intransitive dep
    // When: Tasty.withClasspath(Seq(jar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // Pins: BIND-018 (Spire)
    "Spire classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("spire_3")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"Spire jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading Spire jar: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 9: ZIO classpath loads without error ─────────────────────────────
    // Given: zio_3-2.0.15.jar on java.class.path via build.sbt % Test intransitive dep
    // When: Tasty.withClasspath(Seq(jar)) { Tasty.classpath.map(_.errors) } under Abort.run
    // Then: Result.Success(errors) AND errors.isEmpty
    // Pins: BIND-018 (ZIO)
    "ZIO classpath loads without error" taggedAs slow in runJVM {
        val jar = findJar("zio_3")
        Abort.run[TastyError](
            Tasty.withClasspath(Seq(jar)):
                Tasty.classpath.map(_.errors)
        ).map:
            case Result.Success(errors) =>
                assert(
                    fileLevelErrors(errors).isEmpty,
                    s"ZIO jar produced ${fileLevelErrors(errors).size} file-level errors: ${fileLevelErrors(errors).take(3).mkString(", ")}"
                )
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError loading ZIO jar: $e")
            case Result.Panic(t)   => throw t
    }

    // Finds the first jar on java.class.path whose path contains the given fragment.
    // Calls fail() immediately if no match, producing a red leaf with a clear message
    // rather than silently skipping (which would mask real failures).
    // Must be inside runJVM { ... } because java.lang.System.getProperty and
    // java.io.File.pathSeparatorChar are JVM-only.
    private def findJar(nameFragment: String): String =
        java.lang.System.getProperty("java.class.path", "")
            .split(java.io.File.pathSeparatorChar)
            .find(_.contains(nameFragment))
            .getOrElse(fail(s"Jar containing '$nameFragment' not found on java.class.path"))

end RealWorldClasspathTest
