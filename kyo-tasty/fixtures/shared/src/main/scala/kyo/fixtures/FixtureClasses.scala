package kyo.fixtures

// Plain class
class PlainClass(val x: Int)

// Trait
trait SomeTrait:
    def compute: Int
end SomeTrait

// Object
object SomeObject:
    val value: Int = 42
end SomeObject

// Case class
case class SomeCaseClass(name: String, count: Int)

// Sealed abstract class
sealed abstract class SealedBase
class ConcreteA extends SealedBase
class ConcreteB extends SealedBase

// Enum
enum Color derives CanEqual:
    case Red, Green, Blue
end Color

// Opaque type alias
opaque type Meters = Double
object Meters:
    def apply(d: Double): Meters            = d
    extension (m: Meters) def value: Double = m
end Meters

// Type alias
type StringList = List[String]

// Abstract type member
trait Container:
    type Item
    def get: Item
end Container

// Method with type params
def identityMethod[A](a: A): A = a

// Inline method
inline def inlineAdd(a: Int, b: Int): Int = a + b

// Given (implicit)
given defaultInt: Int = 0

// Val
val topLevelVal: String = "hello"

// Var
var topLevelVar: Int = 0

// Lazy val
lazy val lazyValue: String = "computed lazily"

// Method with default params
def methodWithDefaults(x: Int = 1, y: Int = 2): Int = x + y

// Generic class
class GenericBox[A](val content: A)

// Generic method with bounds
def bounded[A <: SomeTrait](a: A): Int = a.compute

// Nested class
class Outer:
    class Inner(val z: Double)
    object InnerCompanion
end Outer

// Package-object-style object (mimics package object; TASTy records the Module flag)
object `package`:
    val packageVal: String = "package-level"
end `package`
