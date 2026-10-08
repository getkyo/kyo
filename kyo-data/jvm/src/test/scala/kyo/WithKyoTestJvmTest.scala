package kyo

class WithKyoTestJvmTest extends kyo.test.Test[Any]:

    private def onTestClasspath(className: String): Boolean =
        getClass.getClassLoader.getResource(className.replace('.', '/') + ".class") != null

    "a module's tests see kyo-test's runner and none of the runner's own tests" in {
        assert(onTestClasspath("kyo.test.runner.SbtFramework"))
        assert(!onTestClasspath("kyo.TestApiSelfTest"))
    }

end WithKyoTestJvmTest
