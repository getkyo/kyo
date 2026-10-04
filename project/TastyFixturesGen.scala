import sbt.*

/** Source generator for kyo-tasty's `kyo.fixtures.Embedded`: the fixture files its suites read, as byte arrays, so Scala.js, Scala Native
  * and Wasm, which have no classpath directory to read them from, test the same bytes the JVM does.
  *
  * The TASTy and classfiles come from the JVM compile of `kyo-tasty-fixtures-internal`, so a fixture edit reaches every platform with no
  * manual step. The Java classfiles have no compile step in the build and are read from kyo-tasty's test resources.
  */
object TastyFixturesGen {

    private val Generator = "TastyFixturesGen"

    sealed trait Origin
    case object Compiled extends Origin
    case object Resource extends Origin

    // The members are the API the suites call; each names the file it holds.
    private val members: Seq[(String, Origin, String)] = Seq(
        ("plainClassTasty", Compiled, "kyo/fixtures/PlainClass.tasty"),
        ("arrayRecordClass", Resource, "kyo/fixtures/ArrayRecord.class"),
        ("someObjectTasty", Compiled, "kyo/fixtures/SomeObject.tasty"),
        ("someTraitTasty", Compiled, "kyo/fixtures/SomeTrait.tasty"),
        ("genericBoxTasty", Compiled, "kyo/fixtures/GenericBox.tasty"),
        ("outerTasty", Compiled, "kyo/fixtures/Outer.tasty"),
        ("someCaseClassTasty", Compiled, "kyo/fixtures/SomeCaseClass.tasty"),
        ("colorTasty", Compiled, "kyo/fixtures/Color.tasty"),
        ("fixtureClassesPackageTasty", Compiled, "kyo/fixtures/FixtureClasses$package.tasty"),
        ("baseClassTasty", Compiled, "kyo/fixtures/BaseClass.tasty"),
        ("childClassTasty", Compiled, "kyo/fixtures/ChildClass.tasty"),
        ("shapeTasty", Compiled, "kyo/fixtures/Shape.tasty"),
        ("varargFixtureTasty", Compiled, "kyo/fixtures/VarargFixture.tasty"),
        ("typeAdtFixtureTasty", Compiled, "kyo/fixtures/TypeAdtFixture$package.tasty"),
        ("containerTasty", Compiled, "kyo/fixtures/Container.tasty"),
        ("annotatedFixturePackageTasty", Compiled, "kyo/fixtures/AnnotatedFixture$package.tasty"),
        ("annotatedFixtureDeprecatedTasty", Compiled, "kyo/fixtures/AnnotatedFixtureDeprecated.tasty"),
        ("annotatedFixtureMethodsTasty", Compiled, "kyo/fixtures/AnnotatedFixtureMethods.tasty"),
        ("animalTasty", Compiled, "kyo/fixtures/Animal.tasty"),
        ("dogTasty", Compiled, "kyo/fixtures/Dog.tasty"),
        ("catTasty", Compiled, "kyo/fixtures/Cat.tasty"),
        ("vehicleTasty", Compiled, "kyo/fixtures/Vehicle.tasty"),
        ("carTasty", Compiled, "kyo/fixtures/Car.tasty"),
        ("bikeTasty", Compiled, "kyo/fixtures/Bike.tasty"),
        ("nonSealedMarkerTasty", Compiled, "kyo/fixtures/NonSealedMarker.tasty"),
        ("opaqueFixturePackageTasty", Compiled, "kyo/fixtures/OpaqueFixture$package.tasty"),
        ("sealedBaseTasty", Compiled, "kyo/fixtures/SealedBase.tasty"),
        ("concreteATasty", Compiled, "kyo/fixtures/ConcreteA.tasty"),
        ("concreteBTasty", Compiled, "kyo/fixtures/ConcreteB.tasty"),
        ("contextFunctionFixturePackageTasty", Compiled, "kyo/fixtures/ContextFunctionFixture$package.tasty"),
        ("contextFunctionFixtureTasty", Compiled, "kyo/fixtures/ContextFunctionFixture.tasty"),
        ("loggerFixtureTasty", Compiled, "kyo/fixtures/Logger.tasty"),
        ("configFixtureTasty", Compiled, "kyo/fixtures/Config.tasty"),
        ("pointRecordClass", Resource, "kyo/fixtures/PointRecord.class"),
        ("throwsFixtureClass", Resource, "kyo/fixtures/ThrowsFixture.class"),
        ("anonymousFixture1Class", Resource, "kyo/fixtures/AnonymousFixture$1.class"),
        ("treeVariantFixturePackageTasty", Compiled, "kyo/fixtures/TreeVariantFixture$package.tasty"),
        ("hasTypeDefTasty", Compiled, "kyo/fixtures/HasTypeDef.tasty"),
        ("selfDefFixtureTasty", Compiled, "kyo/fixtures/SelfDefFixture.tasty"),
        ("superFixtureBaseTasty", Compiled, "kyo/fixtures/SuperFixtureBase.tasty"),
        ("superFixtureTasty", Compiled, "kyo/fixtures/SuperFixture.tasty"),
        ("superTypeFixtureBaseTasty", Compiled, "kyo/fixtures/SuperTypeFixtureBase.tasty"),
        ("superTypeFixtureTasty", Compiled, "kyo/fixtures/SuperTypeFixture.tasty"),
        ("recFixtureTasty", Compiled, "kyo/fixtures/RecFixture.tasty"),
        ("useIdentTptTasty", Compiled, "kyo/fixtures/UseIdentTpt.tasty"),
        ("typeRefDirectFixtureTasty", Compiled, "kyo/fixtures/TypeRefDirectFixture.tasty"),
        ("typeRefSymbolFixtureTasty", Compiled, "kyo/fixtures/TypeRefSymbolFixture.tasty"),
        ("outerForSelectOuterTasty", Compiled, "kyo/fixtures/OuterForSelectOuter.tasty"),
        ("portedBug108Tasty", Compiled, "kyo/fixtures/PortedBug108.tasty"),
        ("portedBug11075ATasty", Compiled, "kyo/fixtures/PortedBug11075A.tasty"),
        ("portedBug11075BTasty", Compiled, "kyo/fixtures/PortedBug11075B.tasty"),
        ("portedBug116IArraySigTasty", Compiled, "kyo/fixtures/PortedBug116IArraySig.tasty"),
        ("portedBug125Tasty", Compiled, "kyo/fixtures/PortedBug125.tasty"),
        ("portedBug12704CaseClassTasty", Compiled, "kyo/fixtures/PortedBug12704CaseClass.tasty"),
        ("portedBug134Tasty", Compiled, "kyo/fixtures/PortedBug134.tasty"),
        ("portedBug16843Tasty", Compiled, "kyo/fixtures/PortedBug16843.tasty"),
        ("portedBug172OuterTasty", Compiled, "kyo/fixtures/PortedBug172Outer.tasty"),
        ("portedBug178Tasty", Compiled, "kyo/fixtures/PortedBug178.tasty"),
        ("portedBug187OverloadedApplyTasty", Compiled, "kyo/fixtures/PortedBug187OverloadedApply.tasty"),
        ("portedBug192Tasty", Compiled, "kyo/fixtures/PortedBug192.tasty"),
        ("portedBug193HolderTasty", Compiled, "kyo/fixtures/PortedBug193Holder.tasty"),
        ("portedBug193OuterTasty", Compiled, "kyo/fixtures/PortedBug193Outer.tasty"),
        ("portedBug193SuperClassTasty", Compiled, "kyo/fixtures/PortedBug193SuperClass.tasty"),
        ("portedBug195Tasty", Compiled, "kyo/fixtures/PortedBug195.tasty"),
        ("portedBug213Tasty", Compiled, "kyo/fixtures/PortedBug213.tasty"),
        ("portedBug224ATasty", Compiled, "kyo/fixtures/PortedBug224A.tasty"),
        ("portedBug224BTasty", Compiled, "kyo/fixtures/PortedBug224B.tasty"),
        ("portedBug224CTasty", Compiled, "kyo/fixtures/PortedBug224C.tasty"),
        ("portedBug25801Tasty", Compiled, "kyo/fixtures/PortedBug25801.tasty"),
        ("portedBug263ClassAndPackageObjectSameNameTasty", Compiled, "kyo/fixtures/PortedBug263ClassAndPackageObjectSameName.tasty"),
        ("portedBug284Tasty", Compiled, "kyo/fixtures/PortedBug284.tasty"),
        ("portedBug357Tasty", Compiled, "kyo/fixtures/PortedBug357.tasty"),
        ("portedBug380FooTasty", Compiled, "kyo/fixtures/PortedBug380Foo.tasty"),
        ("portedBug401Tasty", Compiled, "kyo/fixtures/PortedBug401.tasty"),
        ("portedBug403ContainerTasty", Compiled, "kyo/fixtures/PortedBug403Container.tasty"),
        ("portedBug405ParamValueClassTasty", Compiled, "kyo/fixtures/PortedBug405ParamValueClass.tasty"),
        ("portedBug414Tasty", Compiled, "kyo/fixtures/PortedBug414.tasty"),
        ("portedBug415FTasty", Compiled, "kyo/fixtures/PortedBug415F.tasty"),
        ("portedBug415HolderTasty", Compiled, "kyo/fixtures/PortedBug415Holder.tasty"),
        ("portedBug424Tasty", Compiled, "kyo/fixtures/PortedBug424.tasty"),
        ("portedBug428ValueClassTasty", Compiled, "kyo/fixtures/PortedBug428ValueClass.tasty"),
        ("portedBug464Tasty", Compiled, "kyo/fixtures/PortedBug464.tasty"),
        ("portedBug7Tasty", Compiled, "kyo/fixtures/PortedBug7.tasty"),
        ("portedBug7022CTasty", Compiled, "kyo/fixtures/PortedBug7022C.tasty"),
        ("portedBug7022PTasty", Compiled, "kyo/fixtures/PortedBug7022P.tasty"),
        ("portedBug74ObjectTasty", Compiled, "kyo/fixtures/PortedBug74Object.tasty"),
        ("portedBug80UsesRawAwareTasty", Compiled, "kyo/fixtures/PortedBug80UsesRawAware.tasty"),
        ("portedBugFixturePackageTasty", Compiled, "kyo/fixtures/PortedBugFixture$package.tasty"),
        ("portedBug71InnerMarkerTasty", Compiled, "kyo/fixtures/portedBug71Outer/portedBug71Inner/Marker.tasty"),
        ("plainClassClassfile", Compiled, "kyo/fixtures/PlainClass.class"),
        ("crossFileTargetTasty", Compiled, "kyo/fixtures/CrossFileTarget.tasty"),
        ("crossFileUserTasty", Compiled, "kyo/fixtures/CrossFileUser.tasty"),
        ("crossFileUser2Tasty", Compiled, "kyo/fixtures/CrossFileUser2.tasty"),
        ("crossFileTarget2Tasty", Compiled, "kyo/fixtures/CrossFileTarget2.tasty"),
        ("crossFileUser3Tasty", Compiled, "kyo/fixtures/CrossFileUser3.tasty"),
        ("crossFileModuleTasty", Compiled, "kyo/fixtures/CrossFileModule.tasty"),
        ("crossFileModuleUserTasty", Compiled, "kyo/fixtures/CrossFileModuleUser.tasty")
    )

