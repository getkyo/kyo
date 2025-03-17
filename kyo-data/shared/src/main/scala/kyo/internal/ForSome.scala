package kyo.internal

/** Existentinal types encoding for type constructors `F[_]`. `ForSome[F]` is semantically equiavalent to `F[?]`
  */
type ForSome[F[_]] = ForSome.Type[F]
object ForSome:
    type Type[F[_]] <: { type A }

    /** Converts value of type `F[A]` to existential form
      */
    inline def apply[F[_], A](f: F[A]): Type[F] = f.asInstanceOf[Type[F]]

    extension [F[_]](value: Type[F])
        /** Converts value of `ForSome[F]` to `F[value.A]`
          */
        inline def unwrap: F[value.A] = value.asInstanceOf[F[value.A]]
    end extension

    inline def of[F[_]]: OfOps[F] = new OfOps[F](())
    class OfOps[F[_]](dummy: Unit) extends AnyVal:
        /** Converts value of type `F[A]` to existential forms
          */
        inline def apply[A](f: F[A]): Type[F] = ForSome(f)
    end OfOps
end ForSome

/** Existentinal types encoding for type constructors `F[_, _]`. `ForSome2[F]` is semantically equiavalent to `F[?, ?]`.
  */
type ForSome2[F[_, _]] = ForSome2.Type[F]
object ForSome2:
    type Type[F[_, _]] <: { type A1; type A2 }

    /** Converts value of type `F[A1, A2]` to existential form
      */
    inline def apply[F[_, _], A1, A2](f: F[A1, A2]): Type[F] = f.asInstanceOf[Type[F]]

    inline def unapply[F[_, _]](value: Type[F]): F[value.A1, value.A2] = value.asInstanceOf[F[value.A1, value.A2]]

    extension [F[_, _]](value: Type[F])
        /** Converts value of `ForSome2[F]` to `F[value.A1, value.A2]`
          */
        inline def unwrap: F[value.A1, value.A2] = unapply(value)
    end extension

    inline def of[F[_, _]]: OfOps[F] = new OfOps[F](())

    class OfOps[F[_, _]](dummy: Unit) extends AnyVal:
        /** Converts value of type `F[A1, A2]` to existential form
          */
        def apply[A1, A2](f: F[A1, A2]): Type[F] = ForSome2(f)
    end OfOps
end ForSome2
