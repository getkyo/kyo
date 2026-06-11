package kyo.demos

import kyo.*
import kyo.Tasty.*
import kyo.fixtures.PlainClass
import kyo.internal.TestClasspaths

/** Runtime-reflection replacement: extract member information about a known Scala class without loading it.
  *
  * Replaces use cases that would reach for `scala.reflect.runtime` or `java.lang.reflect`. Compile-time type info from
  * TASTy means no runtime classloading; the same code works on JVM, JS, and Native. `classFullName[A]` recovers the
  * lookup key at compile time (it must be applied to a concrete type, so the caller resolves it at the call site);
  * `findClass` + `declarationIds` then recover the declared members.
  *
  * Assertions pin the declared `val x` parameter and the synthesized `<init>` method of `kyo.fixtures.PlainClass`.
  */
class RuntimeReflectionDemoTest extends kyo.test.Test[Any]:

    /** Returns the declared members of the class at `fullName` as `(name, kind)` pairs. */
    private def membersOf(classpath: Tasty.Classpath, fullName: String): Maybe[Chunk[(String, String)]] =
        classpath.findClass(fullName) match
            case Absent => Maybe.Absent
            case Present(cls) =>
                val decls = cls.declarationIds.flatMap(id => classpath.symbol(id).toChunk)
                Maybe.Present(decls.map(d => (d.name.asString, d.kind.toString)))

    "membersOf recovers the constructor parameter and initializer of kyo.fixtures.PlainClass" in {
        // classFullName must be applied to the concrete type at the call site; A would be erased inside a generic helper.
        val fullName = Tasty.classFullName[PlainClass]
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            membersOf(classpath, fullName) match
                case Present(members) =>
                    val names = members.map(_._1)
                    assert(
                        names.contains("x"),
                        s"Expected declared member 'x' (the val parameter) of PlainClass, got: ${names.mkString(", ")}"
                    )
                    assert(
                        names.contains("<init>"),
                        s"Expected the synthesized '<init>' method of PlainClass, got: ${names.mkString(", ")}"
                    )
                    succeed
                case Absent =>
                    fail(s"membersOf returned Absent for full name '$fullName'")
        }
    }

    "classFullName resolves the compile-time full name of PlainClass" in {
        val fullName = Tasty.classFullName[PlainClass]
        assert(
            fullName == "kyo.fixtures.PlainClass",
            s"Expected 'kyo.fixtures.PlainClass', got '$fullName'"
        )
        succeed
    }

end RuntimeReflectionDemoTest