    /** Task body for `Test / sourceGenerators`: `compiledDir` is the fixtures module's JVM class directory, `resourcesDir` kyo-tasty's test
      * resources.
      */
    def generate(compiledDir: File, resourcesDir: File, outDir: File): Seq[File] = {
        val pkgDir = outDir / "kyo" / "fixtures"
        IO.createDirectory(pkgDir)
        val sb = new StringBuilder
        sb.append(VendoredFiles.header(Generator, "the kyo-tasty-fixtures-internal JVM compile and kyo-tasty's test resources", Nil))
        sb.append("package kyo.fixtures\n\n")
        sb.append("/** The fixture files kyo-tasty's suites read, as byte arrays, for platforms with no classpath directory to read them from. */\n")
        sb.append("object Embedded:\n\n")
        sb.append(decoder)
        members.foreach { case (name, origin, path) =>
            val file  = (if (origin == Compiled) compiledDir else resourcesDir) / path
            if (!file.isFile) sys.error(s"[$Generator] $name: no fixture at $file")
            val bytes = IO.readBytes(file)
            sb.append(s"\n    /** Contents of `$path` (${bytes.length} bytes). */\n")
            sb.append("    def " + name + ": Array[Byte] = decode(\"\"\"\n")
            bytes.map(b => f"${b & 0xff}%02x").mkString.grouped(120).foreach(line => sb.append("        ").append(line).append('\n'))
            sb.append("    \"\"\")\n")
        }
        sb.append("\nend Embedded\n")
        Seq(VendoredFiles.writeIfChanged(pkgDir / "Embedded.scala", sb.toString))
    }

    // Hex rather than one Byte literal per element: about 100KB of fixtures as Byte literals is 100k tokens for the compiler.
    private val decoder: String =
        """    private def decode(hex: String): Array[Byte] =
          |        var digits = 0
          |        var i      = 0
          |        while i < hex.length do
          |            if !hex.charAt(i).isWhitespace then digits += 1
          |            i += 1
          |        val out = new Array[Byte](digits / 2)
          |        var o   = 0
          |        var hi  = -1
          |        i = 0
          |        while i < hex.length do
          |            val c = hex.charAt(i)
          |            if !c.isWhitespace then
          |                val v = if c <= '9' then c - '0' else c - 'a' + 10
          |                if hi < 0 then hi = v
          |                else
          |                    out(o) = ((hi << 4) | v).toByte
          |                    o += 1
          |                    hi = -1
          |                end if
          |            end if
          |            i += 1
          |        end while
          |        out
          |    end decode
          |""".stripMargin
}
