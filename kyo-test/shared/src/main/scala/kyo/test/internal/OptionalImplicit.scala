package kyo.test.internal

trait OptionalImplicit[A]:
    def value: Option[A]

object OptionalImplicit extends LowPriOptionalImplicit:
    def apply[A: OptionalImplicit]: Option[A] = implicitly[OptionalImplicit[A]].value

    implicit def some[A](using instance: A): OptionalImplicit[A] = new OptionalImplicit[A]:
        val value: Option[A] = Some(instance)
end OptionalImplicit

trait LowPriOptionalImplicit:
    implicit def none[A]: OptionalImplicit[A] = new OptionalImplicit[A]:
        val value: Option[A] = None
