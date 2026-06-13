package kyo.ffi.codegen

import kyo.ffi.codegen.emitters.NativeCallbackCatalog
import scala.io.Source
import scala.util.matching.Regex

/** Lockstep check for [[NativeCallbackCatalog.catalog]] vs `project/CallbackShapesGen.scala`'s `SHAPES` list.
  *
  * `CallbackShapesGen.SHAPES` is the single source of truth that drives code generation at build time. `NativeCallbackCatalog.catalog` must
  * mirror it exactly: same count, same shape ids, same order. A mismatch silently compiles but causes a linker error at Scala Native link
  * time, this spec surfaces the divergence at unit-test time instead.
  *
  * Because `project/CallbackShapesGen.scala` lives in the sbt meta-build classloader it cannot be imported here. The expected shape ids are
  * therefore hardcoded (they are the canonical identifiers shared by both sides) and the `project/` source file is read as text to extract
  * the ids listed there, giving us a cross-classloader lockstep check that fails fast when either side is extended without updating the
  * other.
  */
class NativeCallbackCatalogLockstepTest extends kyo.test.Test[Any]:

    /** Canonical shape ids in declaration order, mirrors `project/CallbackShapesGen.scala`'s `SHAPES`. */
    private val expectedIds: List[String] = List(
        "V_U",  // () => Unit
        "I_U",  // Int => Unit
        "I_I",  // Int => Int
        "II_I", // (Int, Int) => Int
        "JJ_I", // (Long, Long) => Int
        "P_U",  // Ptr[Byte] => Unit
        "PI_U", // (Ptr[Byte], Int) => Unit
        "J_J",  // Long => Long
        "J_U",  // Long => Unit
        "D_D",  // Double => Double
        "II_U", // (Int, Int) => Unit
        "JJ_J"  // (Long, Long) => Long
    )

    "NativeCallbackCatalog.catalog shape count" in {
        assert(NativeCallbackCatalog.catalog.size == expectedIds.size)
    }

    "NativeCallbackCatalog.catalog shape ids match expected set" in {
        val actualIds = NativeCallbackCatalog.catalog.map(_.id)
        assert(actualIds == expectedIds)
    }

    "project/CallbackShapesGen.scala contains the same shape count" in {
        val shapesGenPath = locateCallbackShapesGen()
        val source        = Source.fromFile(shapesGenPath)
        try
            val content      = source.mkString
            val extractedIds = extractShapeIds(content)
            assert(extractedIds.size == expectedIds.size)
            assert(extractedIds == expectedIds)
        finally source.close()
        end try
    }

    /** Extract shape ids from `CallbackShapesGen.scala` by parsing the `.name` derivation logic and the comment annotations (`// V_U`,
      * `// I_U`, ...) that appear on each `CallbackShape(...)` line inside the `SHAPES` list.
      *
      * The file uses this comment convention:
      * {{{
      * CallbackShape(Nil,           V), // V_U  : () => Unit
      * CallbackShape(I :: Nil,      V), // I_U  : Int => Unit
      * ...
      * }}}
      */
    private def extractShapeIds(content: String): List[String] =
        val pattern: Regex = """CallbackShape\([^)]+\).*//\s+(\w+)\s*:""".r
        pattern.findAllMatchIn(content).map(_.group(1)).toList

    /** Locate `project/CallbackShapesGen.scala` relative to the repository root.
      *
      * The test is compiled and run from inside `kyo-ffi-codegen/`, so we walk up until we find the `project/` directory that contains the
      * target file.
      */
    private def locateCallbackShapesGen()(using kyo.test.AssertScope): java.io.File =
        val candidateRelative = new java.io.File("project/CallbackShapesGen.scala")
        if candidateRelative.exists() then return candidateRelative

        // Walk up the directory tree from cwd until the file is found (up to 5 levels)
        Iterator.iterate(new java.io.File(".").getCanonicalFile)(_.getParentFile)
            .take(6)
            .map(root => new java.io.File(root, "project/CallbackShapesGen.scala"))
            .find(_.exists())
            .getOrElse(fail(
                "Could not locate project/CallbackShapesGen.scala, run tests from the repository root or a subproject directory"
            ))
    end locateCallbackShapesGen

end NativeCallbackCatalogLockstepTest
