package kyo.fixtures

/** Type ADT fixtures for cross-platform testing of AndType, OrType, and MatchType decoding.
  *
  * Provides type aliases and methods that use Scala 3's intersection types (A & B), union types (A | B), and match types (T match { case ...
  * }). These types appear in the TASTy encoding as ANDtype, ORtype, and MATCHtype tags respectively.
  *
  * Without this fixture the embedded set contains no intersection types, union types, or match types, and TypeAdtFidelity2Test leaves 1, 2,
  * and 4 would fail with count=0 on JS/Native.
  */

// Trait definitions needed for type ADT fixtures.
// Two traits are combined to produce an intersection type.
trait AnotherTrait:
    def another: String
end AnotherTrait

// Intersection type alias: uses & operator -> encodes as ANDtype in TASTy.
// Uses two traits (not classes) to ensure the intersection type is well-formed.
type SomeAndAnotherTrait = SomeTrait & AnotherTrait

// Union type alias: uses | operator -> encodes as ORtype in TASTy.
// Uses two distinct case class types from this file.
type PlainOrSomeCaseClass = PlainClass | SomeCaseClass

// Method returning union type: ensures the OrType appears in a method declaredType.
def toPlainOrCase(b: Boolean): PlainClass | SomeCaseClass =
    if b then PlainClass(1) else SomeCaseClass("x", 0)

// Match type: a type that uses pattern matching on types -> encodes as MATCHtype in TASTy.
// Uses only types from this fixture file.
type InnerOf[C] = C match
    case GenericBox[t] => t
    case _             => C
