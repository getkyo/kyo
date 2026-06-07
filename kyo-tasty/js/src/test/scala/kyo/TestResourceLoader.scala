package kyo

/** JS implementation: serves `.tasty` and `.class` fixture bytes from `kyo.fixtures.Embedded`
  * (an sbt-generated table in the kyo-tasty-fixtures module). Documentation files and source
  * files are not legitimate test inputs and are not served here.
  */
object TestResourceLoader:

    def loadBytes(resourcePath: String): Array[Byte] =
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
                throw new RuntimeException(s"JS: fixture not embedded: $other; add to Embedded.scala in kyo-tasty-fixtures")

end TestResourceLoader
