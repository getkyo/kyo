package kyo.internal

import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Currency
import java.util.Locale
import kyo.*

/** JVM-only `Schema` givens for platform-bound types not portable to JS/Native.
  *
  * Each given delegates to `Schema.stringSchema.transform` so the wire form is a JSON string
  * (round-trip via the type's canonical string form). The corresponding `typeSymbol`s are
  * registered in `PlatformSymbols.primitiveSymbols` so that case classes referencing these
  * types pass the `isSerializableType` gate during `metaApply` macro expansion on JVM.
  *
  * Import as `import kyo.internal.PlatformSchemas.given` at the use site.
  */
object PlatformSchemas:

    /** Schema for java.net.URI — encoded as the URI's canonical string form. */
    given uriSchema: Schema[URI] =
        Schema.stringSchema.transform[URI](new URI(_))(_.toString)

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

    /** Schema for java.util.Locale — encoded as an IETF BCP 47 language tag. */
    given localeSchema: Schema[Locale] =
        Schema.stringSchema.transform[Locale](Locale.forLanguageTag)(_.toLanguageTag)

    /** Schema for java.util.Currency — encoded as an ISO 4217 three-letter currency code. */
    given currencySchema: Schema[Currency] =
        Schema.stringSchema.transform[Currency](Currency.getInstance)(_.getCurrencyCode)

end PlatformSchemas
