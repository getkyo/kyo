package kyo

class SqlClientJvmTest extends Test:

    "public SQL client methods have resolvable JVM signatures" in {
        val methods = Seq("kyo.SqlClient", "kyo.SqlClient$").flatMap { name =>
            try Class.forName(name, false, getClass.getClassLoader).getDeclaredMethods.toSeq
            catch case error: LinkageError => fail(s"$name method signatures reference an unavailable class: $error")
        }
        val config = methods.filter(_.getName == "config")
        assert(config.map(_.getReturnType.getName) == Seq("kyo.SqlConfig"))
    }

end SqlClientJvmTest
