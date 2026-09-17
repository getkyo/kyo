package kyo

class TastyClassLoadingJvmTest extends kyo.test.Test[Any]:

    "public Tasty methods have resolvable JVM signatures" in {
        val companion = Class.forName("kyo.Tasty$", false, getClass.getClassLoader)
        val methods =
            try companion.getDeclaredMethods
            catch case error: LinkageError => fail(s"Tasty method signatures reference an unavailable class: $error")
        val versions = methods.filter(_.getName == "supportedTastyVersion")
        assert(versions.map(_.getReturnType).sameElements(Array(classOf[Tasty.Version])))
    }

end TastyClassLoadingJvmTest
