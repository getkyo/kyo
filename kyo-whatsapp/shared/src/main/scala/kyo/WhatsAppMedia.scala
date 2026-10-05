package kyo

import kyo.internal.charset.Utf8
import kyo.internal.whatsapp.Codec as WhatsAppCodec
import kyo.internal.whatsapp.Graph
import kyo.internal.whatsapp.Transformers
import kyo.schema.omit
import kyo.schema.rename
import kyo.schema.transform
import kyo.schema.untagged

/** The Cloud API's media verbs, on their own object because media has its own host and lifecycle: an upload answers an id, the id
  * resolves to a short-lived pre-signed url on Meta's media host, and the bytes are fetched from that url.
  *
  * Operations, each requiring the client (`Env[WhatsApp]`) and failing with its own trait:
  *   - `upload` POSTs a multipart form to the phone number's media endpoint and answers the assigned `WhatsAppId.MediaId`;
  *   - `resolveUrl` GETs the media id and answers a `MediaInfo`: the pre-signed url and the stored metadata;
  *   - `download` is `resolveUrl` then `downloadFrom`;
  *   - `downloadFrom` GETs the bytes of an already resolved `MediaInfo`;
  *   - `delete` DELETEs an uploaded media id.
  *
  * IMPORTANT: the module sends only to a media url that is an absolute http or https url on a host, written in printable ASCII: any other,
  * such as a relative url a caller's base url would resolve or a unix-socket url, is refused with [[kyo.WhatsAppRefusedUrlException]]
  * before a byte is sent, since the download carries the access token. A media id is placed in a url path, so one that is not a single
  * segment of `A-Z a-z 0-9 . _ -` is refused the same way.
  *
  * @see
  *   [[kyo.WhatsApp]] the client these verbs require
  */
