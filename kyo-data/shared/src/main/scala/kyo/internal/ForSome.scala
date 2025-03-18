package kyo.internal

/** Existentinal types encoding for type constructors `F[_]`. `ForSome[F]` is semantically equiavalent to `F[?]`
  */
type ForSome[F[_]] = ForSome.Type[F]
object ForSome:
    class Unwrap[F[_], A](val unwrap: F[A]) extends AnyVal

    opaque type Type[F[_]] <: Unwrap[F, ?] = Unwrap[F, ?]

    /** Converts value of type `F[A]` to existential form
      */
    inline def apply[F[_], A](v: F[A]): Type[F] = Unwrap(v)

    inline def of[F[_]]: OfOps[F] = new OfOps[F](())
    class OfOps[F[_]](dummy: Unit) extends AnyVal:
        /** Converts value of type `F[A]` to existential form
          */
        inline def apply[A](f: F[A]): Type[F] = ForSome(f)
    end OfOps
end ForSome

/** Existentinal types encoding for type constructors `F[_, _]`. `ForSome2[F]` is semantically equiavalent to `F[?, ?]`
  */
type ForSome2[F[_, _]] = ForSome2.Type[F]
object ForSome2:
    class Unwrap[F[_, _], A1, A2](val unwrap: F[A1, A2]) extends AnyVal

    opaque type Type[F[_, _]] <: Unwrap[F, ?, ?] = Unwrap[F, ?, ?]

    /** Converts value of type `F[A]` to existential form
      */
    inline def apply[F[_, _], A1, A2](v: F[A1, A2]): Type[F] = Unwrap(v)

    inline def of[F[_, _]]: OfOps[F] = new OfOps[F](())
    class OfOps[F[_, _]](dummy: Unit) extends AnyVal:
        /** Converts value of type `F[A1, A2]` to existential form
          */
        inline def apply[A1, A2](f: F[A1, A2]): Type[F] = ForSome2(f)
    end OfOps
end ForSome2
