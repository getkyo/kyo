package kyo.lang.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import metaconfig.Configured
import org.scalafmt.Formatted
import org.scalafmt.Scalafmt
import org.scalafmt.Versions
import org.scalafmt.config.ScalafmtConfig
import sbt.MessageOnlyException
import sbt.util.Logger
import scala.io.Source

/** Formats `.kyo` sources with the language's pinned profile, in place or in check mode.
  *
  * The profile is the `kyo-lang/scalafmt.conf` resource of this plugin, and the formatter is the scalafmt-core on the plugin's
  * classpath: a project's own `.scalafmt.conf` plays no part, so every `.kyo` source has one layout wherever it is compiled (kyo-lang's
  * design, canonical syntax). A source scalafmt cannot parse is left as written and reported; the compiler reports the syntax error.
  */
private[sbt] object Formatter {

    /** The pinned profile, read once. */
    lazy val style: ScalafmtConfig = {
        val stream = getClass.getResourceAsStream("/kyo-lang/scalafmt.conf")
        if (stream == null) sys.error("kyo-lang: the formatting profile is missing from the plugin")
        val text =
            try Source.fromInputStream(stream, "UTF-8").mkString
            finally stream.close()
        // The profile names no version: it is formatted by the scalafmt-core this plugin carries, whichever that is.
        Scalafmt.parseHoconConfig(s"version = ${Versions.version}\n" + text) match {
            case Configured.Ok(style)    => style
            case Configured.NotOk(error) => sys.error(s"kyo-lang: the formatting profile cannot be read: $error")
        }
    }

    /** The source in the language's format, or the parse error scalafmt reports. The name scalafmt sees ends in `.scala`, so the
      * profile's dialect applies without a file override.
      */
    def format(source: String): Either[String, String] =
        Scalafmt.format(source, style, Set.empty, "Source.scala") match {
            case Formatted.Success(formatted) => canonical(formatted)
            case Formatted.Failure(error)     => Left(error.getMessage)
        }

    /** The canonical-syntax check the formatter cannot normalize: one statement per line (S03). A semicolon between statements has
      * no semantics-preserving rewrite the formatter applies, so the source is reported rather than reshaped.
      */
    private def canonical(formatted: String): Either[String, String] = {
        import scala.meta._
        implicit val dialect: Dialect = dialects.Scala3
        Input.String(formatted).tokenize match {
            case Tokenized.Success(tokens) =>
                tokens.collectFirst { case token: Token.Semicolon => token.pos.startLine + 1 } match {
                    case Some(line) => Left(s"one statement per line: a semicolon separates statements at line $line")
                    case None       => Right(formatted)
                }
            case Tokenized.Error(_, message, _) => Left(message)
        }
    }

    /** Rewrites every source that is not in the language's format; a source that does not parse, or that is outside the canonical
      * syntax in a way the formatter does not rewrite, is reported and left as written.
      */
    def write(sources: Seq[File], log: Logger): Unit =
        sources.foreach { file =>
            val original = read(file)
            format(original) match {
                case Right(formatted) if formatted != original =>
                    Files.write(file.toPath, formatted.getBytes(StandardCharsets.UTF_8))
                    log.info(s"kyo-lang: formatted $file")
                case Right(_)    => ()
                case Left(error) => log.warn(s"kyo-lang: $file is not formatted: $error")
            }
        }

    /** Fails when a source is not in the language's format, naming each one. */
    def check(sources: Seq[File], log: Logger): Unit = {
        val unformatted = sources.filter { file =>
            val original = read(file)
            format(original) match {
                case Right(formatted) => formatted != original
                case Left(error)      =>
                    log.warn(s"kyo-lang: $file is not formatted: $error")
                    false
            }
        }
        if (unformatted.nonEmpty)
            throw new MessageOnlyException(
                s"kyo-lang: ${unformatted.size} .kyo source(s) are not formatted; run kyoFormat:\n" +
                    unformatted.map(file => "  " + file.getPath).mkString("\n")
            )
    }

    private def read(file: File): String = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
}
