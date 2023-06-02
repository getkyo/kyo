// package kyoTest

// import kyo._
// import kyo.tries._
// import kyo.options._
// import kyo.direct._
// import kyo.direct
// import kyo.ios._
// import kyo.envs._
// import kyo.concurrent.fibers._
// import scala.util.Try
// import kyo.consoles._
// import kyo.concurrent.atomics._

// class directTest extends KyoTest {

//   "one run" in {
//     val io = defer {
//       val a = await(IOs("hello"))
//       a + " world"
//     }
//     assert(IOs.run(io) == "hello world")
//   }

//   "two runs" in {
//     val io =
//       defer {
//         val a = await(IOs("hello"))
//         val b = await(IOs("world"))
//         a + " " + b
//       }
//     assert(IOs.run(io) == "hello world")
//   }

//   "two effects" in {
//     val io: String > (IOs with Options) =
//       defer {
//         val a = await(Options.get(Some("hello")))
//         val b = await(IOs("world"))
//         a + " " + b
//       }
//     assert(IOs.run(Options.run(io)) == Some("hello world"))
//   }

//   "if" in {
//     var calls = List.empty[Int]
//     val io: Boolean > IOs =
//       defer {
//         if (await(IOs { calls :+= 1; true }))
//           await(IOs { calls :+= 2; true })
//         else
//           await(IOs { calls :+= 3; true })
//       }
//     assert(IOs.run(io))
//     assert(calls == List(1, 2))
//   }

//   "booleans" - {
//     "&&" in {
//       var calls = List.empty[Int]
//       val io: Boolean > IOs =
//         defer {
//           (await(IOs { calls :+= 1; true }) && await(IOs { calls :+= 2; true }))
//         }
//       assert(IOs.run(io))
//       assert(calls == List(1, 2))
//     }
//     "||" in {
//       var calls = List.empty[Int]
//       val io: Boolean > IOs =
//         defer {
//           (await(IOs { calls :+= 1; true }) || await(IOs { calls :+= 2; true }))
//         }
//       assert(IOs.run(io))
//       assert(calls == List(1))
//     }
//   }

//   "options" in {
//     def test[T](opt: Option[T]) =
//       assert(opt == Options.run(defer(await(Options.get(opt)))))
//     test(Some(1))
//     test(None)
//     test(Some("a"))
//   }
//   "tries" in {
//     def test[T](t: Try[T]) =
//       assert(t == Tries.run(defer(await(Tries.get(t)))))
//     test(Try(1))
//     test(Try(throw new Exception("a")))
//     test(Try("a"))
//   }
//   "consoles" in {
//     object console extends Console {

//       def printErr(s: => String): Unit > IOs = ???

//       def println(s: => String): Unit > IOs = ???

//       def print(s: => String): Unit > IOs = ???

//       def readln: String > IOs = "hello"

//       def printlnErr(s: => String): Unit > IOs = ???
//     }
//     val io: String > IOs = Consoles.run(console)(defer(await(Consoles.readln)))
//     assert(IOs.run(io) == "hello")
//   }

//   "kyo computations must be within a run block" in {
//     assertDoesNotCompile("defer(IOs(1))")
//     assertDoesNotCompile("""
//       defer {
//         val a = IOs(1)
//         10
//       }
//     """)
//     assertDoesNotCompile("""
//       defer {
//         val a = {
//           val b = IOs(1)
//           10
//         }
//         10
//       }
//     """)
//   }

//   "lists" in {
//     import kyo.lists._

//     val x = Lists.foreach(1, -2, -3)
//     val y = Lists.foreach("ab", "cde")

//     val v: Int > Lists =
//       defer {
//         val xx = await(x)
//         xx + (
//             if (xx > 0) then await(y).length * await(x)
//             else await(y).length
//         )
//       }

//     val a: List[Int] = Lists.run(v).pure
//     assert(a == List(3, -3, -5, 4, -5, -8, 0, 1, -1, 0))
//   }

//   "lists + filter" in {
//     import kyo.lists._

//     val x = Lists.foreach(1, -2, -3)
//     val y = Lists.foreach("ab", "cde")

//     val v: Int > Lists =
//       defer {
//         val xx = await(x)
//         val r =
//           xx + (
//               if (xx > 0) then await(y).length * await(x)
//               else await(y).length
//           )
//         await(Lists.filter(r > 0))
//         r
//       }

//     assert(Lists.run(v).pure == List(3, 4, 1))
//   }
// }
