// package kyo

// import kyo.core.*
// import kyo.internal.Trace

// sealed trait Choices extends Effect[Seq, Id]

// object Choices:

//     inline def get[T](inline v: Seq[T])(using inline tag: Tag[Choices]): T < Choices =
//         suspend(tag, v)

//     def run[T, S](v: T < (Choices & S))(using tag: Tag[Choices], trace: Trace): Seq[T] < S =
//         handle[Seq, Id, Choices, Seq[T], S, Any](tag, v.map(Seq(_))) {
//             [C] =>
//                 (input, cont) =>
//                     Seqs.collect(input.map(e => Choices.run(cont(e)))).map(_.flatten.flatten)
//         }

//     inline def eval[T, U, S](v: Seq[T])(f: T => U < S)(using inline tag: Tag[Choices]): U < (Choices & S) =
//         v match
//             case Seq(head) => f(head)
//             case v         => suspend(tag, v, f)

//     def filter[S](v: Boolean < S)(using Trace): Unit < (Choices & S) =
//         v.map {
//             case true =>
//                 ()
//             case false =>
//                 drop
//         }

//     def drop(using Trace): Nothing < Choices =
//         get(Seq.empty)

// end Choices