object WhatsAppMedia:

    /** A media reference: either a previously uploaded asset id or a public link. A sealed union so "both id and link" and "neither" are
      * unrepresentable (the Cloud API rejects both). On the wire it is the `id` or the `link` key of the media object holding it.
      */
    @untagged
    sealed trait Source derives CanEqual
    object Source:
        final case class ById(id: WhatsAppId.MediaId)                       extends Source derives CanEqual
        final case class ByLink(@transform(Transformers.Url) link: HttpUrl) extends Source derives CanEqual
        given Schema[Source] = Schema.derived[Source]
    end Source

    /** Meta's media object naming only its source: a sticker message, and a template's or an interactive header's image or video. */
    final case class Link(source: Source) derives CanEqual
    object Link:
        given Schema[Link] = Schema[Link].flatten(_.source)

    /** Meta's media object of an audio message: its source, and `voice` for a voice note (an OGG/Opus file played as one). */
    final case class Audio(source: Source, @omit(omit.WhenDefault) voice: Boolean = false) derives CanEqual
    object Audio:
        given Schema[Audio] = Schema[Audio].flatten(_.source)

    /** Meta's media object of an image or video message: its source and an optional caption. */
    final case class Captioned(source: Source, caption: Maybe[String] = Absent) derives CanEqual
    object Captioned:
        given Schema[Captioned] = Schema[Captioned].flatten(_.source)

    /** Meta's media object of a document: its source, an optional caption and the file name the recipient sees. */
    final case class Document(source: Source, caption: Maybe[String] = Absent, filename: Maybe[String] = Absent) derives CanEqual
    object Document:
        given Schema[Document] = Schema[Document].flatten(_.source)

    /** The typed MIME vocabulary of uploads. Each case carries its `mime` string; `Other` is the escape for a MIME the enumeration does not
      * name.
      */
    sealed trait MediaType derives CanEqual:
        def mime: String

    object MediaType:
        case object ImageJpeg extends MediaType:
            def mime = "image/jpeg"
        case object ImagePng extends MediaType:
            def mime = "image/png"
        case object AudioAac extends MediaType:
            def mime = "audio/aac"
        case object AudioAmr extends MediaType:
            def mime = "audio/amr"
        case object AudioMp3 extends MediaType:
            def mime = "audio/mpeg"
        case object AudioMp4 extends MediaType:
            def mime = "audio/mp4"
        case object AudioOgg extends MediaType:
            def mime = "audio/ogg"
        case object VideoMp4 extends MediaType:
            def mime = "video/mp4"
        case object Video3gp extends MediaType:
            def mime = "video/3gp"
        case object DocumentPdf extends MediaType:
            def mime = "application/pdf"
        case object DocumentText extends MediaType:
            def mime = "text/plain"
        case object StickerWebp extends MediaType:
            def mime = "image/webp"
        final case class Other(mime: String) extends MediaType
    end MediaType

    /** The answer of `resolveUrl`: the pre-signed url and the stored media metadata, as Meta's JSON for it. The url is a
      * [[kyo.WhatsAppMediaUrl]], whose rendering hides the query that authorizes the download. `fileSize` reads Meta's `file_size`
      * whether it arrives as a number or as a string of digits.
      */
    final case class MediaInfo(
        id: WhatsAppId.MediaId,
        url: WhatsAppMediaUrl,
        @rename("mime_type") mimeType: String,
        sha256: String,
        @rename("file_size") @transform(Transformers.FileSize) fileSize: ByteSize
    ) derives CanEqual

    object MediaInfo:
        given (using Frame): Schema[MediaInfo] = Schema.derived[MediaInfo]

    /** Uploads a media asset with a three-part multipart POST (`messaging_product`, `type` and `file`) and answers the assigned id. The
      * file part's name is `filename`, or a name from the media type when absent.
      *
      * The filename and the media type's mime are written into the file part's head, so each must be printable ASCII without `"` or `\`;
      * either one that is not fails with [[kyo.WhatsAppRefusedPartException]] before anything is sent.
      */
    def upload(bytes: Span[Byte], mediaType: MediaType, filename: Maybe[String] = Absent)(
        using Frame
    ): WhatsAppId.MediaId < (Async & Abort[WhatsAppUploadFailure] & Env[WhatsApp]) =
        val name = filename.getOrElse(defaultName(mediaType))
        if !fitsPartHead(name) then Abort.fail(WhatsAppRefusedPartException(UploadMethod, WhatsAppRefusedPartException.Field.Filename))
        else if !fitsPartHead(mediaType.mime) then
            Abort.fail(WhatsAppRefusedPartException(UploadMethod, WhatsAppRefusedPartException.Field.MediaType))
        else
            Env.use[WhatsApp] { client =>
                val c     = client.config
                val url   = WhatsAppConfig.versioned(c, s"${c.phoneNumberId.value}/media")
                val route = HttpRoute.postRaw(s"${c.apiVersion}/${c.phoneNumberId.value}/media")
                    .request(_.bodyMultipart)
                    .response(_.bodyBinary)
                val parts: Seq[HttpRequest.Part] = Chunk(
                    HttpRequest.Part("messaging_product", Absent, Absent, Utf8.encode("whatsapp")),
                    HttpRequest.Part("type", Absent, Absent, Utf8.encode(mediaType.mime)),
                    HttpRequest.Part("file", Present(name), Present(mediaType.mime), bytes)
                )
                val request = HttpRequest.postRaw(url).addField("body", parts).addHeader("Authorization", WhatsApp.bearerValue(c))
                Graph.call[WhatsAppUploadFailure](client, UploadMethod, url)(_.sendWith(route, request)(identity))
                    .map(body => Abort.get(WhatsAppCodec.decodeMediaId(UploadMethod, body)))
            }
        end if
    end upload

    /** kyo-http writes a part's filename and content type into the part head as they are, and encodes the head as US-ASCII: a quote or a
      * backslash would end or escape the quoted filename, a line break would end the header, and any other character would be replaced.
      */
    private def fitsPartHead(value: String): Boolean =
        value.forall(c => c >= ' ' && c <= '~' && c != '"' && c != '\\')

    /** Resolves a media id to its pre-signed url (valid for about five minutes) and its stored metadata. */
    def resolveUrl(id: WhatsAppId.MediaId)(using Frame): MediaInfo < (Async & Abort[WhatsAppResolveUrlFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            mediaUrl(client.config, id, ResolveUrlMethod).map { url =>
                Graph.call[WhatsAppResolveUrlFailure](client, ResolveUrlMethod, url) { _ =>
                    HttpClient.getBinaryResponse(url, headers = WhatsApp.bearer(client.config), failOnError = false)
                }.map(body => Abort.get(WhatsAppCodec.decodeMediaInfo(ResolveUrlMethod, body)))
            }
        }

    /** Resolves the url then downloads the bytes, as `resolveUrl(id).map(downloadFrom)`. Fails with a failure of either step. */
    def download(id: WhatsAppId.MediaId)(using Frame): Span[Byte] < (Async & Abort[WhatsAppDownloadFailure] & Env[WhatsApp]) =
        resolveUrl(id).map(downloadFrom)

    /** Downloads the bytes of a resolved `MediaInfo` from its pre-signed url, with the bearer token and a `User-Agent`. A url that is not
      * an absolute http or https url on a host is refused before anything is sent.
      */
    def downloadFrom(info: MediaInfo)(using Frame): Span[Byte] < (Async & Abort[WhatsAppDownloadFromFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            val url = info.url.value
            if WhatsAppConfig.absoluteProblemOf(url).nonEmpty then Abort.fail(WhatsAppRefusedUrlException(DownloadFromMethod))
            else
                val headers = WhatsApp.bearer(client.config).add("User-Agent", "kyo-whatsapp")
                Graph.call[WhatsAppDownloadFromFailure](client, DownloadFromMethod, url) { _ =>
                    HttpClient.getBinaryResponse(url, headers = headers, failOnError = false)
                }
            end if
        }

    /** Deletes an uploaded media asset. */
    def delete(id: WhatsAppId.MediaId)(using Frame): Unit < (Async & Abort[WhatsAppDeleteFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            mediaUrl(client.config, id, DeleteMethod).map { url =>
                Graph.call[WhatsAppDeleteFailure](client, DeleteMethod, url) { _ =>
                    HttpClient.deleteBinaryResponse(url, headers = WhatsApp.bearer(client.config), failOnError = false)
                }.map(body => Abort.get(WhatsAppCodec.decodeSuccess(DeleteMethod, body)))
            }
        }

    private[kyo] inline val UploadMethod       = "upload"
    private[kyo] inline val ResolveUrlMethod   = "resolveUrl"
    private[kyo] inline val DownloadFromMethod = "downloadFrom"
    private[kyo] inline val DeleteMethod       = "delete"

    /** The media id's url under the version, or a refusal when the id is not one path segment. */
    private def mediaUrl(c: WhatsAppConfig, id: WhatsAppId.MediaId, method: String)(using
        Frame
    ): HttpUrl < Abort[WhatsAppRefusedUrlException] =
        val v = id.value
        if v.nonEmpty && v != "." && v != ".." && v.forall(isSegmentChar) then WhatsAppConfig.versioned(c, v)
        else Abort.fail(WhatsAppRefusedUrlException(method))
    end mediaUrl

    private def isSegmentChar(c: Char): Boolean =
        (c >= 'a' && c <= 'z') ||
            (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'

    private def defaultName(mediaType: MediaType): String = mediaType match
        case MediaType.ImageJpeg   => "file.jpg"
        case MediaType.ImagePng    => "file.png"
        case MediaType.VideoMp4    => "file.mp4"
        case MediaType.AudioMp3    => "file.mp3"
        case MediaType.DocumentPdf => "file.pdf"
        case _                     => "file.bin"

end WhatsAppMedia
