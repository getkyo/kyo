package kyo

/** The constant-or-signal idiom for every bindable prop (the central FP idiom of the module).
  *
  * A [[Bound.Const]] holds a static value; a [[Bound.Ref]] holds a [[Signal]] whose emissions drive a
  * targeted mutation of exactly the live object the prop binds (never a scene rebuild). Every transform,
  * material, light, and camera prop is a `Bound[A]`, so a prop is either fixed or reactive with one
  * uniform shape.
  *
  * This type is internal to the kyo package. Users pass raw values or `Signal[A]` through the
  * typed setter overloads on each node; the `Bound` wrapper is an implementation detail.
  */
sealed private[kyo] trait Bound[+A] derives CanEqual

private[kyo] object Bound:
    /** A static prop value. */
    final case class Const[A](value: A) extends Bound[A]

    /** A signal-driven prop value: the reconciler observes `signal` and patches the one bound object. */
    final case class Ref[A](signal: Signal[A]) extends Bound[A]
end Bound
