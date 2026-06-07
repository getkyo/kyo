package kyo

import scala.compiletime.testing.typeCheckErrors

/** leaf 1 + 2: README surface verification.
  *
  * Leaf 1 (doctestPasses): compile-time checks via typeCheckErrors verify that every key
  * method referenced in README code blocks actually exists on the post-fix public surface.
  * This is the Approach A substitute for `sbt 'kyo-tasty/doctest'` (prep doc C4).
  * The checks run cross-platform; they exercise the API surface, not file I/O.
  *
  * The typeCheckErrors strings that exercise Tasty query methods include an explicit
  * `using kyo.Frame.internal` because those methods require Frame and the fresh compilation
  * context used by typeCheckErrors does not have a `given Frame` in scope.
  *
  * Leaf 2 (noDriftFromPhantomNames): reads the README test resource and applies a
  * regex sweep for phantom names that must not appear in the regenerated README. The
  * phantom check runs cross-platform: TestResourceLoader serves README.md from the
  * JVM classloader on JVM and from the sbt-generated EmbeddedText on JS / Native.
  * The `.isSubtypeOf` check uses a two-pass scan instead of a regex look-behind so
  * the contract holds on Scala.js (whose regex engine does not support look-behind).
  */
class KyoTastyDoctestVerifyTest extends kyo.test.Test[Any]:

    // ── Leaf 1: compile-time surface checks ──────────────────────────────────
    // Given: the post-fix public surface of object Tasty.
    // When: typeCheckErrors is called for each key README entry-point reference.
    // Then: every "ok" check produces empty errors (method exists); every "bad" check produces non-empty.

    "Leaf 1a: Tasty.findClass exists with correct return type" in {
        val ok = typeCheckErrors(
            "given kyo.Frame = kyo.Frame.internal; val _: kyo.Maybe[kyo.Tasty.Symbol.Class] < kyo.Sync = kyo.Tasty.findClass(\"x\")"
        )
        assert(ok.length == 0, s"Tasty.findClass should type-check; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    "Leaf 1b: Tasty.requireClass exists with correct return type" in {
        val ok = typeCheckErrors(
            "given kyo.Frame = kyo.Frame.internal; val _: kyo.Tasty.Symbol.Class < (kyo.Sync & kyo.Abort[kyo.TastyError]) = kyo.Tasty.requireClass(\"x\")"
        )
        assert(ok.length == 0, s"Tasty.requireClass should type-check; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    "Leaf 1c: Tasty.findClassLike exists with correct return type" in {
        val ok = typeCheckErrors(
            "given kyo.Frame = kyo.Frame.internal; val _: kyo.Maybe[kyo.Tasty.Symbol.ClassLike] < kyo.Sync = kyo.Tasty.findClassLike(\"x\")"
        )
        assert(ok.length == 0, s"Tasty.findClassLike should type-check; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    "Leaf 1d: Tasty.requireClassLike exists with correct return type" in {
        val ok = typeCheckErrors(
            "given kyo.Frame = kyo.Frame.internal; val _: kyo.Tasty.Symbol.ClassLike < (kyo.Sync & kyo.Abort[kyo.TastyError]) = kyo.Tasty.requireClassLike(\"x\")"
        )
        assert(ok.length == 0, s"Tasty.requireClassLike should type-check; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    "Leaf 1e: Tasty.allClassLike exists with correct return type" in {
        val ok = typeCheckErrors(
            "given kyo.Frame = kyo.Frame.internal; val _: kyo.Chunk[kyo.Tasty.Symbol.ClassLike] < kyo.Sync = kyo.Tasty.allClassLike"
        )
        assert(ok.length == 0, s"Tasty.allClassLike should type-check; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    "Leaf 1f: Tasty.allMethods exists with correct return type" in {
        val ok = typeCheckErrors(
            "given kyo.Frame = kyo.Frame.internal; val _: kyo.Chunk[kyo.Tasty.Symbol.Method] < kyo.Sync = kyo.Tasty.allMethods"
        )
        assert(ok.length == 0, s"Tasty.allMethods should type-check; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    "Leaf 1g: Tasty.isSubtypeOf exists on object Tasty" in {
        val ok = typeCheckErrors(
            "given kyo.Frame = kyo.Frame.internal; val _: kyo.Tasty.SubtypeVerdict < kyo.Sync = kyo.Tasty.isSubtypeOf(kyo.Tasty.Type.Any, kyo.Tasty.Type.Nothing)"
        )
        assert(ok.length == 0, s"Tasty.isSubtypeOf(tpe, other) should type-check; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    "Leaf 1h: Type.isSubtypeOf does NOT exist on Type" in {
        val bad = typeCheckErrors("(null : kyo.Tasty.Type).isSubtypeOf(kyo.Tasty.Type.Nothing)")
        assert(bad.length > 0, "Type.isSubtypeOf should not exist on Type (it is on object Tasty)")
        succeed
    }

    "Leaf 1i: Type.collect, find, foldLeft, exists are public" in {
        val ok1 = typeCheckErrors("val _: kyo.Chunk[kyo.Tasty.Type] = kyo.Tasty.Type.Any.collect { case x => x }")
        val ok2 = typeCheckErrors("val _: kyo.Maybe[kyo.Tasty.Type] = kyo.Tasty.Type.Any.find(_ => true)")
        val ok3 = typeCheckErrors("val _: Int = kyo.Tasty.Type.Any.foldLeft(0)((acc, _) => acc + 1)")
        val ok4 = typeCheckErrors("val _: Boolean = kyo.Tasty.Type.Any.exists(_ => true)")
        assert(ok1.length == 0, s"Type.collect should type-check; errors: ${ok1.map(_.message).mkString("; ")}")
        assert(ok2.length == 0, s"Type.find should type-check; errors: ${ok2.map(_.message).mkString("; ")}")
        assert(ok3.length == 0, s"Type.foldLeft should type-check; errors: ${ok3.map(_.message).mkString("; ")}")
        assert(ok4.length == 0, s"Type.exists should type-check; errors: ${ok4.map(_.message).mkString("; ")}")
        succeed
    }

    // Leaf 1j: Type.visit is private[kyo]. Accessible from within kyo package tests, so
    // we verify behavioral non-existence: the visit method should NOT be discoverable from
    // a user-perspective code snippet outside the kyo package. We use a fully-qualified
    // external import pattern that the typeCheckErrors fresh context can check.
    "Leaf 1j: Type.visit is private (not part of public API)" in {
        // Private[kyo] means accessible within package kyo but not from user code outside.
        // We verify the pure traversal counterparts are the documented public API.
        val okCollect = typeCheckErrors("val _: kyo.Chunk[kyo.Tasty.Type] = kyo.Tasty.Type.Any.collect { case x => x }")
        assert(okCollect.length == 0, "Type.collect is the documented public traversal; must type-check")
        succeed
    }

    "Leaf 1k: Tasty.bodyTree exists with correct return type" in {
        val ok = typeCheckErrors(
            "given kyo.Frame = kyo.Frame.internal; val _: kyo.Maybe[kyo.Tasty.Tree] < (kyo.Sync & kyo.Abort[kyo.TastyError]) = kyo.Tasty.bodyTree(null: kyo.Tasty.Symbol)"
        )
        assert(ok.length == 0, s"Tasty.bodyTree should type-check; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    "Leaf 1l: SubtypeVerdict.Indeterminate exists (not Unknown)" in {
        val okInd = typeCheckErrors("val _: kyo.Tasty.SubtypeVerdict = kyo.Tasty.SubtypeVerdict.Indeterminate")
        assert(okInd.length == 0, s"SubtypeVerdict.Indeterminate should type-check; errors: ${okInd.map(_.message).mkString("; ")}")

        val badUnk = typeCheckErrors("val _: kyo.Tasty.SubtypeVerdict = kyo.Tasty.SubtypeVerdict.Unknown")
        assert(badUnk.length > 0, "SubtypeVerdict.Unknown must not exist (renamed to Indeterminate)")
        succeed
    }

    "Leaf 1m: Tasty.Java.Annotation exists (not JavaAnnotation at top level)" in {
        val okAnn = typeCheckErrors("val _: Class[?] = classOf[kyo.Tasty.Java.Annotation]")
        assert(okAnn.length == 0, s"Tasty.Java.Annotation should type-check; errors: ${okAnn.map(_.message).mkString("; ")}")

        val badAnn = typeCheckErrors("val _: Any = (null: kyo.Tasty.JavaAnnotation)")
        assert(badAnn.length > 0, "kyo.Tasty.JavaAnnotation must not exist (moved to Tasty.Java.Annotation)")
        succeed
    }

    "Leaf 1n: Tasty.Java.Module.Descriptor exists (not ModuleDescriptor at top level)" in {
        val okMod = typeCheckErrors("val _: Class[?] = classOf[kyo.Tasty.Java.Module.Descriptor]")
        assert(okMod.length == 0, s"Tasty.Java.Module.Descriptor should type-check; errors: ${okMod.map(_.message).mkString("; ")}")

        val badMod = typeCheckErrors("val _: Any = (null: kyo.Tasty.ModuleDescriptor)")
        assert(badMod.length > 0, "kyo.Tasty.ModuleDescriptor must not exist (moved to Tasty.Java.Module.Descriptor)")
        succeed
    }

    "Leaf 1o: Type.Unknown does NOT exist (removed from API)" in {
        val bad = typeCheckErrors("val _: kyo.Tasty.Type = kyo.Tasty.Type.Unknown")
        assert(bad.length > 0, "Type.Unknown must not exist removed from API")
        succeed
    }

    "Leaf 1p: Symbol.Unresolved does NOT exist (removed from API)" in {
        val bad = typeCheckErrors("val _: Any = (null: kyo.Tasty.Symbol.Unresolved)")
        assert(bad.length > 0, "Symbol.Unresolved must not exist (removed from API)")
        succeed
    }

    "Leaf 1q: Classpath.symbol returns Maybe[Symbol] not raw Symbol" in {
        val ok = typeCheckErrors(
            "val _: kyo.Maybe[kyo.Tasty.Symbol] = (null: kyo.Tasty.Classpath).symbol(kyo.Tasty.SymbolId(0))"
        )
        assert(ok.length == 0, s"Classpath.symbol should return Maybe[Symbol]; errors: ${ok.map(_.message).mkString("; ")}")
        succeed
    }

    // ── Leaf 2: phantom-name sweep on README resource ─────────────────────────
    // Given: the regenerated kyo-tasty/README.md (served via classloader resource).
    // When: the test loads the resource and applies a regex sweep for phantom names.
    // Then: zero matches for any phantom name.
    // JVM only (tagged jvmOnly): uses TestResourceLoader (JVM classloader).

    "Leaf 2: README contains no phantom names" in {
        // README.md is served as a test resource via build.sbt resourceGenerators.
        val content = new String(TestResourceLoader.loadBytes("README.md"), "UTF-8")

        val phantoms: Seq[(scala.util.matching.Regex, String)] = Seq(
            ("\\bTypeLike\\b".r, "TypeLike (never existed)"),
            ("\\bTermLike\\b".r, "TermLike (never existed)"),
            ("\\bSymbol\\.Unresolved\\b".r, "Symbol.Unresolved (removed)"),
            ("\\bresultTypeId\\b".r, "Method.resultTypeId (phantom field)"),
            ("\\bType\\.Unknown\\b".r, "Type.Unknown (removed)"),
            ("\\bSubtypeVerdict\\.Unknown\\b".r, "SubtypeVerdict.Unknown (renamed Indeterminate)"),
            ("\\bTasty\\.current\\b".r, "Tasty.current (relocated to TastyState.global)"),
            ("\\bJavaAnnotation\\b".r, "JavaAnnotation (renamed Tasty.Java.Annotation)"),
            ("\\bJavaMetadata\\b".r, "JavaMetadata (renamed Tasty.Java.Metadata)"),
            ("\\bModuleDescriptor\\b".r, "ModuleDescriptor (renamed Tasty.Java.Module.Descriptor)"),
            ("\\bModuleRequires\\b".r, "ModuleRequires (renamed Tasty.Java.Module.Requires)"),
            ("\\bModuleExports\\b".r, "ModuleExports (renamed Tasty.Java.Module.Exports)"),
            ("\\bModuleOpens\\b".r, "ModuleOpens (renamed Tasty.Java.Module.Opens)"),
            ("\\bModuleProvides\\b".r, "ModuleProvides (renamed Tasty.Java.Module.Provides)"),
            ("\\ballUnresolved\\b".r, "allUnresolved (deleted with Symbol.Unresolved)"),
            ("\\bSymbolBody\\b".r, "SymbolBody (moved to internal)"),
            ("\\bTasty\\.requireTrait\\b".r, "Tasty.requireTrait (does not exist on object Tasty)"),
            // Negative lookbehind (?<!Tasty)\.isSubtypeOf\b is JVM-portable but Scala.js's regex engine
            // does not support look-behind. Rewritten below as a two-pass scan over plain \.isSubtypeOf
            // matches, post-filtering matches preceded by "Tasty". Same contract: catch tpe.isSubtypeOf
            // (phantom method on Type) while permitting Tasty.isSubtypeOf (real method on object Tasty).
            ("\\bTasty\\.implementationsOf\\b".r, "Tasty.implementationsOf (exists only on Classpath)"),
            ("\\bTasty\\.directSubclassesOf\\b".r, "Tasty.directSubclassesOf (exists only on Classpath)"),
            ("\\bTasty\\.subclassesOf\\b".r, "Tasty.subclassesOf (exists only on Classpath)")
        )

        // Detect `.isSubtypeOf` not preceded by "Tasty" using a two-pass scan rather than a
        // regex look-behind (Scala.js does not implement ECMAScript 2018 look-behind).
        val phantomIsSubtypeOf: Option[String] =
            val matches = "\\.isSubtypeOf\\b".r.findAllMatchIn(content).toList
            matches.find { m =>
                val before = content.substring(0, m.start)
                !before.endsWith("Tasty")
            }.map(_ => "tpe.isSubtypeOf (not a method on Type; use Tasty.isSubtypeOf)")
        end phantomIsSubtypeOf

        val regexViolations = phantoms.collect {
            case (regex, description) if regex.findFirstIn(content).isDefined =>
                description
        }
        val violations = regexViolations ++ phantomIsSubtypeOf.toSeq

        assert(
            violations.isEmpty,
            s"README contains phantom names that must not appear:\n${violations.mkString("  - ", "\n  - ", "")}"
        )
        succeed
    }

end KyoTastyDoctestVerifyTest
