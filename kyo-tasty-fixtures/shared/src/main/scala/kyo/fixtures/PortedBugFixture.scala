package kyo.fixtures

/** Fixture classes for ported TASTy-reader regression tests.
  *
  * Each top-level definition here mirrors the minimal reproducer from a closed
  * tasty-query or scala/scala3 issue. The corresponding test in
  * `PortedTastyBugTest.scala` queries the compiled TASTy and asserts the
  * post-fix behavior. See that file for issue URLs.
  *
  * The fixtures intentionally use minimal Scala 3 syntax so that they compile
  * cleanly under the project's toolchain; tests assert behavior, not source
  * syntax.
  */

// ── tasty-query#74: object Foo produces three artifacts ───────────────────
// `object Foo` creates Foo$.class, Foo.class, Foo.tasty. Test asserts that
// findClass("...PortedBug74Object") returns Absent and only findObject succeeds.
object PortedBug74Object

// ── tasty-query#193: isSubtype on path-dependent nested classes ───────────
class PortedBug193SuperClass
class PortedBug193Outer(x: Int):
    class Inner(y: Int) extends PortedBug193SuperClass
end PortedBug193Outer
object PortedBug193Holder:
    val outer = new PortedBug193Outer(5)
    val inner = new outer.Inner(6)
end PortedBug193Holder

// ── tasty-query#195: findMember and private constructor parameters ────────
// child.y must resolve to Parent.y (the public val), not to the private ctor
// parameter of the same name in Child.
object PortedBug195:
    class Parent:
        val y = 2
    class Child(y: Int) extends Parent
    def testSetup(): Int =
        val child = new Child(4)
        child.y
end PortedBug195

// ── tasty-query#263: top-level package object shadowed by same-name class ─
class PortedBug263ClassAndPackageObjectSameName
def portedBug263TopLevelMethod(): Int = 1

// ── tasty-query#380: enum Foo extends java.lang.Enum[Foo] ──────────────────
enum PortedBug380Foo extends java.lang.Enum[PortedBug380Foo]:
    case Bar
end PortedBug380Foo

// ── tasty-query#415: type member with synthesized fresh name (_$N) ────────
// The refinement `{ type Dummy }` on the result of a method that returns an
// anonymous class with a fresh-named type member exercises the
// ClassTypeParamSymbol / UniqueTypeName decode path.
trait PortedBug415F[A]
object PortedBug415Holder:
    def makeF: PortedBug415F[Int] { type Dummy } =
        new PortedBug415F[Int]:
            type Dummy
end PortedBug415Holder

// ── tasty-query#428: member resolution on value class ──────────────────────
class PortedBug428ValueClass(val underlying: Int) extends AnyVal:
    def doubled: Int = underlying * 2
end PortedBug428ValueClass

// ── tasty-query#134: MATCHtype in val declared type ───────────────────────
object PortedBug134:
    type MT[X] = X match
        case Int => String
    def m: MT[Int] = "5"
    val v: MT[Int] = m
end PortedBug134

// ── tasty-query#187: overload resolution without signature ────────────────
class PortedBug187OverloadedApply:
    def foo: String            = "foo"
    def foo[T](x: Int): String = s"$x"
    def callF: String          = foo
end PortedBug187OverloadedApply

// ── tasty-query#125: ContextFunction1 reference in stdlib ─────────────────
// References `scala.ContextFunction1` directly so the decoder must resolve the
// hardcoded scala.* synthetic class lookup.
object PortedBug125:
    val asContextFn: Int ?=> String = summon[Int].toString
    type CFType[A, B] = A ?=> B
    val composed: CFType[Int, String] = asContextFn
end PortedBug125

// ── tasty-query#192: instantiated result type of Apply ────────────────────
object PortedBug192:
    def identity[A](a: A): A = a
    val result: Int          = identity(42)
end PortedBug192

// ── tasty-query#224: super selects without SelectIn ───────────────────────
trait PortedBug224A:
    def m: Int = 1
trait PortedBug224B extends PortedBug224A:
    override def m: Int = 2
class PortedBug224C extends PortedBug224B:
    override def m: Int = super.m + 10
end PortedBug224C

// ── tasty-query#284: pattern matching on quoted Type ──────────────────────
// Just a Refined / SelectTpt exercise; original bug needed staging which is
// out of reach for a fixture.
object PortedBug284:
    type Bounded[A <: AnyRef] = A
    val v: Bounded[String] = "x"
end PortedBug284

// ── tasty-query#357/#337: TypeTreeTypeTest "not a simple name" ─────────────
// Triggered by a Typed pattern (`case x: T =>`) inside an Unapply / Apply
// where the type-test name is qualified.
object PortedBug357:
    def classify(a: Any): String = a match
        case _: scala.collection.immutable.List[?]   => "list"
        case _: scala.collection.immutable.Map[?, ?] => "map"
        case _: String                               => "string"
        case _                                       => "other"
end PortedBug357

