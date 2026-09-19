package kyo

class TagClassLoadingJvmTest extends kyo.test.Test[Any]:

    "public Tag methods have resolvable JVM signatures" in {
        val companion = Class.forName("kyo.Tag$package$Tag$", false, getClass.getClassLoader)
        val methods   =
            try companion.getDeclaredMethods
            catch case error: LinkageError => fail(s"Tag method signatures reference an unavailable class: $error")
        val show = methods.filter(_.getName == "show")
        assert(show.map(_.getReturnType).sameElements(Array(classOf[String])))
    }

end TagClassLoadingJvmTest
