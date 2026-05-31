package kyo

import scala.concurrent.Future

/** Tests for per-type file split (Phase 10 Item 17).
  *
  * Leaf id:18. Pins: Package surface verdict from design 02 - all per-type files are PUBLIC.
  *
  * Guards that the 13 expected per-type satellite objects exist and that the Tasty object re-exports work. File existence is verified via
  * compile-time symbol resolution (if the object doesn't exist, the `classOf` call fails at compile time).
  */
class TastyFileSplitTest extends Test:

    // Leaf id:18 -- Tasty.scala split into per-type satellite files
    // Each of these compile-time checks fails to compile if the corresponding TastyXxx object is absent.
    "TastyName satellite object exists" in {
        val _: Class[?] = classOf[TastyName.type]
        Future.successful(succeed)
    }

    "TastyFlags satellite object exists" in {
        val _: Class[?] = classOf[TastyFlags.type]
        Future.successful(succeed)
    }

    "TastySymbolKind satellite object exists" in {
        val _: Class[?] = classOf[TastySymbolKind.type]
        Future.successful(succeed)
    }

    "TastySymbolId satellite object exists" in {
        val _: Class[?] = classOf[TastySymbolId.type]
        Future.successful(succeed)
    }

    "TastySymbol satellite object exists" in {
        val _: Class[?] = classOf[TastySymbol.type]
        Future.successful(succeed)
    }

    "TastyType satellite object exists" in {
        val _: Class[?] = classOf[TastyType.type]
        Future.successful(succeed)
    }

    "TastyTree satellite object exists" in {
        val _: Class[?] = classOf[TastyTree.type]
        Future.successful(succeed)
    }

    "TastyAnnotation satellite object exists" in {
        val _: Class[?] = classOf[TastyAnnotation.type]
        Future.successful(succeed)
    }

    "TastyClasspath satellite object exists" in {
        val _: Class[?] = classOf[TastyClasspath.type]
        Future.successful(succeed)
    }

    "TastyErrorMode satellite object exists" in {
        val _: Class[?] = classOf[TastyErrorMode.type]
        Future.successful(succeed)
    }

    "TastyModules satellite object exists" in {
        val _: Class[?] = classOf[TastyModules.type]
        Future.successful(succeed)
    }

    "TastyJava satellite object exists" in {
        val _: Class[?] = classOf[TastyJava.type]
        Future.successful(succeed)
    }

    "TastyMisc satellite object exists" in {
        val _: Class[?] = classOf[TastyMisc.type]
        Future.successful(succeed)
    }

    // Leaf id:1 -- Classpath is a class, not an opaque type (regression guard after split)
    "Tasty.Classpath is accessible as a class type" in {
        val _: Class[?] = classOf[Tasty.Classpath]
        Future.successful(succeed)
    }

    // Tasty.scala backward-compat: Tasty.ErrorMode, Tasty.Name etc. still resolve
    "Tasty object re-exports top-level types under historical paths" in {
        val _: Tasty.ErrorMode = Tasty.ErrorMode.SoftFail
        val _: Tasty.Name      = Tasty.Name("test")
        Future.successful(succeed)
    }

end TastyFileSplitTest