// ── tasty-query#412: TreeUnpickler exception corrupts PackageSymbol ───────
// The bug was about exception recovery; we can only encode the trigger shape
// (top-level def with an APPLY in declared type). Test asserts the package
// declarations still load.
def portedBug412topLevel: Int = identity(7)

// ── tasty-query#414: cannot resolve `valueOf` from scala.Predef ───────────
object PortedBug414:
    enum Color:
        case Red, Green
    val parsed: Color = Color.valueOf("Red")
end PortedBug414

// ── tasty-query#424: INLINED in SELECTtpt ──────────────────────────────────
// Bug triggered by inline def with a SELECTtpt whose qualifier is an INLINED
// proxy. Use an inline def referencing a path-dependent member.
object PortedBug424:
    class Outer:
        type T = Int
    inline def proxy(o: Outer): o.T = 0
end PortedBug424

// ── tasty-query#464: APPLIEDtype/TYPEREF in stdlib (Map decode) ───────────
// Reference scala.collection.immutable.Map by FQN so the decoder must follow
// the same path that crashed for 3.8.2 stdlib.
object PortedBug464:
    val m: scala.collection.immutable.Map[String, Int] = Map("a" -> 1)
end PortedBug464

// ── tasty-query#401: recursive match type ─────────────────────────────────
object PortedBug401:
    type Flatten[T] = T match
        case Tuple => T
        case _     => Tuple1[T]
    val x: Flatten[Int] = Tuple1(0)
end PortedBug401

// ── tasty-query#108: PolyType.resultType on Any-typed param ───────────────
class PortedBug108:
    def poly[A <: Any](a: A): A = a
end PortedBug108

// ── tasty-query#80: Java raw types — exercised only via stdlib;
//   we add a Java-style class extension that uses raw List signatures.
class PortedBug80UsesRawAware(val xs: java.util.List[String])

// ── tasty-query#7: type lambda parameter references inside parameters ─────
object PortedBug7:
    type Id[A]     = A
    type Lam[F[_]] = F[Int]
    type Inst      = Lam[Id]
end PortedBug7

// ── tasty-query#213/#167: refinement types ────────────────────────────────
object PortedBug213:
    type Refined = Iterable[Int] { def size: Int }
    val r: Refined = Set(1, 2, 3)
end PortedBug213

// ── tasty-query#172: inner-class reference in Java-style signatures ───────
class PortedBug172Outer:
    class Inner(val data: String)
    def make: PortedBug172Outer#Inner = new Inner("x")
end PortedBug172Outer

// ── tasty-query#403: path-dependent opaque type alias erasure ─────────────
class PortedBug403Container:
    opaque type Wrapped = Int
    def wrap(i: Int): Wrapped = i
end PortedBug403Container

// ── tasty-query#116: IArray erasure ──────────────────────────────────────
trait PortedBug116IArraySig:
    def from(): IArray[String]

// ── tasty-query#119/#405: parametric value class erasure ───────────────────
class PortedBug405ParamValueClass[A](val raw: A) extends AnyVal

// ── tasty-query#178: Java inner class reference (Map.Entry) ──────────────
object PortedBug178:
    def getEntry(m: java.util.Map[String, Int]): java.util.Map.Entry[String, Int] =
        m.entrySet().iterator().next()
end PortedBug178

// ── scala3#11075: abstract inline method across project ───────────────────
// Mimics a trait with an inline def implemented elsewhere; reading the
// implementing class requires reading the constant default value path.
trait PortedBug11075A:
    inline def a: Int
class PortedBug11075B extends PortedBug11075A:
    inline def a: Int = 0
end PortedBug11075B

// ── scala3#16843: typed-pattern bind `x @ (y: Int)` ───────────────────────
object PortedBug16843:
    def go(a: Any): Int = a match
        case x @ (y: Int) => x + y
        case _            => 0
end PortedBug16843

// ── scala3#7022: overloaded alternatives with same signature ──────────────
trait PortedBug7022P[A]:
    def foo[T](x: Int): A = ???
class PortedBug7022C extends PortedBug7022P[Int]:
    def foo(x: Int): Int = x
end PortedBug7022C

// ── scala3#12704: case class with default param value ─────────────────────
case class PortedBug12704CaseClass(value: Int = 1)

// ── scala3#25801: type member named `Api` (refinement type alias) ────────
// Reproduces the basic pattern: refinement on a type alias with a higher-kinded
// type member, exercising the asTerm/asType conversion path.
object PortedBug25801:
    trait MapRes[F[_], R]:
        type K[_[_]]
    type Aux[F[_], R, K0[_[_]]] = MapRes[F, R] { type K[G[_]] = K0[G] }
end PortedBug25801

// ── tasty-query#71/#72: nested packages and module classes ────────────────
// Provide a deeply nested object so module-class / package navigation can be
// exercised. The original bugs were about scala.collection / java.lang; we use
// a small mirror.
package portedBug71Outer:
    package portedBug71Inner:
        object Marker:
            val tag: String = "inner"
        end Marker
    end portedBug71Inner
end portedBug71Outer
