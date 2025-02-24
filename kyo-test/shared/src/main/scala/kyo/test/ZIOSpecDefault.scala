package kyo.test

import kyo.*
import kyo.layer.Layer

abstract class ZIOSpecDefault extends ZIOSpec[TestEnvironment]:
    override val bootstrap: Layer[TestEnvironment, Env[Any] & Abort[Any]] =
        testEnvironment

    def spec: Spec[TestEnvironment with Scope, Any]
end ZIOSpecDefault
