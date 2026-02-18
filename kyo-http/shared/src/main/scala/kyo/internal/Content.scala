package kyo.internal

import kyo.*

sealed private[kyo] trait Content derives CanEqual
private[kyo] object Content:
    sealed trait Input  extends Content
    sealed trait Output extends Content

    case class Json(schema: Schema[Any])                      extends Input with Output
    case object Text                                          extends Input with Output
    case object Binary                                        extends Input with Output
    case object ByteStream                                    extends Input with Output
    case class Ndjson(schema: Schema[Any], emitTag: Tag[Any]) extends Input with Output

    case class Form(schema: Schema[Any]) extends Input
    case object Multipart                extends Input
    case object MultipartStream          extends Input

    case class Sse(schema: Schema[Any], emitTag: Tag[Any]) extends Output
end Content
