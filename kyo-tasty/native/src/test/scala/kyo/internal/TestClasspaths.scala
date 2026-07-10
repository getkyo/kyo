package kyo.internal

import kyo.*

/** Fixture helper for kyo-tasty cross-platform tests on Native.
  *
  * Provides a `TestClasspaths.withClasspath` surface so shared test code can call it on all three platforms. Creates a
  * temporary directory, writes the embedded fixture bytes to it, then delegates to `Tasty.withClasspath` so the same
  * shared test pipeline runs against a real filesystem path.
  *
  * The `roots` parameter is ignored; embedded fixtures are always loaded.
  */
private[kyo] object TestClasspaths:

    /** On Native the `roots` parameter is ignored; embedded fixtures are always loaded. */
    val kyoTastyFixtures: Seq[String] = Seq.empty

    def withClasspath[A, S](roots: Seq[String] = Seq.empty)(f: => A < S)(using Frame): A < (Async & Abort[TastyError] & S) =
        Scope.run {
            Abort.recover[FileException] { e => Abort.fail(TastyError.SnapshotIoError(e.getMessage)) } {
                Path.run(Path.tempDir("kyo-test-fixtures")).map { dir =>
                    val tastyDir     = dir / "root"
                    val javaClassDir = dir / "kyo" / "fixtures"

                    def write(name: String, bytes: Array[Byte]): Unit < PathWrite =
                        (tastyDir / name).writeBytes(Span.from(bytes))

                    val tastyFixtures: Chunk[(String, Array[Byte])] = Chunk(
                        "PlainClass.tasty"                     -> kyo.fixtures.Embedded.plainClassTasty,
                        "PlainClass.class"                     -> kyo.fixtures.Embedded.plainClassClassfile,
                        "SomeObject.tasty"                     -> kyo.fixtures.Embedded.someObjectTasty,
                        "SomeTrait.tasty"                      -> kyo.fixtures.Embedded.someTraitTasty,
                        "GenericBox.tasty"                     -> kyo.fixtures.Embedded.genericBoxTasty,
                        "Outer.tasty"                          -> kyo.fixtures.Embedded.outerTasty,
                        "SomeCaseClass.tasty"                  -> kyo.fixtures.Embedded.someCaseClassTasty,
                        "Color.tasty"                          -> kyo.fixtures.Embedded.colorTasty,
                        "FixtureClasses$package.tasty"         -> kyo.fixtures.Embedded.fixtureClassesPackageTasty,
                        "BaseClass.tasty"                      -> kyo.fixtures.Embedded.baseClassTasty,
                        "ChildClass.tasty"                     -> kyo.fixtures.Embedded.childClassTasty,
                        "Shape.tasty"                          -> kyo.fixtures.Embedded.shapeTasty,
                        "VarargFixture.tasty"                  -> kyo.fixtures.Embedded.varargFixtureTasty,
                        "TypeAdtFixture$package.tasty"         -> kyo.fixtures.Embedded.typeAdtFixtureTasty,
                        "AnnotatedFixture$package.tasty"       -> kyo.fixtures.Embedded.annotatedFixturePackageTasty,
                        "AnnotatedFixtureDeprecated.tasty"     -> kyo.fixtures.Embedded.annotatedFixtureDeprecatedTasty,
                        "AnnotatedFixtureMethods.tasty"        -> kyo.fixtures.Embedded.annotatedFixtureMethodsTasty,
                        "Animal.tasty"                         -> kyo.fixtures.Embedded.animalTasty,
                        "Dog.tasty"                            -> kyo.fixtures.Embedded.dogTasty,
                        "Cat.tasty"                            -> kyo.fixtures.Embedded.catTasty,
                        "Vehicle.tasty"                        -> kyo.fixtures.Embedded.vehicleTasty,
                        "Car.tasty"                            -> kyo.fixtures.Embedded.carTasty,
                        "Bike.tasty"                           -> kyo.fixtures.Embedded.bikeTasty,
                        "NonSealedMarker.tasty"                -> kyo.fixtures.Embedded.nonSealedMarkerTasty,
                        "OpaqueFixture$package.tasty"          -> kyo.fixtures.Embedded.opaqueFixturePackageTasty,
                        "SealedBase.tasty"                     -> kyo.fixtures.Embedded.sealedBaseTasty,
                        "ConcreteA.tasty"                      -> kyo.fixtures.Embedded.concreteATasty,
                        "ConcreteB.tasty"                      -> kyo.fixtures.Embedded.concreteBTasty,
                        "ContextFunctionFixture$package.tasty" -> kyo.fixtures.Embedded.contextFunctionFixturePackageTasty,
                        "ContextFunctionFixture.tasty"         -> kyo.fixtures.Embedded.contextFunctionFixtureTasty,
                        "Logger.tasty"                         -> kyo.fixtures.Embedded.loggerFixtureTasty,
                        "Config.tasty"                         -> kyo.fixtures.Embedded.configFixtureTasty,
                        "TreeVariantFixture$package.tasty"     -> kyo.fixtures.Embedded.treeVariantFixturePackageTasty,
                        "HasTypeDef.tasty"                     -> kyo.fixtures.Embedded.hasTypeDefTasty,
                        "SelfDefFixture.tasty"                 -> kyo.fixtures.Embedded.selfDefFixtureTasty,
                        "SuperFixtureBase.tasty"               -> kyo.fixtures.Embedded.superFixtureBaseTasty,
                        "SuperFixture.tasty"                   -> kyo.fixtures.Embedded.superFixtureTasty,
                        "SuperTypeFixtureBase.tasty"           -> kyo.fixtures.Embedded.superTypeFixtureBaseTasty,
                        "SuperTypeFixture.tasty"               -> kyo.fixtures.Embedded.superTypeFixtureTasty,
                        "RecFixture.tasty"                     -> kyo.fixtures.Embedded.recFixtureTasty,
                        "UseIdentTpt.tasty"                    -> kyo.fixtures.Embedded.useIdentTptTasty,
                        "TypeRefDirectFixture.tasty"           -> kyo.fixtures.Embedded.typeRefDirectFixtureTasty,
                        "TypeRefSymbolFixture.tasty"           -> kyo.fixtures.Embedded.typeRefSymbolFixtureTasty,
                        "OuterForSelectOuter.tasty"            -> kyo.fixtures.Embedded.outerForSelectOuterTasty,
                        "PortedBug108.tasty"                   -> kyo.fixtures.Embedded.portedBug108Tasty,
                        "PortedBug11075A.tasty"                -> kyo.fixtures.Embedded.portedBug11075ATasty,
                        "PortedBug11075B.tasty"                -> kyo.fixtures.Embedded.portedBug11075BTasty,
                        "PortedBug116IArraySig.tasty"          -> kyo.fixtures.Embedded.portedBug116IArraySigTasty,
                        "PortedBug125.tasty"                   -> kyo.fixtures.Embedded.portedBug125Tasty,
                        "PortedBug12704CaseClass.tasty"        -> kyo.fixtures.Embedded.portedBug12704CaseClassTasty,
                        "PortedBug134.tasty"                   -> kyo.fixtures.Embedded.portedBug134Tasty,
                        "PortedBug16843.tasty"                 -> kyo.fixtures.Embedded.portedBug16843Tasty,
                        "PortedBug172Outer.tasty"              -> kyo.fixtures.Embedded.portedBug172OuterTasty,
                        "PortedBug178.tasty"                   -> kyo.fixtures.Embedded.portedBug178Tasty,
                        "PortedBug187OverloadedApply.tasty"    -> kyo.fixtures.Embedded.portedBug187OverloadedApplyTasty,
                        "PortedBug192.tasty"                   -> kyo.fixtures.Embedded.portedBug192Tasty,
                        "PortedBug193Holder.tasty"             -> kyo.fixtures.Embedded.portedBug193HolderTasty,
                        "PortedBug193Outer.tasty"              -> kyo.fixtures.Embedded.portedBug193OuterTasty,
                        "PortedBug193SuperClass.tasty"         -> kyo.fixtures.Embedded.portedBug193SuperClassTasty,
                        "PortedBug195.tasty"                   -> kyo.fixtures.Embedded.portedBug195Tasty,
                        "PortedBug213.tasty"                   -> kyo.fixtures.Embedded.portedBug213Tasty,
                        "PortedBug224A.tasty"                  -> kyo.fixtures.Embedded.portedBug224ATasty,
                        "PortedBug224B.tasty"                  -> kyo.fixtures.Embedded.portedBug224BTasty,
                        "PortedBug224C.tasty"                  -> kyo.fixtures.Embedded.portedBug224CTasty,
                        "PortedBug25801.tasty"                 -> kyo.fixtures.Embedded.portedBug25801Tasty,
                        "PortedBug263ClassAndPackageObjectSameName.tasty" -> kyo.fixtures.Embedded.portedBug263ClassAndPackageObjectSameNameTasty,
                        "PortedBug284.tasty"                -> kyo.fixtures.Embedded.portedBug284Tasty,
                        "PortedBug357.tasty"                -> kyo.fixtures.Embedded.portedBug357Tasty,
                        "PortedBug380Foo.tasty"             -> kyo.fixtures.Embedded.portedBug380FooTasty,
                        "PortedBug401.tasty"                -> kyo.fixtures.Embedded.portedBug401Tasty,
                        "PortedBug403Container.tasty"       -> kyo.fixtures.Embedded.portedBug403ContainerTasty,
                        "PortedBug405ParamValueClass.tasty" -> kyo.fixtures.Embedded.portedBug405ParamValueClassTasty,
                        "PortedBug414.tasty"                -> kyo.fixtures.Embedded.portedBug414Tasty,
                        "PortedBug415F.tasty"               -> kyo.fixtures.Embedded.portedBug415FTasty,
                        "PortedBug415Holder.tasty"          -> kyo.fixtures.Embedded.portedBug415HolderTasty,
                        "PortedBug424.tasty"                -> kyo.fixtures.Embedded.portedBug424Tasty,
                        "PortedBug428ValueClass.tasty"      -> kyo.fixtures.Embedded.portedBug428ValueClassTasty,
                        "PortedBug464.tasty"                -> kyo.fixtures.Embedded.portedBug464Tasty,
                        "PortedBug7.tasty"                  -> kyo.fixtures.Embedded.portedBug7Tasty,
                        "PortedBug7022C.tasty"              -> kyo.fixtures.Embedded.portedBug7022CTasty,
                        "PortedBug7022P.tasty"              -> kyo.fixtures.Embedded.portedBug7022PTasty,
                        "PortedBug74Object.tasty"           -> kyo.fixtures.Embedded.portedBug74ObjectTasty,
                        "PortedBug80UsesRawAware.tasty"     -> kyo.fixtures.Embedded.portedBug80UsesRawAwareTasty,
                        "PortedBugFixture$package.tasty"    -> kyo.fixtures.Embedded.portedBugFixturePackageTasty
                    )

                    Path.run {
                        tastyDir.mkDir.map { _ =>
                            javaClassDir.mkDir.map { _ =>
                                Kyo.foreach(tastyFixtures) { case (name, bytes) =>
                                    write(name, bytes)
                                }.map { _ =>
                                    val bug71Dir = tastyDir / "portedBug71Outer" / "portedBug71Inner"
                                    bug71Dir.mkDir.map { _ =>
                                        (bug71Dir / "Marker.tasty").writeBytes(
                                            Span.from(kyo.fixtures.Embedded.portedBug71InnerMarkerTasty)
                                        ).map { _ =>
                                            // Java fixture: registered as a standalone root so the classpath walker's
                                            // ".class" branch discovers it.
                                            // Path "kyo/fixtures/JavaSimpleFixture.class" yields fully-qualified name
                                            // "kyo.fixtures.JavaSimpleFixture" via classfilePathToFullName.
                                            (javaClassDir / "JavaSimpleFixture.class").writeBytes(
                                                Span.from(kyo.fixtures.EmbeddedJavaFixtures.javaSimpleFixtureClassfile)
                                            ).map { _ =>
                                                // Directory-enumeration barrier on Native: list every just-written
                                                // directory before launching the classpath walker so each directory's
                                                // inode is refreshed and all entries are visible to the subsequent
                                                // Path.walk inside Tasty.withClasspath. Without this barrier, the
                                                // POSIX readdir cache in the Scala Native NIO compat sometimes misses
                                                // the last few writes when the same fiber writes then walks the same
                                                // directory within microseconds.
                                                //
                                                // Two levels, matching the two-level write pattern: writes land inside
                                                // tastyDir (root/) and inside javaClassDir (kyo/fixtures/), both under
                                                // the parent temp dir. Flushing tastyDir, javaClassDir, and dir ensures
                                                // root and kyo/ are both settled in the parent's readdir snapshot before
                                                // the walk begins.
                                                Abort.recover[FileException](_ => ()) {
                                                    Path.runReadOnly(tastyDir.list.map(_ => ()))
                                                }.flatMap { _ =>
                                                    Abort.recover[FileException](_ => ()) {
                                                        Path.runReadOnly(javaClassDir.list.map(_ => ()))
                                                    }
                                                }.flatMap { _ =>
                                                    Abort.recover[FileException](_ => ()) {
                                                        Path.runReadOnly(dir.list.map(_ => ()))
                                                    }
                                                }.map { _ =>
                                                    Tasty.withClasspath(
                                                        Seq(tastyDir.toString, (javaClassDir / "JavaSimpleFixture.class").toString)
                                                    )(f)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end withClasspath

end TestClasspaths
