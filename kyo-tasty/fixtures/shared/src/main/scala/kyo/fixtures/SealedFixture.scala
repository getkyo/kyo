package kyo.fixtures

/** Sealed hierarchy fixtures for cross-platform permittedSubclasses testing.
  *
  * Provides sealed traits with known subclasses so that SealedFidelityTest can run on JS/Native
  * without requiring scala.Option or scala.util.Either from the stdlib classpath.
  *
  * Animal has 2 subclasses (Dog, Cat) mirroring the 2-element scala.Option hierarchy.
  * Vehicle has 2 subclasses (Car, Bike) mirroring the 2-element scala.util.Either hierarchy.
  * NonSealedMarker is intentionally non-sealed so that the negative case (permittedSubclasses Absent) can be tested.
  */
sealed trait Animal
final case class Dog(name: String) extends Animal
final case class Cat(age: Int)     extends Animal

sealed trait Vehicle
final case class Car(brand: String) extends Vehicle
final case class Bike(gears: Int)   extends Vehicle

class NonSealedMarker(val id: Int)
