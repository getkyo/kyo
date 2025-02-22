package zio.test

import zio.*
import zio.internal.stacktracer.Tracer
import zio.internal.stacktracer.Tracer.instance
import zio.internal.stacktracer.Tracer.newTrace
import zio.stacktracer.TracingImplicits.disableAutoTrace

abstract class ZIOSpecDefault extends ZIOSpec[TestEnvironment]:

    override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
        testEnvironment

    def spec: Spec[TestEnvironment with Scope, Any]
end ZIOSpecDefault
