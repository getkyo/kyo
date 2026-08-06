// package kyo.proto

// import kyo.Const
// import scala.annotation.nowarn
// import scala.reflect.ClassTag

// /** A generic unboxed discriminated union: one erased payload type `A` plus a set of
//   * concrete, runtime-discriminable `Cases`, stored flat with no wrapper allocation.
//   *
//   * `Type` is opaque and defined here in the base, so the representation union (and its
//   * nesting marker) is never visible outside. The marker itself is abstract here and
//   * supplied by the inline builder, which mints a distinct marker class per union so
//   * markers of different unions never conflate.
//   *
//   * `reduce` is inline and takes inline functions, so a fold compiles to a plain branch
//   * with no closure allocation or megamorphic dispatch. Its discrimination helpers are
//   * therefore public (they take `Any` and never mention the marker), so the inlined body
//   * is legal at any call site.
//   */
// abstract class Unboxed:
//     type Cases[+A]
//     protected type Nested[+A]

//     opaque type Type[+A] = A | Cases[A] | Nested[A]

//     protected def box[A](value: A): Nested[A]
//     def isBox(value: Any): Boolean
//     def isCase(value: Any): Boolean
//     def unbox(value: Any): Any

//     /** Injects a payload, boxing it only when its runtime value would collide with a case
//       * or is already a marker of this union (the latter is what preserves nesting:
//       * chaining replaces a depth counter).
//       */
//     def lift[A](value: A): Type[A] =
//         if isCase(value) || isBox(value) then box(value)
//         else value

//     /** Injects a case value. */
//     def fromCase[A](c: Cases[A]): Type[A] = c

//     /** Eliminates a value: `onCases` for a case, `onValue` for the payload. */
//     inline def reduce[A, B](value: Type[A])(
//         inline onValue: A => B,
//         inline onCases: Cases[A] => B
//     ): B =
//         if isBox(value) then onValue(unbox(value).asInstanceOf[A])
//         else if isCase(value) then onCases(value.asInstanceOf[Cases[A]])
//         else onValue(value.asInstanceOf[A])
// end Unboxed

// object Unboxed:

//     /** Builds a union whose case set is `C`. Inlined so each use mints its own marker
//       * class; the return type refines `Cases` (the union's own public types, safe to
//       * expose for discrimination) while `Type` stays the opaque base member.
//       *
//       * `ClassTag[C[Any]]` discriminates a single class or sealed hierarchy; an ad-hoc
//       * union of unrelated types needs a richer tag (`ConcreteTag`).
//       */
//     @nowarn
//     inline def apply[C[+_]](using ct: ClassTag[C[Any]]): Unboxed { type Cases[+A] = C[A] } =
//         new Unboxed:
//             final class Marker[+A](val value: A)

//             type Cases[+A]  = C[A]
//             type Nested[+A] = Marker[A]

//             protected def box[A](value: A): Nested[A] = Marker(value)
//             def isBox(value: Any): Boolean            = value.isInstanceOf[Marker[?]]
//             def isCase(value: Any): Boolean           = ct.runtimeClass.isInstance(value)
//             def unbox(value: Any): Any                = value.asInstanceOf[Marker[?]].value
//         end new
//     end apply

// end Unboxed

// /** Binary variant: a union with two type parameters, e.g. an error type `E` and a payload
//   * type `A`. `E` is phantom at runtime (it only refines the case type) but is tracked at
//   * the type level, so `Cases[E, A]` stays typed and the union author never re-types it.
//   */
// abstract class Unboxed2:
//     type Cases[+A, -B]
//     protected type Nested[+A]

//     opaque type Type[+A, -B] = A | Cases[A, B] | Nested[A]

//     protected def box[A](value: A): Nested[A]
//     def isBox(value: Any): Boolean
//     def isCase(value: Any): Boolean
//     def unbox(value: Any): Any

//     def lift[A, B](value: A): Type[A, B] =
//         if isCase(value) || isBox(value) then box(value)
//         else value

//     def fromCase[A, B](c: Cases[A, B]): Type[A, B] = c

