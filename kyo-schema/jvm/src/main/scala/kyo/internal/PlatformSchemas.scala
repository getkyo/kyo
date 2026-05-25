package kyo.internal

import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Currency
import kyo.*

/** JVM-only `Schema` givens for platform-bound types whose Scala.js / Scala Native
  * emulation is incomplete or absent at runtime.
  *
  * Each given delegates to `Schema.stringSchema.transform` so the wire form is a JSON
  * string (round-trip via the type's canonical string form).
  *
  * Per-type cross-platform notes:
  *   - `URI` and `Locale` live in the shared `Schema` companion (scala-java-net /
  *     scala-java-locales cover their string round-trip on JS / Native).
  *   - `URL`, `InetAddress`, `Path`, `File`, `Currency` live here: they compile cross-
  *     platform but their construction methods (`new URL`, `InetAddress.getByName`,
  *     `Paths.get`, `Currency.getInstance`) fail at runtime on JS / Native because the
  *     emulation layers omit the supporting registries / system resources.
  *
  * Import as `import kyo.internal.PlatformSchemas.given` at the use site.
  */
object PlatformSchemas:

    /** Schema for java.net.URL — encoded as the URL string; decode routes through URI to avoid the deprecated `new URL(String)`. */
    given urlSchema: Schema[URL] =
        Schema.stringSchema.transform[URL](s => new URI(s).toURL)(_.toString)

    /** Schema for java.net.InetAddress — encoded as the host-address string (IPv4 dotted-quad or IPv6 numeric form). */
    given inetAddressSchema: Schema[InetAddress] =
        Schema.stringSchema.transform[InetAddress](InetAddress.getByName)(_.getHostAddress)

    /** Schema for java.nio.file.Path — encoded as the path's string form via the default filesystem. */
    given pathSchema: Schema[Path] =
        Schema.stringSchema.transform[Path](s => Paths.get(s))(_.toString)

    /** Schema for java.io.File — encoded as the file's path string. */
    given fileSchema: Schema[File] =
        Schema.stringSchema.transform[File](new File(_))(_.getPath)

    /** Schema for java.util.Currency — encoded as an ISO 4217 three-letter currency code. */
    given currencySchema: Schema[Currency] =
        Schema.stringSchema.transform[Currency](Currency.getInstance)(_.getCurrencyCode)

end PlatformSchemas
