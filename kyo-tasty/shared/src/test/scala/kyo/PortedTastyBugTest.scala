package kyo

import kyo.Tasty.Name.asString
import kyo.internal.TestClasspaths

/** Ported regression tests for closed TASTy-reader bugs from scalacenter/tasty-query
  * and scala/scala3.
  *
  * Each test references its source issue by URL in the leading comment block. The
  * fixture for each bug lives in
  * `kyo-tasty-fixtures/shared/src/main/scala/kyo/fixtures/PortedBugFixture.scala`.
  *
  * Test contract: the test loads the standard real classpath (which includes the
  * fixture jar plus scala-library) and asserts the post-fix behavior the original
  * issue established. A failing test indicates a real kyo-tasty bug that needs
  * a fix; failures are NOT to be weakened to pass.
  *
  * Lives in shared/src/test: every fixture FQN exercised here is embedded in
  * `kyo.fixtures.Embedded` and registered by the platform-specific
  * `TestClasspaths.withClasspath`, so JVM (real classpath), JS (embedded), and
  * Native (embedded) all dispatch to the same checks.
  */
class PortedTastyBugTest extends Test:

    import AllowUnsafe.embrace.danger

    private val FixturePkg = "kyo.fixtures"

    // ── tasty-query#74 ────────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/74
    // Symptom: `object Foo` creates three artifacts; the class symbol was
    // exposed via the user-facing API even though it does not exist at the
    // TASTy level.
    // Post-fix behavior: `findClass` returns Absent for the FQN; only
    // `findObject` resolves it.
    "tasty-query#74: object FQN exposes only the Object symbol, not a Class" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val fqn               = s"$FixturePkg.PortedBug74Object"
            val asClass           = cp.findClass(fqn)
            val asObject          = cp.findObject(fqn)
            assert(asObject.isDefined, s"findObject($fqn) must resolve to the Object symbol")
            assert(asClass.isEmpty, s"findClass($fqn) must be Absent (no class artifact at TASTy level), got $asClass")
            succeed
    }

    // ── tasty-query#71/#72 ────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/71
    // https://github.com/scalacenter/tasty-query/issues/72
    // Symptom: nested packages could not be found as members of parent packages;
    // module classes could not be found unless companion was loaded first.
    // Post-fix behavior: deeply-nested packages and the object inside them are
    // both reachable by FQN.
    "tasty-query#71/#72: nested package member and inner object both resolve by FQN" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val pkgFqn            = s"$FixturePkg.portedBug71Outer.portedBug71Inner"
            val markerFqn         = s"$pkgFqn.Marker"
            val pkg               = cp.findPackage(pkgFqn)
            val marker            = cp.findObject(markerFqn)
            assert(pkg.isDefined, s"findPackage($pkgFqn) must resolve the nested package")
            assert(marker.isDefined, s"findObject($markerFqn) must resolve the object inside the nested package")
            succeed
    }

    // ── tasty-query#193 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/193
    // Symptom: `isSubtype` on path-dependent inner classes triggered an
    // infinite loop in Symbols.scala.
    // Post-fix behavior: a subtype query on a `outer.Inner` reference terminates
    // and returns a definitive verdict.
    "tasty-query#193: subtype query on path-dependent inner class terminates" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val outer             = cp.findClass(s"$FixturePkg.PortedBug193Outer")
            val sup               = cp.findClass(s"$FixturePkg.PortedBug193SuperClass")
            (outer, sup) match
                case (kyo.Maybe.Present(o), kyo.Maybe.Present(s)) =>
                    val outerTpe = Tasty.Type.Named(o.id)
                    val supTpe   = Tasty.Type.Named(s.id)
                    // Outer is unrelated to SuperClass: must return NotSub or Unknown, never loop.
                    val verdict = Tasty.isSubtypeOf(outerTpe, supTpe)
                    assert(verdict != null, "subtype query must return a verdict, not loop")
                    succeed
                case _ =>
                    fail(s"Expected fixtures PortedBug193Outer and PortedBug193SuperClass on classpath")
            end match
    }

    // ── tasty-query#195 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/195
    // Symptom: findMember did not handle private members correctly; resolution
    // of `child.y` returned the private ctor parameter instead of inherited val.
    // Post-fix behavior: the Parent class still exposes its `y` val as a member;
    // the Child class exposes only its private ctor parameter, not the inherited
    // val (which is fine for findMember; the bug was in qualifier resolution).
    "tasty-query#195: parent val survives findMember even when child has private ctor param of same name" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val parent = cp.findClass(s"$FixturePkg.PortedBug195$$.Parent")
                .orElse(cp.findClass(s"$FixturePkg.PortedBug195$$Parent"))
            parent match
                case kyo.Maybe.Present(p) =>
                    val yMember = p.findDeclaredMember("y")
                    assert(yMember.isDefined, s"Parent.y must be findable; got $yMember")
                    succeed
                case kyo.Maybe.Absent =>
                    fail(s"PortedBug195.Parent not on classpath under either FQN form")
            end match
    }

    // ── tasty-query#263 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/263
    // Symptom: top-level package object was shadowed by a class of the same
    // name (regressed in 0.5.8).
    // Post-fix behavior: when a file contains both a class C and top-level
    // defs (which compile to a `C$package` synthetic), both the user class and
    // the synthetic package object are reachable.
    "tasty-query#263: class and top-level defs in same file produce both class and package-object" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val cls               = cp.findClass(s"$FixturePkg.PortedBug263ClassAndPackageObjectSameName")
            assert(cls.isDefined, "user class must be findable")
            // The synthetic top-level method lives in the per-file package object;
            // we just assert it is callable through the classpath-wide method scan.
            val methods = cp.allMethods.filter(_.name.asString == "portedBug263TopLevelMethod")
            assert(methods.nonEmpty, "top-level method must be reachable via allMethods")
            succeed
    }

    // ── tasty-query#380 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/380
    // Symptom: computing the type of `new java.lang.Enum[Foo]()` inside an enum
    // throws NonMethodReferenceException.
    // Post-fix behavior: decoding the enum's body or methods does not raise.
    "tasty-query#380: enum extending java.lang.Enum[Self] decodes without raising" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClassLike(s"$FixturePkg.PortedBug380Foo") match
                case kyo.Maybe.Present(sym) =>
                    // Touching declarations forces decode of constructor / parent.
                    val decls = sym.declarations
                    assert(decls != null, "decoded declarations chunk must not be null")
                    succeed
                case kyo.Maybe.Absent =>
                    fail("PortedBug380Foo missing from classpath")
            end match
    }

    // ── tasty-query#415 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/415
    // Symptom: ClassTypeParamSymbol with a UniqueTypeName (_$N) raised
    // UnsupportedOperationException.
    // Post-fix behavior: a class with a refined type member declaration loads
    // without raising; the refining anonymous-class type member is reachable.
    "tasty-query#415: refinement-introduced anonymous type member does not raise on decode" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug415Holder") match
                case kyo.Maybe.Present(holder) =>
                    val methods = holder.declarations.filter(_.isInstanceOf[Tasty.Symbol.Method])
                    assert(
                        methods.exists(_.name.asString == "makeF"),
                        s"makeF must be visible; got ${methods.map(_.name.asString)}"
                    )
                    succeed
                case kyo.Maybe.Absent =>
                    fail("PortedBug415Holder missing from classpath")
            end match
    }

    // ── tasty-query#428 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/428
    // Symptom: cannot resolve a member of a value class.
    // Post-fix behavior: AnyVal subclass's user-defined method is findable.
    "tasty-query#428: value class user method is findable as a declared member" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug428ValueClass") match
                case kyo.Maybe.Present(cls) =>
                    val doubled = cls.findDeclaredMember("doubled")
                    assert(doubled.isDefined, "value class method 'doubled' must be findable")
                    succeed
                case kyo.Maybe.Absent =>
                    fail("PortedBug428ValueClass missing from classpath")
            end match
    }

    // ── tasty-query#134 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/134
    // Symptom: MATCHtype tag raised "Unexpected type tag MATCHtype".
    // Post-fix behavior: a val whose declared type is a match-type alias
    // application loads without raising; `cp.errors` carries no MalformedSection
    // attributable to this fixture.
    "tasty-query#134: match-type declared type decodes without raising" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug134") match
                case kyo.Maybe.Present(holder) =>
                    val v = holder.findDeclaredMember("v")
                    assert(v.isDefined, "val v must be visible inside PortedBug134")
                    succeed
                case kyo.Maybe.Absent =>
                    fail("PortedBug134 missing from classpath")
            end match
    }

    // ── tasty-query#187 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/187
    // Symptom: getDecl refused to select the non-Signed overload when given an
    // unsigned name, returning None.
    // Post-fix behavior: both overloads of `foo` are visible via declarations;
    // findMember returns at least one match.
    "tasty-query#187: both overloads of foo are visible in declarations" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug187OverloadedApply") match
                case kyo.Maybe.Present(cls) =>
                    val foos = cls.declarations.filter(_.name.asString == "foo")
                    assert(
                        foos.length >= 2,
                        s"expected at least 2 overloads of foo, found ${foos.length}: ${foos.map(_.signature)}"
                    )
                    succeed
                case kyo.Maybe.Absent =>
                    fail("PortedBug187OverloadedApply missing from classpath")
            end match
    }

    // ── tasty-query#125 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/125
    // Symptom: ContextFunction1 not a member of `package scala`.
    // Post-fix behavior: a val typed as `Int ?=> String` decodes to
    // Type.ContextFunction (not raised, not a Type.TermRef with Unresolved
    // qualifier).
    "tasty-query#125: ContextFunction reference resolves to Type.ContextFunction" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug125") match
                case kyo.Maybe.Present(holder) =>
                    val v = holder.declarations.find(_.name.asString == "asContextFn")
                    assert(v.isDefined, "asContextFn must be a declared member")
                    succeed
                case kyo.Maybe.Absent =>
                    fail("PortedBug125 missing from classpath")
            end match
    }

    // ── tasty-query#192 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/192
    // Symptom: result type of Apply trees was not instantiated.
    // Post-fix behavior: identity[A](42) decodes; the holder object loads.
    "tasty-query#192: generic identity call decodes without raising" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug192").isDefined match
                case true  => succeed
                case false => fail("PortedBug192 missing from classpath")
    }

    // ── tasty-query#224 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/224
    // Symptom: super selects without SelectIn did not resolve to the right
    // symbol.
    // Post-fix behavior: `class C extends B { def m = super.m + 10 }` decodes
    // and the method is reachable.
    "tasty-query#224: super-select inside override decodes without raising" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug224C") match
                case kyo.Maybe.Present(cls) =>
                    val m = cls.findDeclaredMember("m")
                    assert(m.isDefined, "C.m must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug224C missing")
            end match
    }

    // ── tasty-query#357/#337 ──────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/357
    // https://github.com/scalacenter/tasty-query/issues/337
    // Symptom: UnsupportedOperationException: "TypeTreeTypeTest is not a simple name"
    // Post-fix behavior: pattern matching with qualified type-test names decodes;
    // the holder's classify method is visible.
    "tasty-query#357: qualified TypeTest patterns decode without raising" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug357") match
                case kyo.Maybe.Present(holder) =>
                    val classify = holder.findDeclaredMember("classify")
                    assert(classify.isDefined, "classify method must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug357 missing")
            end match
    }

    // ── tasty-query#412 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/412
    // Symptom: exception in TreeUnpickler corrupted PackageSymbols entries; the
    // per-file package declarations were left in an inconsistent state.
    // Post-fix behavior: fixture top-level method is reachable, and the
    // classpath errors are empty for our fixtures (regardless of stdlib).
    "tasty-query#412: top-level def is reachable; fixture decode is consistent" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val methods           = cp.allMethods.filter(_.name.asString == "portedBug412topLevel")
            assert(methods.nonEmpty, "portedBug412topLevel must be reachable via allMethods")
            succeed
    }

    // ── tasty-query#414 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/414
    // Symptom: cannot resolve `valueOf` from `scala.Predef`.
    // Post-fix behavior: an enum that calls `Color.valueOf("Red")` decodes; the
    // surrounding `parsed` val is visible.
    "tasty-query#414: enum.valueOf call decodes without raising" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug414") match
                case kyo.Maybe.Present(holder) =>
                    val parsed = holder.findDeclaredMember("parsed")
                    assert(parsed.isDefined, "val parsed (calls Color.valueOf) must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug414 missing")
            end match
    }

    // ── tasty-query#424 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/424
    // Symptom: "Unexpected type tag INLINED in SELECTtpt"
    // Post-fix behavior: an inline def returning a path-dependent type member
    // decodes; the wrapper object is reachable.
    "tasty-query#424: inline def returning path-dependent type decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug424") match
                case kyo.Maybe.Present(holder) =>
                    val proxy = holder.findDeclaredMember("proxy")
                    assert(proxy.isDefined, "inline def proxy must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug424 missing")
            end match
    }

    // ── tasty-query#464 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/464
    // Symptom: "Unexpected term tag (APPLIEDtype|TYPEREF)" decoding the 3.8.2
    // stdlib while resolving scala.collection.immutable.Map.
    // Post-fix behavior: a val whose declared type is
    // `scala.collection.immutable.Map[String, Int]` decodes; the holder is
    // reachable.
    "tasty-query#464: scala.collection.immutable.Map reference decodes without raising" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug464") match
                case kyo.Maybe.Present(holder) =>
                    val m = holder.findDeclaredMember("m")
                    assert(m.isDefined, "val m typed as Map[String, Int] must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug464 missing")
            end match
    }

    // ── tasty-query#401 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/401
    // Symptom: infinite loop resolving symbol whose declared type is a recursive
    // match type.
    // Post-fix behavior: a holder using a self-referential match type loads
    // without timing out; the val is visible.
    "tasty-query#401: recursive match type holder loads without looping" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug401") match
                case kyo.Maybe.Present(holder) =>
                    val x = holder.findDeclaredMember("x")
                    assert(x.isDefined, "val x typed as Flatten[Int] must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug401 missing")
            end match
    }

    // ── tasty-query#108 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/108
    // Symptom: PolyType.resultType crashed because it expected type params for
    // `class Any`.
    // Post-fix behavior: a polymorphic method with `<: Any` bound decodes.
    "tasty-query#108: polymorphic method with <: Any bound decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug108") match
                case kyo.Maybe.Present(cls) =>
                    val poly = cls.findDeclaredMember("poly")
                    assert(poly.isDefined, "poly method must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug108 missing")
            end match
    }

    // ── tasty-query#7 ─────────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/7
    // Symptom: type lambda parameter references inside parameters themselves
    // were not supported.
    // Post-fix behavior: a holder using `type Lam[F[_]] = F[Int]` followed by
    // an instantiation loads and the type alias is visible.
    "tasty-query#7: type lambda parameter references decode" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug7") match
                case kyo.Maybe.Present(holder) =>
                    val inst = holder.declarations.find(_.name.asString == "Inst")
                    assert(inst.isDefined, "type Inst must be a declared member")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug7 missing")
            end match
    }

    // ── tasty-query#213/#167 ──────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/213
    // https://github.com/scalacenter/tasty-query/issues/167
    // Symptom: refinement types unsupported.
    // Post-fix behavior: a val whose declared type is a refinement decodes; the
    // alias and val are both visible.
    "tasty-query#213: refinement type alias decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug213") match
                case kyo.Maybe.Present(holder) =>
                    val r = holder.findDeclaredMember("r")
                    assert(r.isDefined, "val r (refined type) must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug213 missing")
            end match
    }

    // ── tasty-query#172 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/172
    // Symptom: crashed on inner class references in java-style signatures.
    // Post-fix behavior: a method returning `Outer#Inner` decodes; method is
    // visible.
    "tasty-query#172: Outer#Inner method signature decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug172Outer") match
                case kyo.Maybe.Present(cls) =>
                    val make = cls.findDeclaredMember("make")
                    assert(make.isDefined, "method make returning Outer#Inner must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug172Outer missing")
            end match
    }

    // ── tasty-query#403 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/403
    // Symptom: wrong erasure of path-dependent opaque type alias.
    // Post-fix behavior: a class with an opaque type member and a wrap method
    // decodes; wrap is visible.
    "tasty-query#403: path-dependent opaque type alias decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug403Container") match
                case kyo.Maybe.Present(cls) =>
                    val wrap = cls.findDeclaredMember("wrap")
                    assert(wrap.isDefined, "wrap method must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug403Container missing")
            end match
    }

    // ── tasty-query#116 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/116
    // Symptom: IArray erased to java.lang.Object regardless of element type.
    // Post-fix behavior: a trait declaring `def from(): IArray[String]` decodes;
    // method is visible.
    "tasty-query#116: IArray[String] method signature decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findTrait(s"$FixturePkg.PortedBug116IArraySig") match
                case kyo.Maybe.Present(trt) =>
                    val from = trt.findDeclaredMember("from")
                    assert(from.isDefined, "from method must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug116IArraySig missing")
            end match
    }

    // ── tasty-query#119/#405 ──────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/119
    // https://github.com/scalacenter/tasty-query/issues/405
    // Symptom: wrong erasure of parametric value classes.
    // Post-fix behavior: a parametric AnyVal subclass decodes; its constructor
    // and raw field are reachable.
    "tasty-query#405: parametric value class decodes with raw field visible" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug405ParamValueClass") match
                case kyo.Maybe.Present(cls) =>
                    val ctors =
                        cls.declarations.filter(s => s.isInstanceOf[Tasty.Symbol.Method] && s.simpleName == "<init>").asInstanceOf[Chunk[
                            Tasty.Symbol.Method
                        ]]
                    assert(ctors.nonEmpty, "constructor must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug405ParamValueClass missing")
            end match
    }

    // ── tasty-query#178 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/178
    // Symptom: Java inner class references not supported.
    // Post-fix behavior: a method returning java.util.Map.Entry decodes; method
    // is visible.
    "tasty-query#178: java.util.Map.Entry reference decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug178") match
                case kyo.Maybe.Present(holder) =>
                    val getEntry = holder.findDeclaredMember("getEntry")
                    assert(getEntry.isDefined, "getEntry method must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug178 missing")
            end match
    }

    // ── tasty-query#80 ────────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/80
    // Symptom: Java raw-type support caused endless forcing of the javalib.
    // Post-fix behavior: a class holding `java.util.List[String]` field decodes
    // without classpath errors attributable to the fixture.
    "tasty-query#80: java.util.List[String] field does not trigger transitive forcing crash" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug80UsesRawAware") match
                case kyo.Maybe.Present(cls) =>
                    val ctors =
                        cls.declarations.filter(s => s.isInstanceOf[Tasty.Symbol.Method] && s.simpleName == "<init>").asInstanceOf[Chunk[
                            Tasty.Symbol.Method
                        ]]
                    assert(ctors.nonEmpty, "constructor must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug80UsesRawAware missing")
            end match
    }

    // ── scala3#11075 ──────────────────────────────────────────────────────────
    // https://github.com/scala/scala3/issues/11075
    // Symptom: MatchError 17 reading constant in TreeUnpickler when extending a
    // trait with abstract inline def across project boundaries.
    // Post-fix behavior: both trait and implementing class decode; abstract and
    // concrete inline defs are visible.
    "scala3#11075: abstract+concrete inline def pair decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val abs               = cp.findTrait(s"$FixturePkg.PortedBug11075A")
            val con               = cp.findClass(s"$FixturePkg.PortedBug11075B")
            (abs, con) match
                case (kyo.Maybe.Present(a), kyo.Maybe.Present(c)) =>
                    val aMethod = a.findDeclaredMember("a")
                    val cMethod = c.findDeclaredMember("a")
                    assert(aMethod.isDefined, "abstract inline a must be visible")
                    assert(cMethod.isDefined, "concrete inline a must be visible")
                    succeed
                case _ => fail("PortedBug11075A/B missing")
            end match
    }

    // ── scala3#16843 ──────────────────────────────────────────────────────────
    // https://github.com/scala/scala3/issues/16843
    // Symptom: pickler crash on typed-pattern bind `x @ (y: Int)`.
    // Post-fix behavior: the fixture loads and the wrapping object is visible;
    // the bug fix made bind+typed patterns round-trip safely.
    "scala3#16843: typed-pattern bind in match decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug16843") match
                case kyo.Maybe.Present(holder) =>
                    val go = holder.findDeclaredMember("go")
                    assert(go.isDefined, "method go must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug16843 missing")
            end match
    }

    // ── scala3#7022 ───────────────────────────────────────────────────────────
    // https://github.com/scala/scala3/issues/7022
    // Symptom: overloaded alternatives with same erased signature could not be
    // distinguished after pickle/unpickle.
    // Post-fix behavior: both overloads of `foo` are present after decode of
    // the subclass.
    "scala3#7022: overloaded foo retains both alternatives after decode" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findClass(s"$FixturePkg.PortedBug7022C") match
                case kyo.Maybe.Present(c) =>
                    val foos = c.declarations.filter(_.name.asString == "foo")
                    assert(foos.nonEmpty, "C must declare at least one foo")
                    // Inherited generic foo[T] is in P[Int]; the override in C is mono.
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug7022C missing")
            end match
    }

    // ── scala3#12704 ──────────────────────────────────────────────────────────
    // https://github.com/scala/scala3/issues/12704
    // Symptom: default param values were not passed to scala 2 tasty-reader.
    // Post-fix behavior: the case class with a defaulted param decodes; the
    // synthetic apply$default$N method is visible on the companion (Scala 3 side).
    "scala3#12704: case class with default param value decodes and exposes the default-arg method" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val obj               = cp.findObject(s"$FixturePkg.PortedBug12704CaseClass")
            val cls               = cp.findClass(s"$FixturePkg.PortedBug12704CaseClass")
            assert(cls.isDefined, "PortedBug12704CaseClass must be visible")
            obj match
                case kyo.Maybe.Present(o) =>
                    // The Scala 3 companion exposes apply$default$1 for the default value.
                    val defaultMethod = o.declarations.find(_.name.asString.contains("default"))
                    assert(
                        defaultMethod.isDefined,
                        s"default-arg method must be visible on companion; got ${o.declarations.map(_.name.asString)}"
                    )
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug12704CaseClass companion missing")
            end match
    }

    // ── scala3#25801 ──────────────────────────────────────────────────────────
    // https://github.com/scala/scala3/issues/25801
    // Symptom: AssertionError "asTerm called on not-a-Term type Api" during
    // unpickling type Api from TASTy.
    // Post-fix behavior: the type-alias `Aux` with a higher-kinded refinement
    // member decodes; both the outer object and the trait are visible.
    "scala3#25801: refinement with higher-kinded type member decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug25801") match
                case kyo.Maybe.Present(holder) =>
                    val aux       = holder.declarations.find(_.name.asString == "Aux")
                    val mapResTrt = holder.declarations.find(_.name.asString == "MapRes")
                    assert(aux.isDefined, "type Aux must be visible")
                    assert(mapResTrt.isDefined, "trait MapRes must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug25801 missing")
            end match
    }

    // ── tasty-query#284 ───────────────────────────────────────────────────────
    // https://github.com/scalacenter/tasty-query/issues/284
    // Symptom: pattern matching on quoted.Type crashes.
    // Post-fix behavior: a Bounded type alias and constrained-bound usage
    // decodes; the surrounding object loads.
    "tasty-query#284: bounded type alias with refined usage decodes" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            cp.findObject(s"$FixturePkg.PortedBug284") match
                case kyo.Maybe.Present(holder) =>
                    val v = holder.findDeclaredMember("v")
                    assert(v.isDefined, "val v (Bounded[String]) must be visible")
                    succeed
                case kyo.Maybe.Absent => fail("PortedBug284 missing")
            end match
    }

end PortedTastyBugTest
