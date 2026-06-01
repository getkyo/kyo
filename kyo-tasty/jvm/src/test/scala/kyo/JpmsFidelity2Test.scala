package kyo

import kyo.internal.TestClasspaths2

/** Phase 2.03 fidelity tests for JPMS jrt:/ classfile decoding.
  *
  * Owns all 8 plan leaves for F-A3-001..005, F-A1-005, and F-A1-009. Un-pends the corresponding stubs in RealClasspathFidelity2Test and
  * covers the full set of JDK class shapes reachable after initWithPlatformModules walks jrt:/ classfiles.
  *
  * JDK version requirement: JDK 11+ for jrt:/ filesystem; JDK 14+ for java.lang.Runtime.Version records; JDK 17+ for sealed classes
  * (java.lang.constant.Constable PermittedSubclasses). The running JVM must be JDK 17 or later for all leaves to pass.
  */
class JpmsFidelity2Test extends Test:

    // JDK classfile decoding loads java.base (~7,000 entries) from jrt:/ via TestClasspaths2.standardWithPlatformModules.
    // The load is started in a background thread at object init time; tests block until it completes.
    // Allow 3 minutes per test to give headroom on loaded CI machines; the actual load takes 10-30s.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(3))

    import AllowUnsafe.embrace.danger

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.03 ACTIVE leaves
    // ─────────────────────────────────────────────────────────────────────────

    // Leaf 1 (Phase 2.03): F-A3-001 -- java.lang.String resolves
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules (initWithPlatformModules)
    // When: calling cp.findClass("java.lang.String")
    // Then: returns Present (jrt:/ walker enumerates String.class and ClassfileUnpickler decodes it)
    // Pins: F-A3-001
    "F-A3-001 (Phase 2.03): findClass('java.lang.String') returns Present after jrt:/ walk" in run {
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

    // Leaf 2 (Phase 2.03): java.util.HashMap resolves
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.findClass("java.util.HashMap")
    // Then: returns Present; HashMap.class is in java.base under jrt:/modules/java.base/java/util/HashMap.class
    // Pins: F-A3-001 (extended to java.util)
    "F-A3-001b (Phase 2.03): findClass('java.util.HashMap') returns Present after jrt:/ walk" in run {
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

    // Leaf 3 (Phase 2.03): java.util.concurrent.ConcurrentHashMap resolves
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.findClass("java.util.concurrent.ConcurrentHashMap")
    // Then: returns Present; java.util.concurrent is in java.base
    // Pins: F-A3-001 (extended to java.util.concurrent)
    "F-A3-001c (Phase 2.03): findClass('java.util.concurrent.ConcurrentHashMap') returns Present after jrt:/ walk" in run {
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

    // Leaf 4 (Phase 2.03): F-A3-005 -- cp.modules contains "java.base"
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.findModule("java.base")
    // Then: returns Present with at least one export starting with "java.lang"
    // Pins: F-A3-005
    "F-A3-005 (Phase 2.03): cp.modules contains 'java.base' after jrt:/ walker addition" in run {
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
                        moduleCount >= 20,
                        s"Expected >= 20 JPMS modules after jrt:/ walk (JDK typically has 69+), found $moduleCount"
                    )
                    succeed
                case Absent =>
                    fail(
                        "cp.findModule('java.base') returned Absent after initWithPlatformModules; " +
                            "module-info.class for java.base was not decoded from jrt:/"
                    )
    }

    // Leaf 5 (Phase 2.03): JDK class parentTypes includes java.lang.Object
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules; java.util.HashMap found
    // When: examining cp.findClass("java.util.HashMap").parentTypes
    // Then: at least one parent resolves to a non-sentinel symbol id (java.lang.AbstractMap or java.util.Map)
    // Pins: F-A3-001 parent wiring in finalizeMerge
    "F-A3-001d (Phase 2.03): JDK class parentTypes wired (HashMap parents include AbstractMap or Object)" in run {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            given Tasty.Classpath = cp
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

    // Leaf 6 (Phase 2.03): F-A3-002 -- Java enum isEnum
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.findClass("java.lang.annotation.RetentionPolicy")
    // Then: Present(sym) with sym.isEnum == true; RetentionPolicy is a JDK enum
    // Pins: F-A3-002
    "F-A3-002 (Phase 2.03): findClass('java.lang.annotation.RetentionPolicy').isEnum is true" in run {
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

    // Leaf 7 (Phase 2.03): Java sealed class permittedSubclasses populated (JDK 17+)
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules; JDK >= 17 for PermittedSubclasses attribute
    // When: calling cp.findClassLike("java.lang.constant.ConstantDesc")
    // Then: Present(sym) with sym.isSealed == true and sym.permittedSubclasses returns Present with >= 1 entry
    // JDK version: ConstantDesc is sealed since JDK 12. Note: java.lang.constant.Constable was sealed in
    // JDK 17-21 but became non-sealed in JDK 22+; ConstantDesc remains sealed through JDK 25.
    // Pins: F-A3-003 (Java sealed)
    "F-A3-003 (Phase 2.03): java.lang.constant.ConstantDesc permittedSubclasses populated (JDK 12+)" in run {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            given Tasty.Classpath = cp
            val jdkVersion        = java.lang.Runtime.version().feature()
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
                        sym.permittedSubclasses match
                            case Absent =>
                                fail(
                                    s"java.lang.constant.ConstantDesc.permittedSubclasses is Absent on JDK $jdkVersion; " +
                                        "ClassfileUnpickler did not decode PermittedSubclasses attribute"
                                )
                            case Present(subs) =>
                                assert(
                                    subs.nonEmpty,
                                    s"java.lang.constant.ConstantDesc.permittedSubclasses is empty on JDK $jdkVersion; " +
                                        "expected at least one permitted subclass (e.g. Double, Integer, String)"
                                )
                                succeed
                        end match
                    else
                        // JDK < 12: ConstantDesc does not exist; skip.
                        succeed
            end match
    }

    // Leaf 8 (Phase 2.03): Java interface default methods detectable
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules; java.util.Iterator found
    // When: enumerating java.util.Iterator.declarations and filtering for non-abstract methods
    // Then: at least one non-abstract method exists (the default method forEachRemaining added in Java 8)
    // Java interface default methods are NOT marked Abstract (ACC_ABSTRACT is clear); abstract interface methods ARE Abstract.
    // Pins: F-A3-004 (Java interface default methods)
    "F-A3-004 (Phase 2.03): java.util.Iterator has non-abstract method declarations (default methods)" in run {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            given Tasty.Classpath = cp
            cp.findClassLike("java.util.Iterator") match
                case Absent =>
                    fail(
                        "cp.findClassLike('java.util.Iterator') returned Absent; " +
                            "jrt:/ walker did not enumerate java/util/Iterator.class"
                    )
                case Present(sym) =>
                    val allMethods = sym.methods
                    assert(
                        allMethods.nonEmpty,
                        "java.util.Iterator.methods is empty; expected at least hasNext, next, remove, forEachRemaining"
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

    // Leaf 9 (Phase 2.03): F-A1-005 -- initWithPlatformModules includes JDK class symbols
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules (kyo-tasty roots + JDK)
    // When: verifying both kyo.Tasty and java.lang.String are findable
    // Then: both return Present; demonstrates user roots and JDK roots are merged
    // Pins: F-A1-005
    "F-A1-005 (Phase 2.03): initWithPlatformModules includes both user TASTy and JDK class symbols" in run {
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

    // Leaf 10 (Phase 2.03): F-A1-009 -- unresolvedTypeReferenceCount == 0 with JDK
    // Given: Classpath loaded via TestClasspaths2.standardWithPlatformModules
    // When: calling cp.unresolvedTypeReferenceCount
    // Then: the count is 0 (all Named(-1) sentinel parent refs are wired once JDK classfiles are present)
    // Note: the JDK classpath provides java.lang.Object, java.lang.Enum, java.io.Serializable etc. so all
    // parentTypes from user TASTy that reference JDK types can now resolve.
    // Pins: F-A1-009
    "F-A1-009 (Phase 2.03): cp.unresolvedTypeReferenceCount == 0 on full classpath including JDK" in run {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            val unresolved = cp.unresolvedTypeReferenceCount
            assert(
                unresolved == 0,
                s"Expected 0 unresolved type references with full JDK classpath, found $unresolved. " +
                    s"Non-zero means some parentTypes still carry Named(-1) sentinels after JDK class decode."
            )
            succeed
    }

end JpmsFidelity2Test
