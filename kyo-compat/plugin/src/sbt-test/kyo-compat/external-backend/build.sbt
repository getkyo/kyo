// Scripted test: externally-published backend coordinates.
//
// A CompatBackendAxis can carry its own {organization, artifactName, version},
// so a backend published OUTSIDE the kyo repo resolves to its own Maven
// coordinates instead of io.getkyo:kyo-compat-<name>. Declared via
// CompatBackendAxis.external(...). Built-in backends in the same matrix keep
// pulling io.getkyo:kyo-compat-<name> % compatKyoVersion.

ThisBuild / scalaVersion     := "3.3.4"
ThisBuild / compatKyoVersion := "STUB-COMPAT-VERSION"

// An externally-published backend: custom org, artifact, and version.
lazy val AcmeLib = CompatBackendAxis.external(
    name = "acme",
    idSuffix = "Acme",
    directorySuffix = "-acme",
    supportedPlatforms = Set("jvm"),
    organization = "com.acme",
    artifactName = "kyo-compat-acme",
    version = "9.9.9-EXTERNAL"
)

// Matrix with the external Acme backend plus the built-in Kyo backend.
lazy val myLib = (projectMatrix in file("my-lib"))
    .settings(
        organization := "com.example",
        version      := "0.1.0-TEST"
    )
    .compatLibrary(AcmeLib, KyoLib)(VirtualAxis.jvm)(Seq("3.3.4"))

// --------------------------------------------------------------------
// Assertion task keys
// --------------------------------------------------------------------

val checkExternalCoords = taskKey[Unit](
    "verify myLibAcme pulls com.acme:kyo-compat-acme:9.9.9-EXTERNAL and NOT io.getkyo"
)
val checkBuiltinCoords = taskKey[Unit](
    "verify myLibKyo still pulls io.getkyo:kyo-compat-kyo:<compatKyoVersion>"
)

checkExternalCoords := {
    val ext = sbt.Project.extract(Keys.state.value)
    val acmeProj = myLib.get(AcmeLib).getOrElse(
        sys.error("Acme backend was not opted in to the matrix")
    ).jvm
    val deps = ext.get(acmeProj / Keys.libraryDependencies)
    val external = deps.filter { mid =>
        mid.organization == "com.acme" && mid.name == "kyo-compat-acme"
    }
    if (!external.exists(_.revision == "9.9.9-EXTERNAL"))
        sys.error(
            s"myLibAcme must pull com.acme:kyo-compat-acme:9.9.9-EXTERNAL. " +
                s"Actual libraryDependencies: $deps"
        )
    val leaked = deps.filter { mid =>
        mid.organization == "io.getkyo" && mid.name == "kyo-compat-acme"
    }
    if (leaked.nonEmpty)
        sys.error(
            s"myLibAcme must NOT pull io.getkyo:kyo-compat-acme (it is externally published). " +
                s"Leaked entries: $leaked"
        )
    println(
        "checkExternalCoords OK; myLibAcme -> " +
            external.map(m => s"${m.organization}:${m.name}:${m.revision}").mkString(", ")
    )
}

checkBuiltinCoords := {
    val ext  = sbt.Project.extract(Keys.state.value)
    val deps = ext.get(myLib.kyo.jvm / Keys.libraryDependencies)
    val builtin = deps.filter { mid =>
        mid.organization == "io.getkyo" && mid.name == "kyo-compat-kyo"
    }
    if (!builtin.exists(_.revision == "STUB-COMPAT-VERSION"))
        sys.error(
            s"myLibKyo MUST retain io.getkyo:kyo-compat-kyo:STUB-COMPAT-VERSION when Kyo is a " +
                s"built-in backend. Actual io.getkyo deps: ${deps.filter(_.organization == "io.getkyo")}"
        )
    println(
        "checkBuiltinCoords OK; myLibKyo -> " +
            builtin.map(m => s"${m.organization}:${m.name}:${m.revision}").mkString(", ")
    )
}
