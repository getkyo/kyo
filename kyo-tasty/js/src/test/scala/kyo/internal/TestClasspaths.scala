package kyo.internal

import kyo.*
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** JS-side cross-platform fixture helper.
  *
  * Mirrors the surface of the JVM `TestClasspaths` object so shared test code can call `TestClasspaths.withClasspath` on all three
  * platforms. On JS there is no filesystem-based classpath discovery; the helper constructs a `MemoryFileSource` pre-loaded from
  * `kyo.fixtures.Embedded` and calls `ClasspathOrchestrator.init` directly, bypassing `JsFileSource` entirely.
  *
  * Design notes:
  *   - `withClasspath` takes no `roots` parameter (no JVM classpath on JS).
  *   - Concurrency is fixed at 1 (JS is single-threaded; higher values have no effect and would be misleading).
  *   - All 13 embedded TASTy fixtures are included (PlainClass, SomeObject, SomeTrait, GenericBox, Outer, SomeCaseClass, Color,
  *     FixtureClasses$package, BaseClass, ChildClass, Shape, VarargFixture, TypeAdtFixture$package). Shape carries class-form enum cases
  *     (Phase 15 addition). VarargFixture carries a String* varargs parameter (Phase 2.10 addition).
  *     TypeAdtFixture$package carries intersection/union/match types (Phase 2.10 addition).
  *   - The `roots` parameter mirrors the JVM surface but is ignored; embedded fixtures are always loaded.
  *   - HARD RULE 7: the MemoryFileSource is internal to the loading call; it is not exposed to callers.
  *
  * Scaladoc: 8-35 lines.
  */
private[kyo] object TestClasspaths:

    /** Build a `Tasty.Classpath` from the embedded TASTy fixtures.
      *
      * Returns a Kyo effect that initialises the classpath within the surrounding `Sync & Async & Scope & Abort[TastyError]` context. Call
      * inside a `run { ... }` test body. The `roots` parameter is ignored on JS (no filesystem); embedded fixtures are always used.
      */
    def withClasspath(roots: Seq[String] = Seq.empty)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        src.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        src.add("root/GenericBox.tasty", kyo.fixtures.Embedded.genericBoxTasty)
        src.add("root/Outer.tasty", kyo.fixtures.Embedded.outerTasty)
        src.add("root/SomeCaseClass.tasty", kyo.fixtures.Embedded.someCaseClassTasty)
        src.add("root/Color.tasty", kyo.fixtures.Embedded.colorTasty)
        src.add("root/FixtureClasses$package.tasty", kyo.fixtures.Embedded.fixtureClassesPackageTasty)
        src.add("root/BaseClass.tasty", kyo.fixtures.Embedded.baseClassTasty)
        src.add("root/ChildClass.tasty", kyo.fixtures.Embedded.childClassTasty)
        src.add("root/Shape.tasty", kyo.fixtures.Embedded.shapeTasty)
        src.add("root/VarargFixture.tasty", kyo.fixtures.Embedded.varargFixtureTasty)
        src.add("root/TypeAdtFixture$package.tasty", kyo.fixtures.Embedded.typeAdtFixtureTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end withClasspath

end TestClasspaths
