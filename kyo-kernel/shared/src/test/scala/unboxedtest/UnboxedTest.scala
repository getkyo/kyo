// package unboxedtest

// import kyo.Const
// import kyo.proto.Maybe
// import kyo.proto.Result
// import kyo.proto.Unboxed
// import kyo.test.Test

// A SECOND, distinct union built on the same primitive, to exercise cross-union nesting.
// sealed abstract class Empty
// case object Empty extends Empty

// opaque type Slot[+A] = Slot.unboxed.Type[A]

// object Slot:
//     private[unboxedtest] val unboxed = Unboxed[Const[Empty]]
//     def apply[A](v: A): Slot[A]      = unboxed.lift(v)
//     def empty[A]: Slot[A]            = unboxed.fromCase(Empty)
//     extension [A](self: Slot[A])
//         def fold[B](ifEmpty: => B)(ifValue: A => B): B =
//             unboxed.reduce[A, B](self)(ifValue, _ => ifEmpty)
//         def isEmpty: Boolean   = fold(true)(_ => false)
//         def isDefined: Boolean = !isEmpty
//         def get: A             = fold(throw new NoSuchElementException("Slot.get"))(identity)
//     end extension
// end Slot

// class UnboxedTest extends Test[Any]:

//     "Maybe: construct, empty, get" in {
//         assert(Maybe(42).get == 42)
//         assert(Maybe.empty[Int].isEmpty)
//         assert(!Maybe(42).isEmpty)
//     }

//     "Maybe: payload that IS the case value stays present" in {
//         val m: Maybe[Maybe.Absent] = Maybe(Maybe.Absent)
//         assert(m.isDefined)
//         assert(m.get eq Maybe.Absent)
//     }

//     "Maybe: nesting round-trips" in {
//         val inner: Maybe[Int]        = Maybe.empty
//         val outer: Maybe[Maybe[Int]] = Maybe(inner)
//         assert(outer.isDefined)
//         assert(outer.get.isEmpty)
//         assert(Maybe(Maybe(5)).get.get == 5)
//     }

//     "Maybe: deep nesting" in {
//         val d: Maybe[Maybe[Maybe[Int]]] = Maybe(Maybe(Maybe.empty[Int]))
//         assert(d.isDefined)
//         assert(d.get.isDefined)
//         assert(d.get.get.isEmpty)
//     }

//     "cross-union: a Maybe marker stored in a Slot keeps its identity (markers do not conflate)" in {
//         val m: Maybe[Maybe[Int]]       = Maybe(Maybe.empty[Int]) // a Maybe marker
//         val s: Slot[Maybe[Maybe[Int]]] = Slot(m)
//         assert(s.isDefined) // Slot present (did NOT mistake the Maybe marker for its own)
//         assert(s.get.isDefined) // inner Maybe still present
//         assert(s.get.get.isEmpty) // innermost empty
//     }

//     // "Result: construct and queries" in {
//     //     assert(Result.succeed[String, Int](42).isSuccess)
//     //     assert(Result.fail[String, Int]("boom").isFailure)
//     //     assert(Result.panic[String, Int](new RuntimeException("x")).isPanic)
//     //     assert(!Result.succeed[String, Int](42).isFailure)
//     // }

//     // "Result: typed error channel" in {
//     //     val r: Result[String, Int] = Result.fail("boom")
//     //     assert(r.fold(_ => -1, e => e.length, _ => -2) == 4) // e: String, .length typechecks
//     // }

//     // "Result: THE bug class killed, succeed(panic) stays a success" in {
//     //     val inner: Result[Int, String]                 = Result.panic(new RuntimeException("boom"))
//     //     val outer: Result[String, Result[Int, String]] = Result.succeed(inner)
//     //     assert(outer.isSuccess)
//     //     assert(!outer.isPanic)
//     //     assert(outer.fold(_.isPanic, _ => false, _ => false)) // inner IS the panic
//     // }

//     // "Result: succeed(fail) preserved" in {
//     //     val inner: Result[Int, String]                 = Result.fail(42)
//     //     val outer: Result[String, Result[Int, String]] = Result.succeed(inner)
//     //     assert(outer.isSuccess)
//     //     assert(outer.fold(_.isFailure, _ => false, _ => false))
//     // }

//     // "cross-union: a Result marker stored in a Maybe keeps its identity" in {
//     //     val r: Result[String, Result[Int, String]] =
//     //         Result.succeed(Result.panic[Int, String](new RuntimeException("x"))) // a Result marker
//     //     val m: Maybe[Result[String, Result[Int, String]]] = Maybe(r)
//     //     assert(m.isDefined)
//     //     assert(m.get.isSuccess) // inner Result seen as success, not conflated
//     //     assert(m.get.fold(_.isPanic, _ => false, _ => false))
//     // }

// end UnboxedTest
