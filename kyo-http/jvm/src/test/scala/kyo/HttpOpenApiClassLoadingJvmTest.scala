package kyo

class HttpOpenApiClassLoadingJvmTest extends BaseHttpTest:

    "public OpenAPI methods have resolvable JVM signatures" in {
        val methods = Seq("kyo.HttpOpenApi", "kyo.HttpOpenApi$").flatMap { name =>
            try Class.forName(name, false, getClass.getClassLoader).getDeclaredMethods.toSeq
            catch case error: LinkageError => fail(s"$name method signatures reference an unavailable class: $error")
        }
        val toJson = methods.filter(_.getName == "toJson")
        assert(toJson.map(_.getReturnType.getName) == Seq("java.lang.String", "java.lang.String"))
    }

end HttpOpenApiClassLoadingJvmTest
