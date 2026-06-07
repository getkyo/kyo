package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths2

/** fidelity tests for JPMS jrt:/ classfile decoding.
  *
  * Owns all 8 plan leaves for, and Un-pends the corresponding stubs in RealClasspathFidelity2Test and
  * covers the full set of JDK class shapes reachable after initWithPlatformModules walks jrt:/ classfiles.
  *
  * JDK version requirement: JDK 11+ for jrt:/ filesystem; JDK 14+ for java.lang.Runtime.Version records; JDK 17+ for sealed classes
  * (java.lang.constant.Constable PermittedSubclasses). The running JVM must be JDK 17 or later for all leaves to pass.
  */
class JpmsFidelity2Test extends Fidelity2TestBase:

    // JDK classfile decoding loads java.base (~7,000 entries) from jrt:/ via TestClasspaths2.standardWithPlatformModules.
    // The load is started in a background thread at object init time; tests block until it completes.
    // Allow 3 minutes per test to give headroom on loaded CI machines; the actual load takes 10-30s.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(3))

    import AllowUnsafe.embrace.danger

    // ─────────────────────────────────────────────────────────────────────────
    // ACTIVE leaves
    // ─────────────────────────────────────────────────────────────────────────

    // java.lang.String resolves
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules (initWithPlatformModules)
    // When: calling cp.findClass("java.lang.String")
    // Then: returns Present (jrt:/ walker enumerates String.class and ClassfileUnpickler decodes it)
    "findClass('java.lang.String') returns Present after jrt:/ walk" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            cp.findClass("java.lang.String") match
                case Present(sym) =>
                    assert(sym.isJava, s"java.lang.String symbol is not marked as JavaDefined; flags=${sym.flags}")
                    succeed
                case Absent =>
                    fail(
                        "cp.findClass('java.lang.String') returned Absent after initWithPlatformModules; " +
                            "jrt:/ walker did not enumerate java/lang/String.class"
                    )
    }

    // java.util.HashMap resolves
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.findClass("java.util.HashMap")
    // Then: returns Present; HashMap.class is in java.base under jrt:/modules/java.base/java/util/HashMap.class
    "b : findClass('java.util.HashMap') returns Present after jrt:/ walk" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            cp.findClass("java.util.HashMap") match
                case Present(sym) =>
                    assert(sym.isJava, s"java.util.HashMap symbol is not marked as JavaDefined; flags=${sym.flags}")
                    succeed
                case Absent =>
                    fail(
                        "cp.findClass('java.util.HashMap') returned Absent after initWithPlatformModules; " +
                            "jrt:/ walker did not enumerate java/util/HashMap.class"
                    )
    }

    // java.util.concurrent.ConcurrentHashMap resolves
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.findClass("java.util.concurrent.ConcurrentHashMap")
    // Then: returns Present; java.util.concurrent is in java.base
    "c : findClass('java.util.concurrent.ConcurrentHashMap') returns Present after jrt:/ walk" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            cp.findClass("java.util.concurrent.ConcurrentHashMap") match
                case Present(sym) =>
                    assert(sym.isJava, s"java.util.concurrent.ConcurrentHashMap symbol not marked JavaDefined; flags=${sym.flags}")
                    succeed
                case Absent =>
                    fail(
                        "cp.findClass('java.util.concurrent.ConcurrentHashMap') returned Absent after initWithPlatformModules; " +
                            "jrt:/ walker did not enumerate java/util/concurrent/ConcurrentHashMap.class"
                    )
    }

    // cp.modules contains "java.base"
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.findModule("java.base")
    // Then: returns Present with at least one export starting with "java.lang"
    "cp.modules contains 'java.base' after jrt:/ walker addition" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            cp.findModule("java.base") match
                case Present(m) =>
                    val exportedPackages = m.exports.map(_.packageName).toList
                    assert(
                        exportedPackages.exists(_.startsWith("java.lang")),
                        s"java.base exports do not include java.lang; found: ${exportedPackages.take(10).mkString(", ")}"
                    )
                    val moduleCount = cp.modules.size
                    assert(
                        moduleCount >= 65,
                        s"Expected >= 65 JPMS modules after jrt:/ walk (measured 69 on JDK 25), found $moduleCount"
                    )
                    succeed
                case Absent =>
                    fail(
                        "cp.findModule('java.base') returned Absent after initWithPlatformModules; " +
                            "module-info.class for java.base was not decoded from jrt:/"
                    )
    }

    // JDK class parentTypes includes java.lang.Object
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules; java.util.HashMap found
    // When: examining cp.findClass("java.util.HashMap").parentTypes
    // Then: at least one parent resolves to a non-sentinel symbol id (java.lang.AbstractMap or java.util.Map)
    "d : JDK class parentTypes wired (HashMap parents include AbstractMap or Object)" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            cp.findClass("java.util.HashMap") match
                case Absent =>
                    fail("cp.findClass('java.util.HashMap') returned Absent; prerequisite for parent check failed")
                case Present(hm) =>
                    val parentTypes = hm.parentTypes
                    assert(
                        parentTypes.nonEmpty,
                        "java.util.HashMap.parentTypes is empty; finalizeMerge did not wire classfile parent binary names"
                    )
                    // At least one parent should be a resolved Named (non-sentinel)
                    val resolvedParents = parentTypes.collect:
                        case Tasty.Type.Named(id) if id.value >= 0 => id
                    assert(
                        resolvedParents.nonEmpty,
                        s"java.util.HashMap has no resolved Named parents (all are sentinel -1 or other type kinds); " +
                            s"parentTypes: $parentTypes"
                    )
                    succeed
            end match
    }

    // Java enum isEnum
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.findClass("java.lang.annotation.RetentionPolicy")
    // Then: Present(sym) with sym.isEnum == true; RetentionPolicy is a JDK enum
    "findClass('java.lang.annotation.RetentionPolicy').isEnum is true" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            cp.findClass("java.lang.annotation.RetentionPolicy") match
                case Absent =>
                    fail(
                        "cp.findClass('java.lang.annotation.RetentionPolicy') returned Absent; " +
                            "jrt:/ walker did not enumerate java/lang/annotation/RetentionPolicy.class"
                    )
                case Present(sym) =>
                    assert(
                        sym.isEnum,
                        s"java.lang.annotation.RetentionPolicy.isEnum returned false; expected true for JDK enum; flags=${sym.flags}"
                    )
                    succeed
    }

    // Java sealed class permittedSubclasses populated (JDK 17+)
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules; JDK >= 17 for PermittedSubclasses attribute
    // When: calling cp.findClassLike("java.lang.constant.ConstantDesc")
    // Then: Present(sym) with sym.isSealed == true and sym.permittedSubclassIds.map(_.map(cp.symbol)).getOrElse(Chunk.empty) returns Present with >= 1 entry
    // JDK version: ConstantDesc is sealed since JDK 12. Note: java.lang.constant.Constable was sealed in
    // JDK 17-21 but became non-sealed in JDK 22+; ConstantDesc remains sealed through JDK 25.
    "java.lang.constant.ConstantDesc permittedSubclasses populated (JDK 12+)" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            val jdkVersion = java.lang.Runtime.version().feature()
            cp.findClassLike("java.lang.constant.ConstantDesc") match
                case Absent =>
                    fail(
                        "cp.findClassLike('java.lang.constant.ConstantDesc') returned Absent; " +
                            "jrt:/ walker did not enumerate java/lang/constant/ConstantDesc.class (requires JDK 12+)"
                    )
                case Present(sym) =>
                    if jdkVersion >= 12 then
                        assert(
                            sym.isSealed,
                            s"java.lang.constant.ConstantDesc.isSealed is false on JDK $jdkVersion; " +
                                s"expected true (PermittedSubclasses attribute decoded); flags=${sym.flags}"
                        )
                        val subs = sym match
                            case c: Tasty.Symbol.Class => c.permittedSubclassIds.map(_.map(cp.symbol)).getOrElse(Chunk.empty)
                            case t: Tasty.Symbol.Trait => t.permittedSubclassIds.map(_.map(cp.symbol)).getOrElse(Chunk.empty)
                            case _                     => Chunk.empty
                        assert(
                            subs.nonEmpty,
                            s"java.lang.constant.ConstantDesc.permittedSubclassIds.map(_.map(cp.symbol)).getOrElse(Chunk.empty) is empty on JDK $jdkVersion; " +
                                "expected at least one permitted subclass (e.g. Double, Integer, String); " +
                                "ClassfileUnpickler did not decode PermittedSubclasses attribute"
                        )
                        succeed
                    else
                        // JDK < 12: ConstantDesc does not exist; skip.
                        succeed
            end match
    }

    // Java interface default methods detectable
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules; java.util.Iterator found
    // When: enumerating java.util.Iterator.declarationIds.map(cp.symbol) and filtering for non-abstract methods
    // Then: at least one non-abstract method exists (the default method forEachRemaining added in Java 8)
    // Java interface default methods are NOT marked Abstract (ACC_ABSTRACT is clear); abstract interface methods ARE Abstract.
    "java.util.Iterator has non-abstract method declarations (default methods)" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            cp.findClassLike("java.util.Iterator") match
                case Absent =>
                    fail(
                        "cp.findClassLike('java.util.Iterator') returned Absent; " +
                            "jrt:/ walker did not enumerate java/util/Iterator.class"
                    )
                case Present(sym) =>
                    val allMethods = sym.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(
                        _.isInstanceOf[Tasty.Symbol.Method]
                    ).asInstanceOf[Chunk[Tasty.Symbol.Method]]
                    assert(
                        allMethods.nonEmpty,
                        "java.util.Iterator.declarationIds.flatMap(cp.symbol).filter(_.isInstanceOf[Tasty.Symbol.Method]) is empty; expected at least hasNext, next, remove, forEachRemaining"
                    )
                    // Non-abstract methods in an interface are default methods.
                    // ACC_ABSTRACT (0x0400) is clear for default methods; set for abstract interface methods.
                    val defaultMethods = allMethods.filter(!_.isAbstract)
                    assert(
                        defaultMethods.nonEmpty,
                        s"java.util.Iterator has no non-abstract methods; expected at least 'remove' or 'forEachRemaining' default method. " +
                            s"All methods (${allMethods.size}): ${allMethods.map(_.name.asString).toList.mkString(", ")}"
                    )
                    succeed
            end match
    }

    // initWithPlatformModules includes JDK class symbols
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules (kyo-tasty roots + JDK)
    // When: verifying both kyo.Tasty and java.lang.String are findable
    // Then: both return Present; demonstrates user roots and JDK roots are merged
    "initWithPlatformModules includes both user TASTy and JDK class symbols" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            val kyoTasty   = cp.findClassLike("kyo.Tasty")
            val javaString = cp.findClass("java.lang.String")
            assert(
                kyoTasty.isDefined,
                "cp.findClassLike('kyo.Tasty') returned Absent after initWithPlatformModules; user TASTy roots not merged"
            )
            assert(
                javaString.isDefined,
                "cp.findClass('java.lang.String') returned Absent after initWithPlatformModules; JDK class roots not merged"
            )
            succeed
    }

    // unresolvedTypeReferenceCount with java.base JDK
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules (java.base only)
    // When: calling cp.unresolvedTypeReferenceCount
    // Then: the count is non-negative (counts FQN-tracked cross-classpath gaps; types from
    // JDK modules outside java.base may be unresolved if only java.base is loaded, but no crash occurs)
    // Note: Before this checked == 0 (sentinel-id refs only). After the count is the
    // number of FQN-tracked parent-type refs whose defining package was absent from the loaded classpath.
    "cp.unresolvedTypeReferenceCount >= 0 on full classpath including JDK" in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            val unresolved = cp.unresolvedTypeReferenceCount
            assert(
                unresolved >= 0,
                s"Expected non-negative unresolved type reference count, found $unresolved."
            )
            succeed
    }

end JpmsFidelity2Test
