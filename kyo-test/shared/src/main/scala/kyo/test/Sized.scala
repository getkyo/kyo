package kyo.test

import kyo.*
import kyo.FiberRef
import kyo.Frame
import kyo.Layer
import kyo.Tag
import kyo.Unsafe
import kyo.stream.KStream
import kyo.test.Gen

trait Sized extends Serializable:
    def size(implicit trace: Frame): Int < IO
    def withSize[R, E, A](size: Int)(effect: A < Env[R] & Abort[E])(implicit trace: Frame): A < Env[R] & Abort[E]
    def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Frame): Gen[R, A]
end Sized

object Sized:

    val tag: Tag[Sized] = Tag[Sized]

    final case class Test(fiberRef: FiberRef[Int]) extends Sized:
        def size(implicit trace: Frame): Int < IO =
            fiberRef.get
        def withSize[R, E, A](size: Int)(effect: A < Env[R] & Abort[E])(implicit trace: Frame): A < Env[R] & Abort[E] =
            fiberRef.locally(size)(effect)
        def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Frame): Gen[R, A] =
            Gen {
                KStream
                    .fromKyo(fiberRef.get)
                    .flatMap { oldSize =>
                        KStream.scoped(fiberRef.locallyScoped(size)) *> gen.sample.mapKyo(a => fiberRef.set(oldSize).as(a))
                    }
            }
    end Test

    val default: Layer[Sized] =
        live(100)(Trace.empty)

    def live(size: Int)(implicit trace: Frame): Layer[Sized] =
        Layer.scoped {
            for
                fiberRef <- FiberRef.make(size)
                sized = Test(fiberRef)
                _ <- withSizedScoped(sized)
            yield sized
        }

    private[test] val initial: Sized =
        Test(FiberRef.unsafe.make(100)(Unsafe.unsafe))

    def size(implicit trace: Frame): Int < IO =
        sizedWith(_.size)

    def withSize[R, E, A](size: Int)(effect: A < Env[R] & Abort[E])(implicit trace: Frame): A < Env[R] & Abort[E] =
        sizedWith(_.withSize(size)(effect))

    def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Frame): Gen[R, A] =
        Gen.fromKyo(sized).flatMap(_.withSizeGen(size)(gen))
end Sized
