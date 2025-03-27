package kyo.test

import kyo.*
import kyo.test.Gen

trait Sized extends Serializable:
    def size(implicit trace: Trace): Int < IO
    def withSize[R, E, A](size: Int)(effect: A < Env[R] & Abort[E])(implicit trace: Trace): A < Env[R] & Abort[E]
    def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Trace): Gen[R, A]
end Sized

object Sized:

    val tag: Tag[Sized] = Tag[Sized]

    final case class Test(fiberRef: Local[Int]) extends Sized:
        def size(implicit trace: Trace): Int < IO =
            fiberRef.get
        def withSize[R, E, A](size: Int)(effect: A < Env[R] & Abort[E])(implicit trace: Trace): A < (Env[R] & Abort[E]) =
            fiberRef.let[A, Env[R] & Abort[E]](size)(effect)
        def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Trace): Gen[R, A] =
            Gen {
                Stream
                    .apply(Emit.value(fiberRef.get))
                    .flatMap { oldSize =>
                        Stream.scoped(fiberRef.locallyScoped(size)) *> gen.sample.mapKyo(a => fiberRef.set(oldSize).as(a))
                    }
            }
    end Test

    val default: Layer[Sized] =
        live(100)(Trace.empty)

    def live(size: Int)(implicit trace: Trace): Layer[Sized] =
        Layer.scoped {
            for
                fiberRef <- Local.make(size)
                sized = Test(fiberRef)
                _ <- withSizedScoped(sized)
            yield sized
        }

    private[test] val initial: Sized =
        Test(Local.unsafe.make(100)(Unsafe.unsafe))

    def size(implicit trace: Trace): Int < IO =
        sizedWith(_.size)

    def withSize[R, E, A](size: Int)(effect: A < Env[R] & Abort[E])(implicit trace: Trace): A < Env[R] & Abort[E] =
        sizedWith(_.withSize(size)(effect))

    def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Trace): Gen[R, A] =
        Gen.fromKyo(sized).flatMap(_.withSizeGen(size)(gen))
end Sized
