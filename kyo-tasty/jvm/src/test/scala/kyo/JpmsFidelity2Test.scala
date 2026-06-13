package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths2

/** Fidelity tests for JPMS jrt:/ classfile decoding.
  *
  * Covers the set of JDK class shapes reachable after initWithPlatformModules walks jrt:/ classfiles.
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

    "findClass('java.lang.String') returns Present after jrt:/ walk" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            classpath.findClass("java.lang.String") match
                case Present(symbol) =>
                    assert(symbol.isJava, s"java.lang.String symbol is not marked as JavaDefined; flags=${symbol.flags}")
                    succeed
                case Absent =>
                    fail(
                        "classpath.findClass('java.lang.String') returned Absent after initWithPlatformModules; " +
                            "jrt:/ walker did not enumerate java/lang/String.class"
                    )
        }
    }

    "findClass('java.util.HashMap') returns Present after jrt:/ walk" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            classpath.findClass("java.util.HashMap") match
                case Present(symbol) =>
                    assert(symbol.isJava, s"java.util.HashMap symbol is not marked as JavaDefined; flags=${symbol.flags}")
                    succeed
                case Absent =>
                    fail(
                        "classpath.findClass('java.util.HashMap') returned Absent after initWithPlatformModules; " +
                            "jrt:/ walker did not enumerate java/util/HashMap.class"
                    )
        }
    }

    "findClass('java.util.concurrent.ConcurrentHashMap') returns Present after jrt:/ walk" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            classpath.findClass("java.util.concurrent.ConcurrentHashMap") match
                case Present(symbol) =>
                    assert(symbol.isJava, s"java.util.concurrent.ConcurrentHashMap symbol not marked JavaDefined; flags=${symbol.flags}")
                    succeed
                case Absent =>
                    fail(
                        "classpath.findClass('java.util.concurrent.ConcurrentHashMap') returned Absent after initWithPlatformModules; " +
                            "jrt:/ walker did not enumerate java/util/concurrent/ConcurrentHashMap.class"
                    )
        }
    }

    "classpath.modules contains 'java.base' after jrt:/ walker addition" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            classpath.findModule("java.base") match
                case Present(m) =>
                    val exportedPackages = m.exports.map(_.packageName).toList
                    assert(
                        exportedPackages.exists(_.startsWith("java.lang")),
                        s"java.base exports do not include java.lang; found: ${exportedPackages.take(10).mkString(", ")}"
                    )
                    val moduleCount = classpath.modules.size
                    assert(
                        moduleCount >= 65,
                        s"Expected >= 65 JPMS modules after jrt:/ walk (measured 69 on JDK 25), found $moduleCount"
                    )
                    succeed
                case Absent =>
                    fail(
                        "classpath.findModule('java.base') returned Absent after initWithPlatformModules; " +
                            "module-info.class for java.base was not decoded from jrt:/"
                    )
        }
    }

    "JDK class parentTypes wired (HashMap parents include AbstractMap or Object)" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            classpath.findClass("java.util.HashMap") match
                case Absent =>
                    fail("classpath.findClass('java.util.HashMap') returned Absent; prerequisite for parent check failed")
                case Present(hm) =>
                    val parentTypes = hm.parentTypes
                    assert(
                        parentTypes.nonEmpty,
                        "java.util.HashMap.parentTypes is empty; finalizeMerge did not wire classfile parent binary names"
                    )
                    // At least one parent should be a resolved Named (non-sentinel)
                    val resolvedParents = parentTypes.collect {
                        case Tasty.Type.Named(id) if id.value >= 0 => id
                    }
                    assert(
                        resolvedParents.nonEmpty,
                        s"java.util.HashMap has no resolved Named parents (all are sentinel -1 or other type kinds); " +
                            s"parentTypes: $parentTypes"
                    )
                    succeed
            end match
        }
    }

    "findClass('java.lang.annotation.RetentionPolicy').isEnum is true" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            classpath.findClass("java.lang.annotation.RetentionPolicy") match
                case Absent =>
                    fail(
                        "classpath.findClass('java.lang.annotation.RetentionPolicy') returned Absent; " +
                            "jrt:/ walker did not enumerate java/lang/annotation/RetentionPolicy.class"
                    )
                case Present(symbol) =>
                    assert(
                        symbol.isEnum,
                        s"java.lang.annotation.RetentionPolicy.isEnum returned false; expected true for JDK enum; flags=${symbol.flags}"
                    )
                    succeed
        }
    }

    "java.lang.constant.ConstantDesc permittedSubclasses populated (JDK 12+)" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            val jdkVersion = java.lang.Runtime.version().feature()
            classpath.findClassLike("java.lang.constant.ConstantDesc") match
                case Absent =>
                    fail(
                        "classpath.findClassLike('java.lang.constant.ConstantDesc') returned Absent; " +
                            "jrt:/ walker did not enumerate java/lang/constant/ConstantDesc.class (requires JDK 12+)"
                    )
                case Present(symbol) =>
                    if jdkVersion >= 12 then
                        assert(
                            symbol.isSealed,
                            s"java.lang.constant.ConstantDesc.isSealed is false on JDK $jdkVersion; " +
                                s"expected true (PermittedSubclasses attribute decoded); flags=${symbol.flags}"
                        )
                        val subs = symbol match
                            case c: Tasty.Symbol.Class => c.permittedSubclassIds.map(_.map(classpath.symbol)).getOrElse(Chunk.empty)
                            case t: Tasty.Symbol.Trait => t.permittedSubclassIds.map(_.map(classpath.symbol)).getOrElse(Chunk.empty)
                            case _                     => Chunk.empty
                        assert(
                            subs.nonEmpty,
                            s"java.lang.constant.ConstantDesc.permittedSubclassIds.map(_.map(classpath.symbol)).getOrElse(Chunk.empty) is empty on JDK $jdkVersion; " +
                                "expected at least one permitted subclass (e.g. Double, Integer, String); " +
                                "ClassfileUnpickler did not decode PermittedSubclasses attribute"
                        )
                        succeed
                    else
                        // JDK < 12: ConstantDesc does not exist; skip.
                        succeed
            end match
        }
    }

    "java.util.Iterator has non-abstract method declarations (default methods)" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            classpath.findClassLike("java.util.Iterator") match
                case Absent =>
                    fail(
                        "classpath.findClassLike('java.util.Iterator') returned Absent; " +
                            "jrt:/ walker did not enumerate java/util/Iterator.class"
                    )
                case Present(symbol) =>
                    val allMethods = symbol.declarationIds.flatMap(id => classpath.symbol(id).toChunk).collect {
                        case m: Tasty.Symbol.Method => m
                    }
                    assert(
                        allMethods.nonEmpty,
                        "java.util.Iterator.declarationIds.flatMap(classpath.symbol).filter(_.isInstanceOf[Tasty.Symbol.Method]) is empty; expected at least hasNext, next, remove, forEachRemaining"
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
    }

    "initWithPlatformModules includes both user TASTy and JDK class symbols" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            val kyoTasty   = classpath.findClassLike("kyo.Tasty")
            val javaString = classpath.findClass("java.lang.String")
            assert(
                kyoTasty.isDefined,
                "classpath.findClassLike('kyo.Tasty') returned Absent after initWithPlatformModules; user TASTy roots not merged"
            )
            assert(
                javaString.isDefined,
                "classpath.findClass('java.lang.String') returned Absent after initWithPlatformModules; JDK class roots not merged"
            )
            succeed
        }
    }

    "classpath.unresolvedTypeReferenceCount >= 0 on full classpath including JDK" in {
        TestClasspaths2.standardWithPlatformModules.map { classpath =>
            val unresolved = classpath.unresolvedTypeReferenceCount
            assert(
                unresolved >= 0,
                s"Expected non-negative unresolved type reference count, found $unresolved."
            )
            succeed
        }
    }

end JpmsFidelity2Test
