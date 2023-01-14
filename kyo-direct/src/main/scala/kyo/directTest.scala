// package kyo

// import core._
// import tries._
// import options._
// import direct._

// object directTest extends App {

//   case class Person(name: String, age: Int)

//   def test(name: Option[String], age: String): Person > (Options | Tries) = select {
//     Person(from(name > Options), from(Tries(Integer.parseInt(age))))
//   }

//   println(test(Some("a"), "10") < Tries < Options)

// //   kyo.core.apply[scala.Int, kyo.options.Options](age)(((v0: scala.Int) => kyo.core.apply[java.lang.String, kyo.tries.Tries](name)(((`v0₂`: java.lang.String) => kyo.directTest.Person.apply(`v0₂`, v0)))(kyo.frames.given_Frame_T["apply"](new scala.ValueOf["apply"]("apply")))))(kyo.frames.given_Frame_T["apply"](new scala.ValueOf["apply"]("apply")))
// }
