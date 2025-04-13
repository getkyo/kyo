// package kyo.kernel2

// import java.util.ArrayDeque
// import kyo.Const
// import kyo.Frame
// import kyo.Maybe
// import kyo.Tag
// import kyo.kernel2.internal.*
// import scala.annotation.nowarn
// import scala.annotation.tailrec
// import scala.util.NotGiven

// sealed abstract class Arrow[-A, +B, -S]:

//     import Arrow.internal.*

//     def apply[S2 <: S](v: A < S2)(using Safepoint): B < (S & S2)

//     def map[C, S2](f: Safepoint ?=> B => C < S2): Arrow[A, C, S & S2] =
//         andThen(Arrow.init(f))

//     def andThen[C, S2](other: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
//         if this eq Arrow._identity then
//             other.asInstanceOf[Arrow[A, C, S & S2]]
//         else
//             AndThen(this, other)
// end Arrow

// object Arrow:

//     import Arrow.internal.*

//     private[Arrow] val _identity: Arrow[Any, Any, Any] = init(v => v)

//     def apply[A]: Arrow[A, A, Any] = _identity.asInstanceOf[Arrow[A, A, Any]]

//     def const[A](v: A)(using Frame): Arrow[Any, A, Any] = init(_ => v)

//     def defer[A, S](v: Safepoint ?=> A < S)(using NotGiven[A <:< Any < Nothing]): Arrow[Any, A, S] =
//         suspendDefer.map(_ => v)

//     @nowarn("msg=anonymous")
//     inline def init[A](using inline frame: Frame)[B, S](inline f: Safepoint ?=> A => B < S): Arrow[A, B, S] =
//         new Lift[A, B, S]:
//             def _frame = frame
//             def apply[S2 <: S](v: A < S2)(using safepoint: Safepoint): B < (S & S2) =
//                 v match
//                     case kyo: Arrow[Any, A, S2] =>
//                         kyo.andThen(this)
//                     case _ =>
//                         var value = v
//                         if value.isInstanceOf[Box[?]] then
//                             value = value.asInstanceOf[Box[A]].v
//                         if !safepoint.enter(_frame, value) then
//                             return defer(this(value))
//                         val r = f(value.asInstanceOf[A])
//                         safepoint.exit()
//                         r
//             end apply

//     @nowarn("msg=anonymous")
//     inline def loop[A, B, S](inline f: Safepoint ?=> (Arrow[A, B, S], A) => B < S)(using inline frame: Frame): Arrow[A, B, S] =
//         new Lift[A, B, S]:
//             def _frame = frame
//             def apply[S2 <: S](v: A < S2)(using safepoint: Safepoint): B < (S & S2) =
//                 v match
//                     case kyo: Arrow[Any, A, S2] =>
//                         kyo.andThen(this)
//                     case _ =>
//                         val value =
//                             v match
//                                 case v: Box[A] @unchecked => v.v
//                                 case _                    => v.asInstanceOf[A]
//                         f(this, value)

//     object internal:

//         sealed trait IsPure
//         case object IsPure extends IsPure

//         abstract class Lift[A, B, S] extends Arrow[A, B, S]:
//             def _frame: Frame
//             // final def apply[S2 <: S](v: A < S2)(using safepoint: Safepoint): B < (S & S2) =
//             //     v match
//             //         case kyo: Arrow[Any, A, S2] =>
//             //             kyo.andThen(this)
//             //         case _ =>
//             //             val value =
//             //                 v match
//             //                     case v: Box[A] @unchecked => v.v
//             //                     case _                    => v.asInstanceOf[A]
//             //             if !safepoint.enter(_frame, value) then
//             //                 defer(run(value))
//             //             else
//             //                 val r = run(value)
//             //                 safepoint.exit()
//             //                 r
//             //             end if
//             // end apply

//             // def run(v: A)(using Safepoint): B < S

//             override def toString = s"Lift(${_frame.show})"
//         end Lift

//         class Pure
//         case object Pure extends Pure

//         // def preEval[A, S](v: A < S)(using safepoint: Safepoint, frame: Frame): Maybe[A < S] =
//         //     v match
//         //         case kyo: Arrow[Any, A, S] =>
//         //             Maybe.empty
//         //         case _ =>
//         //             val value =
//         //                 v match
//         //                     case v: Box[A] @unchecked => v.v
//         //                     case _ => v.asInstanceOf[A]
//         //             if !safepoint.enter(frame, value) then
//         //                 defer(run(value))
//         //             else
//         //                 val r = run(value)
//         //                 safepoint.exit()
//         //                 r
//         //             end if

//         final case class AndThen[A, B, C, S](a: Arrow[A, B, S], b: Arrow[B, C, S]) extends Arrow[A, C, S]:
//             def apply[S2 <: S](v: A < S2)(using Safepoint): C < (S & S2) = b(a(v))

//             override def toString = s"AndThen($a, $b)"
//         end AndThen

//         abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A] extends Arrow[Any, O[A], E]:
//             def _frame: Frame
//             def _tag: Tag[E]
//             val _input: I[A]

//             def apply[S2 <: E](v: Any < S2)(using Safepoint): O[A] < (E & S2) = this

//             override def toString = s"Suspend(tag=${_tag.showTpe}, input=$_input, frame=${_frame.position})"
//         end Suspend

//         sealed trait Defer extends ArrowEffect[Const[Any], Const[Any]]

//         val suspendDefer: Arrow[Any, Any, Any] =
//             (new Suspend[Const[Any], Const[Any], Defer, Any]:
//                 def _frame = Frame.internal
//                 val _input = ()
//                 def _tag   = Tag[Defer]

//                 override def apply[S2 <: Defer](v: Any < S2)(using Safepoint) = ()
//             ).asInstanceOf[Arrow[Any, Any, Any]]

//         case class Chain[A, B, S](array: IArray[Arrow[Any, Any, Any]]) extends Arrow[A, B, S]:
//             final def apply[S2 <: S](v: A < S2)(using Safepoint) =
//                 Chain.unsafeEval(v, array)

//             override def toString = s"Chain(${array.mkString(", ")})"
//         end Chain

//         object Chain:
//             val emptyArray = new Array[Arrow[Any, Any, Any]](0)
//             def unsafeEval[A, B, S](v: A < S, array: IArray[Arrow[Any, Any, Any]]): B < S =
//                 def loop(v: Any < Any, idx: Int): Any < Any =
//                     if idx == array.length then
//                         v
//                     else
//                         array(idx)(v).reduce(
//                             pending = kyo =>
//                                 val contSize = array.length - idx
//                                 if contSize == 1 then
//                                     kyo
//                                 else
//                                     val newArray = new Array[Arrow[Any, Any, Any]](contSize)
//                                     newArray(0) = kyo
//                                     System.arraycopy(array, idx + 1, newArray, 1, contSize - 1)
//                                     Chain(IArray.unsafeFromArray(newArray))
//                                 end if
//                             ,
//                             done = loop(_, idx + 1)
//                         )
//                 loop(v, 0).asInstanceOf[B < S]
//             end unsafeEval
//         end Chain

//     end internal
// end Arrow
