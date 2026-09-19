package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyHeader

/** Pins `Tasty.supportedTastyVersion` to the TASTy version the build's own compiler emits.
  *
  * The supported version is deliberately a hand-maintained constant: accepting a new TASTy minor is a decision taken after checking
  * the release's format for new tags. This leaf is what makes that decision unavoidable. It reads the header of a `.tasty` file this
  * module was just compiled to, so moving the build to a new Scala minor without reviewing the format fails here, naming both versions,
  * instead of surfacing later as decode failures on real classpaths.
  */
class TastyHeaderCompilerTest extends kyo.test.Test[Any]:

    "supportedTastyVersion matches the TASTy version the build compiler emits" in {
        import AllowUnsafe.embrace.danger
        val resource = "/kyo/Tasty.tasty"
        val bytes    = Maybe(classOf[Tasty.Version].getResourceAsStream(resource))
            .map(in =>
                try in.readAllBytes()
                finally in.close()
            )
            .getOrElse(fail(s"$resource not found on the test classpath"))
        TastyHeader.read(ByteView(bytes)) match
            case Result.Success(header) =>
                val emitted = Tasty.Version(header.major, header.minor, header.experimental)
                assert(
                    emitted.show == Tasty.supportedTastyVersion.show,
                    s"the build compiler (${header.toolingVersion}) emits TASTy ${emitted.show} but kyo-tasty supports " +
                        s"${Tasty.supportedTastyVersion.show}: review the TastyFormat changes and update supportedTastyVersion"
                )
            case Result.Failure(e) =>
                fail(
                    s"kyo-tasty cannot read the TASTy its own build compiler emits ($e): review the TastyFormat changes and update " +
                        "supportedTastyVersion"
                )
            case Result.Panic(t) => throw t
        end match
    }

end TastyHeaderCompilerTest
