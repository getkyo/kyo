package kyo

/** Verifies that several popular Scala ecosystem jars load without file-level errors.
  *
  * All leaves are JVM-only (java.class.path discovery requires JVM) and tagged `slow` so they
  * are excluded from the default test run but can be included with `-- -n slow`.
  */
class RealWorldClasspathTest extends kyo.test.Test[Any]:

    /** Filter cp.errors to only file-level failures (CorruptedFile, MalformedSection, FileNotFound).
      *
      * UnknownType errors for TypeAlias/OpaqueType/Parameter symbols with absent declared types are not
      * file-level failures; they arise when AstUnpickler.readTypeIntoSession catches a decode exception
      * and returns Absent for a cross-file type reference.
      */
    private def fileLevelErrors(errors: Chunk[TastyError]): Chunk[TastyError] =
        errors.filter:
            case _: TastyError.CorruptedFile    => true
            case _: TastyError.MalformedSection => true
            case _: TastyError.FileNotFound     => true
            case _                              => false

    "Akka classpath loads without error".onlyJvm.tagged("slow") in {
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

    "Cats Effect classpath loads without error".onlyJvm.tagged("slow") in {
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

    "Http4s classpath loads without error".onlyJvm.tagged("slow") in {
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

    "Pekko classpath loads without error".onlyJvm.tagged("slow") in {
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

    "Play classpath loads without error".onlyJvm.tagged("slow") in {
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

    "Spark classpath loads without error".onlyJvm.tagged("slow") in {
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

    "Spire classpath loads without error".onlyJvm.tagged("slow") in {
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

    "ZIO classpath loads without error".onlyJvm.tagged("slow") in {
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

    private def findJar(nameFragment: String)(using kyo.test.AssertScope, Frame): String =
        java.lang.System.getProperty("java.class.path", "")
            .split(java.io.File.pathSeparatorChar)
            .find(_.contains(nameFragment))
            .getOrElse(fail(s"Jar containing '$nameFragment' not found on java.class.path"))

end RealWorldClasspathTest
