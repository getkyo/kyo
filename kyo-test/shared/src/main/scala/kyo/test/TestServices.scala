package kyo.test

import zio._
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

object TestServices {

  /**
   * The default ZIO Test services.
   */
  val test: ZEnvironment[Annotations with Live with Sized with TestConfig] =
    ZEnvironment[Annotations, Live, Sized, TestConfig](
      Annotations.Test(Ref.unsafe.make(TestAnnotationMap.empty)(Unsafe.unsafe)),
      Live.Test(DefaultServices.live),
      Sized.Test(FiberRef.unsafe.make(100)(Unsafe.unsafe)),
      TestConfig.Test(100, 100, 200, 1000)
    )(Annotations.tag, Live.tag, Sized.tag, TestConfig.tag)

  private[zio] val currentServices: FiberRef.WithPatch[
    ZEnvironment[
      Annotations with Live with Sized with TestConfig
    ],
    ZEnvironment.Patch[
      Annotations with Live with Sized with TestConfig,
      Annotations with Live with Sized with TestConfig
    ]
  ] =
    FiberRef.unsafe.makeEnvironment(test)(Unsafe.unsafe)
}
