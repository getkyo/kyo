package kyo.test

import kyo.*

object TestServices:

    /** The default ZIO Test services.
      */
    val test: Environment[Annotations with Live with Sized with TestConfig] =
        Environment[Annotations, Live, Sized, TestConfig](
            Annotations.Test(Var.unsafe.make(TestAnnotationMap.empty)(Unsafe.unsafe)),
            Live.Test(DefaultServices.live),
            Sized.Test(Local.unsafe.make(100)(Unsafe.unsafe)),
            TestConfig.Test(100, 100, 200, 1000)
        )(Annotations.tag, Live.tag, Sized.tag, TestConfig.tag)

    private[kyo] val currentServices: Local.WithPatch[
        Environment[
            Annotations with Live with Sized with TestConfig
        ],
        Environment.Patch[
            Annotations with Live with Sized with TestConfig,
            Annotations with Live with Sized with TestConfig
        ]
    ] =
        Local.unsafe.makeEnvironment(test)(Unsafe.unsafe)
end TestServices