//     inline def reduce[A, B, C](value: Type[A, B])(
//         inline onValue: A => C,
//         inline onCases: Cases[A, B] => C
//     ): C =
//         if isBox(value) then onValue(unbox(value).asInstanceOf[A])
//         else if isCase(value) then onCases(value.asInstanceOf[Cases[A, B]])
//         else onValue(value.asInstanceOf[A])
// end Unboxed2

// object Unboxed2:

//     @nowarn
//     inline def apply[C[+_, -_]](using ct: ClassTag[C[Any, Any]]): Unboxed2 { type Cases[+A, -B] = C[A, B] } =
//         new Unboxed2:
//             final class Marker[+A](val value: A)

//             type Cases[+A, -B] = C[A, B]
//             type Nested[+A]    = Marker[A]

//             protected def box[A](value: A): Nested[A] = Marker(value)
//             def isBox(value: Any): Boolean            = value.isInstanceOf[Marker[?]]
//             def isCase(value: Any): Boolean           = ct.runtimeClass.isInstance(value)
//             def unbox(value: Any): Any                = value.asInstanceOf[Marker[?]].value
//         end new
//     end apply

// end Unboxed2

// /** An optional value on the unboxed union: the payload stored raw, or `Absent`. */
// opaque type Maybe[+A] = Maybe.unboxed.Type[A]

// object Maybe:

//     sealed abstract class Absent
//     case object Absent extends Absent

//     private[kyo] val unboxed = Unboxed[Const[Absent]]

//     def apply[A](value: A): Maybe[A] = unboxed.lift(value)
//     def empty[A]: Maybe[A]           = unboxed.fromCase(Absent)

//     extension [A](self: Maybe[A])
//         inline def fold[B](inline ifAbsent: => B)(inline ifPresent: A => B): B =
//             unboxed.reduce[A, B](self)(ifPresent, _ => ifAbsent)

//         def isEmpty: Boolean                    = fold(true)(_ => false)
//         def isDefined: Boolean                  = !isEmpty
//         def get: A                              = fold(throw new NoSuchElementException("Maybe.get"))(identity)
//         def getOrElse[B >: A](default: => B): B = fold(default)(identity)
//     end extension
// end Maybe

// /** A success value stored raw, or a typed `Failure`, or a `Panic`, no wrapper on success. */
// opaque type Result[+E, +A] = Result.unboxed.Type[E, A]
// //
// object Result:
// //
//     sealed abstract class Error[+E]
//     final case class Failure[+E](failure: E)     extends Error[E]
//     final case class Panic(exception: Throwable) extends Error[Nothing]
// //
//     // The case set is the sealed `Error` hierarchy, indexed by `E`. `E` is erased at
//     // runtime but tracked at the type level by `Unboxed2`, so `onCases` below is typed.
//     private[kyo] val unboxed = Unboxed2[[E, A] =>> Error[E]]
// //
//     def succeed[E, A](value: A): Result[E, A]           = unboxed.lift[E, A](value)
//     def fail[E, A](failure: E): Result[E, A]            = unboxed.fromCase[E, A](Failure(failure))
//     def panic[E, A](exception: Throwable): Result[E, A] = unboxed.fromCase[E, A](Panic(exception))
// //
//     extension [E, A](self: Result[E, A])
//         inline def fold[B](inline onSuccess: A => B, inline onFailure: E => B, inline onPanic: Throwable => B): B =
//             unboxed.reduce[E, A, B](self)(
//                 onSuccess,
//                 {
//                     case Failure(e) => onFailure(e)
//                     case Panic(t)   => onPanic(t)
//                 }
//             )
// //
//         def isSuccess: Boolean = fold(_ => true, _ => false, _ => false)
//         def isFailure: Boolean = fold(_ => false, _ => true, _ => false)
//         def isPanic: Boolean   = fold(_ => false, _ => false, _ => true)
// //
//         def getOrThrow(using ev: E <:< Throwable): A =
//             fold(identity, e => throw ev(e), t => throw t)
//     end extension
// end Result
