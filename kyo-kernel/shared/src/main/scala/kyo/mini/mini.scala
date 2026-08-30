// package kyo.mini

// import Kyo.*
// import kyo.Tag

// trait Effect[-I[_], +O[_]]

// object Effect:
//     def suspend[I[_], O[_], E <: Effect[I, O], A, S](
//         tag: Tag[E],
//         input: I[A]
//     ): Kyo[O[A], E] =
//         Suspend(tag, input)
// end Effect

// sealed trait Arrow[-A, +B, -S]:
//     def apply[C, S2](v: Kyo[A, S2], cont: Arrow[B, C, S2]): Kyo[C, S & S2] =
//         Defer(v, this, cont)
//     def chain[C, S2](a: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
//         Arrow.Chain(this, a)
// end Arrow

// object Arrow:

//     def apply[A, B, S](f: A => Kyo[B, S]): Arrow[A, B, S] =
//         new Arrow[A, B, S]:
//             def apply[C, S2](v: Kyo[A, S2], cont: Arrow[B, C, S2]) =
//                 Defer(v, this, cont)

//     case class Id[A]() extends Arrow[A, A, Any]:
//         def apply[C, S2](v: Kyo[A, S2], cont: Arrow[A, C, S2]) =
//             cont(v, Id())

//     case class Chain[A, B, C, S](
//         a: Arrow[A, B, S],
//         b: Arrow[B, C, S]
//     ) extends Arrow[A, C, S]:
//         def apply[D, S2](v: Kyo[A, S2], cont: Arrow[C, D, S2]) =
//             Defer(v, this, cont)
//     end Chain
// end Arrow

// sealed trait Kyo[+A, -S]:
//     def chain[B, S2](a: Arrow[A, B, S2]): Kyo[B, S & S2] =
//         Kyo.Defer(this, a)

// object Kyo:
//     case class Done[A](
//         result: A
//     ) extends Kyo[A, Any]

//     case class Defer[A, B, C, S](
//         value: Kyo[A, S],
//         contA: Arrow[A, B, S],
//         contB: Arrow[B, C, S] = Arrow.Id[B]()
//     ) extends Kyo[C, S]

//     case class Suspend[I[_], O[_], E <: Effect[I, O], A, S](
//         tag: Tag[E],
//         input: I[A]
//     ) extends Kyo[O[A], E & S]

//     case class Handle[I[_], O[_], E <: Effect[I, O], A, S, State](
//         state: State,
//         value: Kyo[A, E & S],
//         handler: [X] => (State, I[X], Arrow[O[X], A, E & S]) => Kyo[A, E & S]
//     ) extends Kyo[A, S]
// end Kyo

// object Eval:
//     def apply[A](v: Kyo[A, Any]): A =
//         def loop[A, B, C, S](v: Kyo[A, S], contA: Arrow[A, B, S], contB: Arrow[A, C, S]) =
//             v match
//                 case Done(v)                    => v
//                 case Defer(value, contA, contB) => ???
//     end apply
// end Eval
