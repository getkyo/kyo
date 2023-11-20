// package kyo.concurrent

// import kyo._
// import kyo.core._
// import kyo.core.internal._
// import kyo.concurrent.channels._
// import kyo.concurrent.fibers._
// import kyo.ios._
// import kyo.lists._
// import kyo.App.Effects

// object Test extends App {

//   import streams._

//   def run(args: List[String]) = {
//     Streams.run {
//       Streams.source(List(1, 2)).map(_ + 1) // .map(i => Streams.source(List(i, i + 1)))
//     }.map(_.foreach(println(_)))
//   }

// }

// object streams {

//   import Stream._

//   sealed trait Stream[+T] {

//     def get: T > (Fibers with Streams) =
//       this match {
//         case Done =>
//           Streams.done
//         case More(v, Done) =>
//           v
//         case _ =>
//           Streams.get(this)
//       }

//     // def flatten[U](implicit ev: T => Stream[U]): Stream[U] > Fibers = {
//     //   def loop(s: Stream[T]): Stream[U] > Fibers =
//     //     s match {
//     //       case Done => Done
//     //       case More(v, tail) =>
//     //         val a: Stream[U] > (Fibers & IOs) = v.map(ev)
//     //         a.map {
//     //           case Done => Done
//     //           case More(u, utail) =>
//     //             More(u, ???)
//     //         }
//     //     }
//     //   loop(this)
//     // }

//     def sink[B >: T](ch: Channel[B]): Unit > Fibers = {
//       def loop(s: Stream[T]): Unit > Fibers =
//         s match {
//           case Done => ()
//           case More(v, tail) =>
//             ch.put(v).andThen {
//               tail.map(loop(_))
//             }
//         }
//       loop(this)
//     }

//     def drain: Unit > Fibers =
//       this match {
//         case Done => ()
//         case More(_, tail) =>
//           tail.map(_.drain)
//       }

//     def foreach(f: T => Unit > Fibers): Unit > Fibers =
//       this match {
//         case Done => ()
//         case More(v, tail) =>
//           v.map(f).andThen {
//             tail.map(_.foreach(f))
//           }
//       }

//     def head: Option[T] > Fibers =
//       this match {
//         case Done       => None
//         case More(v, _) => v.map(Some(_))
//       }

//     def last: Option[T] > Fibers = {
//       def loop(s: Stream[T], prev: Option[T]): Option[T] > Fibers =
//         s match {
//           case Done => prev
//           case More(v, tail) =>
//             tail.map { s =>
//               v.map(v => loop(s, Some(v)))
//             }
//         }
//       loop(this, None)
//     }

//     def count: Int > Fibers = {
//       def loop(s: Stream[T], acc: Int): Int > Fibers =
//         s match {
//           case Done => acc
//           case More(_, tail) =>
//             tail.map(loop(_, acc + 1))
//         }
//       loop(this, 0)
//     }

//     def take(n: Int): List[T] > Fibers = {
//       def loop(s: Stream[T], acc: List[T]): List[T] > Fibers =
//         s match {
//           case Done => acc.reverse
//           case More(v, tail) =>
//             v.map { v =>
//               tail.map(loop(_, v :: acc))
//             }
//         }
//       loop(this, Nil)
//     }
//   }

//   object Stream {

//     case object Done
//         extends Stream[Nothing]

//     case class More[T](v: T > Fibers, tail: Stream[T] > Fibers)
//         extends Stream[T]
//   }

//   type Streams >: Streams.Effects <: Streams.Effects

//   object Streams {
//     type Effects = StreamsEffect with Fibers

//     def run
//   }

//   final class StreamsEffect private[streams] () extends Effect[Stream, StreamsEffect] {

//     def run[T, S](v: T > (Streams with S)): Stream[T] > (Fibers with S) =
//       handle[T, S, Fibers](v)

//     def source[T](v: T > Fibers, tail: Stream[T] > Fibers): T > Streams =
//       More(v, tail).get

//     def source[T](f: () => Stream[T]): T > Streams =
//       f().get.map(v => source(v, Streams.run(source(f))))

//     def source[T](ch: Channel[T]): T > Streams =
//       source(ch.take, Streams.run(source(ch)))

//     def source[T, S](i: Iterable[T > Fibers]): T > Streams = {
//       val it = i.iterator
//       def loop: T > Streams =
//         it.nextOption() match {
//           case None =>
//             done
//           case Some(v) =>
//             source(v, Streams.run(loop))
//         }
//       loop
//     }

//     val done: Nothing > Streams = suspend(Done)

//     private[streams] def get[T](s: Stream[T]): T > Streams =
//       suspend(s)

//     private implicit val handler: Handler[Stream, Streams, Fibers] =
//       new Handler[Stream, Streams, Fibers] {
//         def pure[T](v: T) =
//           More(v, Done)
//         def apply[T, U, S](s: Stream[T], f: T => U > (Streams with S)) = {
//           def loop(s: Stream[T]): Stream[Stream[U]] > (Fibers with S) =
//             s match {
//               case Done =>
//                 Done
//               case More(v, tail) =>
//                 run(v.map(f)).map { su =>
//                   tail.map(loop).map { ntail =>
//                     More(su, ntail)
//                   }
//                 }
//             }
//           loop(s).map(_.get).map(_.get)
//         }
//       }
//   }
// }
