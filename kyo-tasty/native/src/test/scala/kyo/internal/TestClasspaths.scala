package kyo.internal

import kyo.*
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext

/** Native-side cross-platform fixture helper.
  *
  * Mirrors the surface of the JVM `TestClasspaths` object so shared test code can call `TestClasspaths.withClasspath` on all three
  * platforms. On Native there is no JVM classpath discovery; the helper constructs a `MemoryFileSource` pre-loaded from
  * `kyo.fixtures.Embedded` and calls `ClasspathOrchestrator.init` directly, bypassing `NativeFileSource` entirely.
  *
  * Design notes:
  *   - `withClasspath` takes no `roots` parameter (no JVM classpath on Native).
  *   - Concurrency is fixed at 1 (Native single-threaded model; higher values have no effect and would be misleading).
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

    /** Run `f` in a fresh classpath scope built from the embedded TASTy fixtures.
      *
      * Builds the classpath from an in-memory MemoryFileSource, installs the Binding in `Tasty.bindingLocal`,
      * and runs `f` in that scope. Call inside a `run { ... }` test body. The `roots` parameter is ignored on
      * Native (no filesystem); embedded fixtures are always used.
      */
    def withClasspath[A, S](roots: Seq[String] = Seq.empty)(f: => A < S)(using Frame): A < (Async & Abort[TastyError] & S) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        // Companion .class alongside .tasty so cp.findClass populates javaMetadata cross-platform (F-G-002).
        src.add("root/PlainClass.class", kyo.fixtures.Embedded.plainClassClassfile)
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
        src.add("root/AnnotatedFixture$package.tasty", kyo.fixtures.Embedded.annotatedFixturePackageTasty)
        src.add("root/AnnotatedFixtureDeprecated.tasty", kyo.fixtures.Embedded.annotatedFixtureDeprecatedTasty)
        src.add("root/AnnotatedFixtureMethods.tasty", kyo.fixtures.Embedded.annotatedFixtureMethodsTasty)
        src.add("root/Animal.tasty", kyo.fixtures.Embedded.animalTasty)
        src.add("root/Dog.tasty", kyo.fixtures.Embedded.dogTasty)
        src.add("root/Cat.tasty", kyo.fixtures.Embedded.catTasty)
        src.add("root/Vehicle.tasty", kyo.fixtures.Embedded.vehicleTasty)
        src.add("root/Car.tasty", kyo.fixtures.Embedded.carTasty)
        src.add("root/Bike.tasty", kyo.fixtures.Embedded.bikeTasty)
        src.add("root/NonSealedMarker.tasty", kyo.fixtures.Embedded.nonSealedMarkerTasty)
        src.add("root/OpaqueFixture$package.tasty", kyo.fixtures.Embedded.opaqueFixturePackageTasty)
        src.add("root/SealedBase.tasty", kyo.fixtures.Embedded.sealedBaseTasty)
        src.add("root/ConcreteA.tasty", kyo.fixtures.Embedded.concreteATasty)
        src.add("root/ConcreteB.tasty", kyo.fixtures.Embedded.concreteBTasty)
        src.add("root/ContextFunctionFixture$package.tasty", kyo.fixtures.Embedded.contextFunctionFixturePackageTasty)
        src.add("root/ContextFunctionFixture.tasty", kyo.fixtures.Embedded.contextFunctionFixtureTasty)
        src.add("root/Logger.tasty", kyo.fixtures.Embedded.loggerFixtureTasty)
        src.add("root/Config.tasty", kyo.fixtures.Embedded.configFixtureTasty)
        src.add("root/TreeVariantFixture$package.tasty", kyo.fixtures.Embedded.treeVariantFixturePackageTasty)
        src.add("root/HasTypeDef.tasty", kyo.fixtures.Embedded.hasTypeDefTasty)
        src.add("root/SelfDefFixture.tasty", kyo.fixtures.Embedded.selfDefFixtureTasty)
        src.add("root/SuperFixtureBase.tasty", kyo.fixtures.Embedded.superFixtureBaseTasty)
        src.add("root/SuperFixture.tasty", kyo.fixtures.Embedded.superFixtureTasty)
        src.add("root/SuperTypeFixtureBase.tasty", kyo.fixtures.Embedded.superTypeFixtureBaseTasty)
        src.add("root/SuperTypeFixture.tasty", kyo.fixtures.Embedded.superTypeFixtureTasty)
        src.add("root/RecFixture.tasty", kyo.fixtures.Embedded.recFixtureTasty)
        src.add("root/UseIdentTpt.tasty", kyo.fixtures.Embedded.useIdentTptTasty)
        src.add("root/TypeRefDirectFixture.tasty", kyo.fixtures.Embedded.typeRefDirectFixtureTasty)
        src.add("root/TypeRefSymbolFixture.tasty", kyo.fixtures.Embedded.typeRefSymbolFixtureTasty)
        src.add("root/OuterForSelectOuter.tasty", kyo.fixtures.Embedded.outerForSelectOuterTasty)
        // PortedBug* fixtures (PortedBugFixture.scala in kyo-tasty-fixtures); embedded for JS/Native parity.
        src.add("root/PortedBug108.tasty", kyo.fixtures.Embedded.portedBug108Tasty)
        src.add("root/PortedBug11075A.tasty", kyo.fixtures.Embedded.portedBug11075ATasty)
        src.add("root/PortedBug11075B.tasty", kyo.fixtures.Embedded.portedBug11075BTasty)
        src.add("root/PortedBug116IArraySig.tasty", kyo.fixtures.Embedded.portedBug116IArraySigTasty)
        src.add("root/PortedBug125.tasty", kyo.fixtures.Embedded.portedBug125Tasty)
        src.add("root/PortedBug12704CaseClass.tasty", kyo.fixtures.Embedded.portedBug12704CaseClassTasty)
        src.add("root/PortedBug134.tasty", kyo.fixtures.Embedded.portedBug134Tasty)
        src.add("root/PortedBug16843.tasty", kyo.fixtures.Embedded.portedBug16843Tasty)
        src.add("root/PortedBug172Outer.tasty", kyo.fixtures.Embedded.portedBug172OuterTasty)
        src.add("root/PortedBug178.tasty", kyo.fixtures.Embedded.portedBug178Tasty)
        src.add("root/PortedBug187OverloadedApply.tasty", kyo.fixtures.Embedded.portedBug187OverloadedApplyTasty)
        src.add("root/PortedBug192.tasty", kyo.fixtures.Embedded.portedBug192Tasty)
        src.add("root/PortedBug193Holder.tasty", kyo.fixtures.Embedded.portedBug193HolderTasty)
        src.add("root/PortedBug193Outer.tasty", kyo.fixtures.Embedded.portedBug193OuterTasty)
        src.add("root/PortedBug193SuperClass.tasty", kyo.fixtures.Embedded.portedBug193SuperClassTasty)
        src.add("root/PortedBug195.tasty", kyo.fixtures.Embedded.portedBug195Tasty)
        src.add("root/PortedBug213.tasty", kyo.fixtures.Embedded.portedBug213Tasty)
        src.add("root/PortedBug224A.tasty", kyo.fixtures.Embedded.portedBug224ATasty)
        src.add("root/PortedBug224B.tasty", kyo.fixtures.Embedded.portedBug224BTasty)
        src.add("root/PortedBug224C.tasty", kyo.fixtures.Embedded.portedBug224CTasty)
        src.add("root/PortedBug25801.tasty", kyo.fixtures.Embedded.portedBug25801Tasty)
        src.add(
            "root/PortedBug263ClassAndPackageObjectSameName.tasty",
            kyo.fixtures.Embedded.portedBug263ClassAndPackageObjectSameNameTasty
        )
        src.add("root/PortedBug284.tasty", kyo.fixtures.Embedded.portedBug284Tasty)
        src.add("root/PortedBug357.tasty", kyo.fixtures.Embedded.portedBug357Tasty)
        src.add("root/PortedBug380Foo.tasty", kyo.fixtures.Embedded.portedBug380FooTasty)
        src.add("root/PortedBug401.tasty", kyo.fixtures.Embedded.portedBug401Tasty)
        src.add("root/PortedBug403Container.tasty", kyo.fixtures.Embedded.portedBug403ContainerTasty)
        src.add("root/PortedBug405ParamValueClass.tasty", kyo.fixtures.Embedded.portedBug405ParamValueClassTasty)
        src.add("root/PortedBug414.tasty", kyo.fixtures.Embedded.portedBug414Tasty)
        src.add("root/PortedBug415F.tasty", kyo.fixtures.Embedded.portedBug415FTasty)
        src.add("root/PortedBug415Holder.tasty", kyo.fixtures.Embedded.portedBug415HolderTasty)
        src.add("root/PortedBug424.tasty", kyo.fixtures.Embedded.portedBug424Tasty)
        src.add("root/PortedBug428ValueClass.tasty", kyo.fixtures.Embedded.portedBug428ValueClassTasty)
        src.add("root/PortedBug464.tasty", kyo.fixtures.Embedded.portedBug464Tasty)
        src.add("root/PortedBug7.tasty", kyo.fixtures.Embedded.portedBug7Tasty)
        src.add("root/PortedBug7022C.tasty", kyo.fixtures.Embedded.portedBug7022CTasty)
        src.add("root/PortedBug7022P.tasty", kyo.fixtures.Embedded.portedBug7022PTasty)
        src.add("root/PortedBug74Object.tasty", kyo.fixtures.Embedded.portedBug74ObjectTasty)
        src.add("root/PortedBug80UsesRawAware.tasty", kyo.fixtures.Embedded.portedBug80UsesRawAwareTasty)
        src.add("root/PortedBugFixture$package.tasty", kyo.fixtures.Embedded.portedBugFixturePackageTasty)
        src.add("root/portedBug71Outer/portedBug71Inner/Marker.tasty", kyo.fixtures.Embedded.portedBug71InnerMarkerTasty)
        Scope.run:
            ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                val binding = Binding(cp, Maybe.Present(DecodeContext.fresh()))
                Tasty.bindingLocal.let(Maybe.Present(binding))(f)
    end withClasspath

end TestClasspaths
