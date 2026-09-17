package kyo

class ConcreteTagClassLoadingJvmTest extends kyo.test.Test[Any]:

    "public ConcreteTag methods have resolvable JVM signatures" in {
        val companion = Class.forName("kyo.ConcreteTag$package$ConcreteTag$", false, getClass.getClassLoader)
        val methods =
            try companion.getDeclaredMethods
            catch case error: LinkageError => fail(s"ConcreteTag method signatures reference an unavailable class: $error")
        val show = methods.filter(_.getName == "show")
        assert(show.map(_.getReturnType).sameElements(Array(classOf[String])))
    }

end ConcreteTagClassLoadingJvmTest
