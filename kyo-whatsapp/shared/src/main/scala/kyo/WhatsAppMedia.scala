package kyo

import kyo.internal.whatsapp.Codec as WhatsAppCodec
import kyo.internal.whatsapp.Graph

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
      * unrepresentable (the Cloud API rejects both).
      */
    sealed trait Source derives CanEqual
    object Source:
        final case class ById(id: WhatsAppId.MediaId) extends Source derives CanEqual
        final case class ByLink(link: HttpUrl)        extends Source derives CanEqual
    end Source

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

    /** The answer of `resolveUrl`: the pre-signed url and the stored media metadata.
      *
      * The url's query (`hash`, `ext` and the rest) authorizes the download for about five minutes. `url` holds it in full, since
      * `downloadFrom` needs it, but `toString` renders the url without its query, followed by `?<redacted>`, so logging a `MediaInfo`
      * does not log the signature.
      */
    final case class MediaInfo(id: WhatsAppId.MediaId, url: HttpUrl, mimeType: String, sha256: String, fileSize: ByteSize)
        derives CanEqual:
        override def toString: String =
            val shownUrl = url.copy(rawQuery = Absent).full + url.rawQuery.fold("")(_ => "?<redacted>")
            s"MediaInfo(${id.value},$shownUrl,$mimeType,$sha256,${fileSize.show})"
        end toString
    end MediaInfo

    object MediaInfo:
        inline given Schema[MediaInfo] =
            compiletime.error("WhatsAppMedia.MediaInfo has no Schema: kyo-whatsapp decodes the Cloud API's payloads itself")
    end MediaInfo

    /** Uploads a media asset with a three-part multipart POST (`messaging_product`, `type` and `file`) and answers the assigned id. The
      * file part's name is `filename`, or a name from the media type when absent.
      */
    def upload(bytes: Span[Byte], mediaType: MediaType, filename: Maybe[String] = Absent)(
        using Frame
    ): WhatsAppId.MediaId < (Async & Abort[WhatsAppUploadFailure] & Env[WhatsApp]) =
        Env.use[WhatsApp] { client =>
            val c     = client.config
            val url   = WhatsAppConfig.versioned(c, s"${c.phoneNumberId.value}/media")
            val route = HttpRoute.postRaw(s"${c.apiVersion}/${c.phoneNumberId.value}/media")
                .request(_.bodyMultipart)
                .response(_.bodyBinary)
            val parts = Seq(
                HttpRequest.Part("messaging_product", Absent, Absent, Span.from("whatsapp".getBytes("UTF-8"))),
                HttpRequest.Part("type", Absent, Absent, Span.from(mediaType.mime.getBytes("UTF-8"))),
                HttpRequest.Part("file", Present(filename.getOrElse(defaultName(mediaType))), Present(mediaType.mime), bytes)
            )
            val request = HttpRequest.postRaw(url).addField("body", parts).addHeader("Authorization", s"Bearer ${c.token.value}")
            Graph.call[WhatsAppUploadFailure](client, UploadMethod, url)(_.sendWith(route, request)(identity))
                .map(body => Abort.get(WhatsAppCodec.decodeMediaId(UploadMethod, body)))
        }

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
            if WhatsAppConfig.absoluteProblemOf(info.url).nonEmpty then Abort.fail(WhatsAppRefusedUrlException(DownloadFromMethod))
            else
                val headers = WhatsApp.bearer(client.config) :+ ("User-Agent" -> "kyo-whatsapp")
                Graph.call[WhatsAppDownloadFromFailure](client, DownloadFromMethod, info.url) { _ =>
                    HttpClient.getBinaryResponse(info.url, headers = headers, failOnError = false)
                }
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
