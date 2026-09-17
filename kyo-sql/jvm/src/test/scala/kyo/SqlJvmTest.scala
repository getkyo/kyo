package kyo

class SqlJvmTest extends Test:

    "public SQL methods have resolvable JVM signatures" in {
        val names = Seq(
            "kyo.Sql",
            "kyo.Sql$",
            "kyo.Sql$Table",
            "kyo.Sql$Nested$Builder",
            "kyo.Sql$Lateral$Builder",
            "kyo.Sql$ValuesFrom$Builder"
        )
        val methods = names.flatMap { name =>
            try Class.forName(name, false, getClass.getClassLoader).getDeclaredMethods.toSeq
            catch case error: LinkageError => fail(s"$name method signatures reference an unavailable class: $error")
        }
        val columns = methods.filter(_.getName == "jsonColumn")
        assert(columns.map(_.getReturnType.getName) == Seq.fill(4)("kyo.SqlSchema$Column"))
    }

end SqlJvmTest
