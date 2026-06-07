package kyo

import java.nio.charset.StandardCharsets

/** Native implementation: serves known fixture bytes from `Embedded` (classfile + .tasty fixtures) and
  * known text bytes from `EmbeddedText` (README.md, CONTRIBUTING.md, source files). Both are sbt-generated
  * for the Native target so tests do not need a classloader resource path.
  */
object TestResourceLoader:

    private val embeddedTextKeys: Set[String] = Set(
        "README.md",
        "CONTRIBUTING.md",
        "kyo/Tasty.scala",
        "kyo/internal/tasty/classfile/ClassfileUnpickler.scala",
        "kyo/internal/tasty/symbol/Constant.scala"
    )

    def loadBytes(resourcePath: String): Array[Byte] =
        if embeddedTextKeys.contains(resourcePath) then
            EmbeddedText.get(resourcePath).getBytes(StandardCharsets.UTF_8)
        else
            resourcePath match
                case p if p.endsWith("PlainClass.tasty")             => kyo.fixtures.Embedded.plainClassTasty
                case p if p.endsWith("SomeObject.tasty")             => kyo.fixtures.Embedded.someObjectTasty
                case p if p.endsWith("SomeTrait.tasty")              => kyo.fixtures.Embedded.someTraitTasty
                case p if p.endsWith("GenericBox.tasty")             => kyo.fixtures.Embedded.genericBoxTasty
                case p if p.endsWith("Outer.tasty")                  => kyo.fixtures.Embedded.outerTasty
                case p if p.endsWith("SomeCaseClass.tasty")          => kyo.fixtures.Embedded.someCaseClassTasty
                case p if p.endsWith("Color.tasty")                  => kyo.fixtures.Embedded.colorTasty
                case p if p.endsWith("FixtureClasses$package.tasty") => kyo.fixtures.Embedded.fixtureClassesPackageTasty
                case p if p.endsWith("BaseClass.tasty")              => kyo.fixtures.Embedded.baseClassTasty
                case p if p.endsWith("ChildClass.tasty")             => kyo.fixtures.Embedded.childClassTasty
                case p if p.endsWith("Shape.tasty")                  => kyo.fixtures.Embedded.shapeTasty
                case other =>
                    throw new RuntimeException(s"Native: resource not embedded: $other; add to Embedded.scala or EmbeddedText (build.sbt)")

end TestResourceLoader
