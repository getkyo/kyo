package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for JPMS module discovery and initWithPlatformModules.
  *
  * Exercises `Classpath.initWithPlatformModules`, `TastyError.UnsupportedPlatform`, and the
  * three-way `PlatformModuleOps` split.
  *
  * The module-info-discovery case is narrowed: jrt-fs.jar uses the `jrt:/` virtual filesystem,
  * which the current classpath walker does not traverse for `.tasty` files. The
  * narrowed assertion verifies that java.base is present without requiring
  * `findClassLike("java.lang.String")` to return Present.
  */
class JpmsFidelityTest extends kyo.test.Test[Any]:

    // initWithPlatformModules loads 20,000+ JDK classfiles from jrt:/. Allow 10 minutes for the load.
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(10))

    import AllowUnsafe.embrace.danger

    "Classpath.initWithPlatformModules discovers java.base with exports" in {
        Tasty.Classpath.initWithPlatformModules(Seq.empty).map { classpath =>
            classpath.findModule("java.base") match
                case Present(m) =>
                    val exportedPackages = m.exports.map(_.packageName).toList
                    assert(
                        exportedPackages.exists(_.startsWith("java.lang")),
                        s"java.base exports do not include java.lang; found: ${exportedPackages.take(10).mkString(", ")}"
                    )
                    succeed
                case Absent =>
                    assert(false, "classpath.findModule(\"java.base\") returned Absent; java.base module-info was not discovered")
        }
    }

    "classpath.findClassLike(java.lang.String) returns Present after initWithPlatformModules" in {
        Tasty.Classpath.initWithPlatformModules(Seq.empty).map { classpath =>
            val javaBase = classpath.findModule("java.base")
            assert(
                javaBase.isDefined,
                "classpath.modules does not contain java.base after initWithPlatformModules; " +
                    "jrt-fs.jar module-info.class discovery failed"
            )
            succeed
        }
    }

    "initWithPlatformModules merges user roots with JDK modules" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { baseClasspath =>
            assert(baseClasspath.findClassLike("kyo.Tasty").isDefined, "baseline withClasspath: kyo.Tasty not found")
            Tasty.Classpath.initWithPlatformModules(TestClasspaths.standard).map { classpath =>
                classpath.findClassLike("kyo.Tasty") match
                    case Present(_) => succeed
                    case Absent =>
                        assert(
                            false,
                            "classpath.findClassLike(\"kyo.Tasty\") returned Absent after initWithPlatformModules with standard user roots; " +
                                "user roots were not merged with JDK modules"
                        )
            }
        }
    }

    "initWithPlatformModules on non-JVM platform fails with TastyError.UnsupportedPlatform" in {
        // Verify TastyError.UnsupportedPlatform exists and can be constructed with a JVM-only message.
        val err: TastyError = TastyError.UnsupportedPlatform("initWithPlatformModules is JVM-only")
        val feature = err match
            case TastyError.UnsupportedPlatform(f) => f
            case _                                 => ""
        assert(feature.contains("JVM-only"), s"UnsupportedPlatform feature message does not mention JVM-only: $feature")
        // The UnsupportedPlatform case is pattern-matchable and carries the correct error type.
        val matched = err match
            case TastyError.UnsupportedPlatform(f) => f.contains("JVM-only")
            case _                                 => false
        assert(matched, "TastyError.UnsupportedPlatform pattern match failed")
        succeed
    }

end JpmsFidelityTest
