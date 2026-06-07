package kyo

/** leaves 3 + 4: kyo-tasty/CONTRIBUTING.md content verification.
  *
  * Leaf 3 (gapClosureSubstance): asserts the file exists and contains every
  * substantive section required by the plan and design/00-guides.md.
  *
  * Leaf 4 (scaladocBarReferenced): asserts the 8-35 line scaladoc bar is
  * cited with a link to CONTRIBUTING.md Documentation lines 434-455.
  *
  * Both leaves are tagged jvmOnly because they use TestResourceLoader (JVM-only).
  */
class KyoTastyContributingMdTest extends kyo.test.Test[Any]:

    /** Load the text of a test resource. Uses TestResourceLoader (JVM classloader). */
    private def loadText(resourcePath: String): String =
        new String(TestResourceLoader.loadBytes(resourcePath), "UTF-8")
    end loadText

    // ── Leaf 3: gap-closure substance ─────────────────────────────────────────
    // Given: the new kyo-tasty/CONTRIBUTING.md (available as a test resource).
    // When: the test asserts the content contains required substantive sections.
    // Then: AllowUnsafe four-site list, closed TastyError ADT, wire-format-minor-bump rule,
    //   Tasty.Java.* namespace, LoadingSymbol producer/consumer split, DecodeContext
    //   consumer, JVM cross-platform stance, INV-IMMUTABLE-ADT; content >= 120 lines.

    "Leaf 3: CONTRIBUTING.md resource is loadable with >= 120 lines".onlyJvm in {
        val content = loadText("CONTRIBUTING.md")
        val lines   = content.split('\n').length
        assert(lines >= 120, s"CONTRIBUTING.md must be >= 120 lines; got $lines")
        succeed
    }

    "Leaf 3a: AllowUnsafe four-site list is present".onlyJvm in {
        val content = loadText("CONTRIBUTING.md")
        assert(content.contains("AllowUnsafe"), "CONTRIBUTING.md must mention AllowUnsafe")
        assert(content.contains("withClasspath"), "AllowUnsafe site-1 (withClasspath) must be named")
        assert(content.contains("global"), "AllowUnsafe site-2 (global, renamed from current) must be named")
        assert(content.contains("bodyTree"), "AllowUnsafe site-3 (bodyTree) must be named")
        assert(content.contains("evictOlderThan"), "AllowUnsafe site-4 (evictOlderThan) must be named")
        succeed
    }

    "Leaf 3b: closed TastyError ADT and wire-format-minor-bump rule".onlyJvm in {
        val content = loadText("CONTRIBUTING.md")
        assert(content.contains("TastyError"), "CONTRIBUTING.md must mention TastyError closed ADT")
        assert(content.contains("wire-format"), "CONTRIBUTING.md must document the wire-format minor-bump rule")
        succeed
    }

    "Leaf 3c: Tasty.Java.* namespace convention documented".onlyJvm in {
        val content = loadText("CONTRIBUTING.md")
        assert(content.contains("Tasty.Java"), "CONTRIBUTING.md must document the Tasty.Java.* namespace convention")
        succeed
    }

    "Leaf 3d: producer/consumer split (LoadingSymbol and DecodeContext) documented".onlyJvm in {
        val content = loadText("CONTRIBUTING.md")
        assert(content.contains("LoadingSymbol"), "CONTRIBUTING.md must document LoadingSymbol producer/consumer split")
        assert(content.contains("DecodeContext"), "CONTRIBUTING.md must document DecodeContext accumulator")
        succeed
    }

    "Leaf 3e: cross-platform stance documented (JVM and in-memory)".onlyJvm in {
        val content = loadText("CONTRIBUTING.md")
        assert(content.contains("JVM"), "CONTRIBUTING.md must document JVM-primary cross-platform stance")
        succeed
    }

    "Leaf 3f: INV-IMMUTABLE-ADT documented".onlyJvm in {
        val content = loadText("CONTRIBUTING.md")
        assert(
            content.contains("INV-IMMUTABLE-ADT") || content.contains("immutable"),
            "CONTRIBUTING.md must document the immutable-ADT invariant"
        )
        succeed
    }

    // ── Leaf 4: scaladoc bar citation ─────────────────────────────────────────
    // Given: the new kyo-tasty/CONTRIBUTING.md.
    // When: the test asserts the 8-35 line scaladoc bar is cited.
    // Then: a citation referencing CONTRIBUTING.md Documentation lines 434-455 is present.

    "Leaf 4: scaladoc bar citation (8-35 lines / CONTRIBUTING.md 434-455) is present".onlyJvm in {
        val content    = loadText("CONTRIBUTING.md")
        val has8to35   = content.contains("8-35") || content.contains("8 to 35") || content.contains("8 and 35")
        val hasLineRef = content.contains("434") && content.contains("455")
        assert(
            has8to35 || hasLineRef,
            "CONTRIBUTING.md must cite the 8-35 line scaladoc bar (referencing CONTRIBUTING.md 434-455)"
        )
        succeed
    }

end KyoTastyContributingMdTest
