package kyo

class LayerClassLoadingJvmTest extends kyo.test.Test[Any]:

    "public Layer methods have resolvable JVM signatures" in {
        val methods = Seq("kyo.Layer", "kyo.Layer$").flatMap { name =>
            try Class.forName(name, false, getClass.getClassLoader).getDeclaredMethods.toSeq
            catch case error: LinkageError => fail(s"$name method signatures reference an unavailable class: $error")
        }
        val empty = methods.filter(_.getName == "empty")
        assert(empty.map(_.getReturnType.getName) == Seq("kyo.Layer", "kyo.Layer"))
    }

end LayerClassLoadingJvmTest
