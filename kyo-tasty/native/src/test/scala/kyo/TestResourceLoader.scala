package kyo

/** Native implementation: serves known fixture bytes from Embedded; throws for unknown paths. */
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
            case other =>
                throw new RuntimeException(s"Native: fixture not embedded: $other; add to Embedded.scala")

end TestResourceLoader
