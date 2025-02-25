package kyo.test

import kyo.*
import kyo.layer.Layer

abstract class KyoSpecDefault extends KyoSpec[TestEnvironment]:
    override val bootstrap: Layer[TestEnvironment, Env[Any] & Abort[Any]] =
        testEnvironment

    def spec: Spec[TestEnvironment with Scope, Any]
end KyoSpecDefault
