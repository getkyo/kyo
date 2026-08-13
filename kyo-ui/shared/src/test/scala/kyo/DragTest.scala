package kyo

import Drag.*

class DragTest extends kyo.test.Test[Any]:

    private def mediaType(value: String): MediaType =
        MediaType.parse(value).get

    private def mediaTypePattern(value: String): MediaTypePattern =
        MediaTypePattern.parse(value).get

    private def text(representations: (String, String)*): Item.Text =
        Item.Text(representations.iterator.map((media, value) => mediaType(media) -> value).toMap)

    private def assertConstructorRejected[A: Schema](json: String)(using kyo.test.AssertScope): Unit =
        Json.decode[A](json) match
            case Result.Failure(_: ConstructorRejectedException) => ()
            case other                                           => fail(s"expected constructor rejection, got $other")

    "MediaType" - {
        "parses and renders canonical exact media types" in {
            assert(MediaType.parse("  Application/Vnd.Kyo+Json  ").map(_.render) == Present("application/vnd.kyo+json"))
            assert(MediaType.parse("text/plain").map(_.render) == Present("text/plain"))
        }

        "rejects invalid exact media types" in {
            Chunk(
                null,
                "",
                "   ",
                "text",
                "/plain",
                "text/",
                "text/plain/extra",
                "text/*",
                "*/plain",
                "text/(plain)",
                "text/pläin"
            ).foreach(value => assert(MediaType.parse(value) == Absent))
        }

        "uses a normalized scalar schema and rejects invalid decoding" in {
            val mediaType = MediaType.parse(" Text/Plain ").getOrElse(fail("valid media type did not parse"))
            assert(Json.encode(mediaType) == "\"text/plain\"")
            assert(Json.decode[MediaType]("\" APPLICATION/JSON \"").map(_.render) == Result.succeed("application/json"))
            Json.decode[MediaType]("\"text/*\"") match
                case Result.Failure(error: ConstructorRejectedException) => assert(error.typeName == "Drag.MediaType")
                case other                                               => fail(s"expected constructor rejection, got $other")
        }

        "is statically distinct from MediaTypePattern and String" in {
            typeCheckFailure("""val value: Drag.MediaType = Drag.MediaTypePattern.parse("text/plain").get""")
            typeCheckFailure("""val value: Drag.MediaType = "text/plain"""")
        }
    }

    "MediaTypePattern" - {
        "parses exact and concrete wildcard patterns canonically" in {
            assert(MediaTypePattern.parse(" Text/Plain ").map(_.render) == Present("text/plain"))
            assert(MediaTypePattern.parse(" IMAGE/* ").map(_.render) == Present("image/*"))
        }

        "rejects invalid patterns" in {
            Chunk(
                null,
                "",
                "   ",
                "image",
                "/png",
                "image/",
                "image/png/extra",
                "*/*",
                "*/png",
                "image/**",
                "image/p*ng",
                "imäge/*"
            ).foreach(value => assert(MediaTypePattern.parse(value) == Absent))
        }

        "matches exact media types without crossing main types" in {
            val exact    = MediaTypePattern.parse("image/png").getOrElse(fail("valid exact pattern did not parse"))
            val wildcard = MediaTypePattern.parse("image/*").getOrElse(fail("valid wildcard pattern did not parse"))
            val png      = MediaType.parse("image/png").getOrElse(fail("valid png media type did not parse"))
            val jpeg     = MediaType.parse("image/jpeg").getOrElse(fail("valid jpeg media type did not parse"))
            val text     = MediaType.parse("text/plain").getOrElse(fail("valid text media type did not parse"))
            assert(exact.matches(png))
            assert(!exact.matches(jpeg))
            assert(wildcard.matches(png))
            assert(wildcard.matches(jpeg))
            assert(!wildcard.matches(text))
            assert(MediaTypePattern.exact(png) == exact)
        }

        "uses a normalized scalar schema and rejects invalid decoding" in {
            val pattern = MediaTypePattern.parse(" Image/* ").getOrElse(fail("valid pattern did not parse"))
            assert(Json.encode(pattern) == "\"image/*\"")
            assert(Json.decode[MediaTypePattern]("\" TEXT/PLAIN \"").map(_.render) == Result.succeed("text/plain"))
            Json.decode[MediaTypePattern]("\"*/*\"") match
                case Result.Failure(error: ConstructorRejectedException) => assert(error.typeName == "Drag.MediaTypePattern")
                case other                                               => fail(s"expected constructor rejection, got $other")
        }
    }

    "nested media type schemas" - {
        "validate and normalize FileMeta media types" in {
            val file = FileMeta("token", "file.txt", mediaType("text/plain"), ByteSize.Zero, Instant.Epoch)
            val json = Json.encode(file)
            assert(Json.decode[FileMeta](json.replace("text/plain", " TEXT/PLAIN ")).map(_.mediaType.render) ==
                Result.succeed("text/plain"))
            assertConstructorRejected[FileMeta](json.replace("text/plain", "text/*"))
        }

        "validate and normalize Item.Text representation keys" in {
            val json = Json.encode[Item](text("text/plain" -> "value"))
            Json.decode[Item](json.replace("text/plain", " TEXT/PLAIN ")) match
                case Result.Success(Item.Text(representations)) =>
                    assert(representations.keySet.map(_.render) == Set("text/plain"))
                case other => fail(s"expected normalized text item, got $other")
            end match
            assertConstructorRejected[Item](json.replace("text/plain", "text/*"))
        }

        "validate and normalize Accept media type patterns" in {
            val json = Json.encode(Accept.types(mediaTypePattern("image/*")))
            assert(Json.decode[Accept](json.replace("image/*", " IMAGE/* ")).map(_.mediaTypes.map(_.render)) ==
                Result.succeed(Set("image/*")))
            assertConstructorRejected[Accept](json.replace("image/*", "*/*"))
        }

        "validate nested Source and Target media values" in {
            val sourceJson = Json.encode(Source("source", Chunk(text("text/plain" -> "value"))))
            Json.decode[Source](sourceJson.replace("text/plain", " TEXT/PLAIN ")) match
                case Result.Success(Source(_, Chunk(Item.Text(representations)), _, _, _, _, _)) =>
                    assert(representations.keySet.map(_.render) == Set("text/plain"))
                case other => fail(s"expected normalized source, got $other")
            end match
            assertConstructorRejected[Source](sourceJson.replace("text/plain", "text/*"))

            val targetJson = Json.encode(Target("target", Accept.types(mediaTypePattern("image/*"))))
            assert(Json.decode[Target](targetJson.replace("image/*", " IMAGE/* ")).map(_.accepts.mediaTypes.map(_.render)) ==
                Result.succeed(Set("image/*")))
            assertConstructorRejected[Target](targetJson.replace("image/*", "*/*"))
        }
    }

    "AllowedOperations" - {
        "constants contain exactly their named operations" in {
            assert(AllowedOperations.none == AllowedOperations(Set.empty))
            assert(AllowedOperations.copy == AllowedOperations(Set(Operation.Copy)))
            assert(AllowedOperations.move == AllowedOperations(Set(Operation.Move)))
            assert(AllowedOperations.link == AllowedOperations(Set(Operation.Link)))
            assert(AllowedOperations.all == AllowedOperations(Set(Operation.Copy, Operation.Move, Operation.Link)))
        }

        "allows only contained operations" in {
            assert(!AllowedOperations.none.allows(Operation.Copy))
            assert(AllowedOperations.copy.allows(Operation.Copy))
            assert(!AllowedOperations.copy.allows(Operation.Move))
            assert(AllowedOperations.all.allows(Operation.Copy))
            assert(AllowedOperations.all.allows(Operation.Move))
            assert(AllowedOperations.all.allows(Operation.Link))
        }
    }

    "Accept" - {
        "matches an exact text representation" in {
            val accept          = Accept.types(mediaTypePattern("application/x-card"), mediaTypePattern("text/plain"))
            val result: Boolean = accept.accepts(text("application/x-card" -> "card"))
            assert(result)
            assert(!accept.accepts(text("application/x-other" -> "other")))
        }

        "stores and matches canonical typed media values" in {
            val application = mediaTypePattern("  APPLICATION/X-CARD  ")
            val textPlain   = mediaTypePattern(" Text/Plain ")
            val accept      = Accept.types(application, textPlain)
            assert(accept.mediaTypes.map(_.render) == Set("application/x-card", "text/plain"))
            assert(accept.accepts(text(" APPLICATION/X-CARD " -> "card")))
        }

        "matches a type wildcard" in {
            val accept = Accept.types(mediaTypePattern("image/*"))
            assert(accept.accepts(text("image/png" -> "png")))
            assert(!accept.accepts(text("text/plain" -> "text")))
        }

        "rejects text with no transfer representations" in {
            assert(!Accept().accepts(Item.Text(Map.empty)))
        }

        "unfiltered acceptance accepts typed exact offered media types" in {
            assert(Accept().accepts(text("application/vnd.kyo+json" -> "valid")))
        }

        "requires typed media values at domain construction sites" in {
            typeCheckFailure("""Drag.Accept.types("text/plain")""")
            typeCheckFailure("""Drag.Accept(mediaTypes = Set("text/plain"))""")
            typeCheckFailure("""Drag.Item.Text(Map("text/plain" -> "value"))""")
            typeCheckFailure("""Drag.FileMeta("token", "file", "text/plain", ByteSize.Zero, Instant.Epoch)""")
        }

        "does not enforce item count for a single item" in {
            val accept = Accept(maxItems = Present(0))
            assert(accept.accepts(text("text/plain" -> "text")))
        }

        "accepts URI items as text/uri-list" in {
            assert(Accept.types(mediaTypePattern("text/uri-list")).accepts(Item.Uri("https://getkyo.io")))
            assert(!Accept.types(mediaTypePattern("text/plain")).accepts(Item.Uri("https://getkyo.io")))
        }

        "checks file media type and size" in {
            val file = Item.File(FileMeta("token", "card.bin", mediaType(" APPLICATION/X-CARD "), 65.kib, Instant.Epoch))
            val accept = Accept(
                mediaTypes = Set(mediaTypePattern("application/x-card")),
                operations = AllowedOperations.copy,
                maxFileSize = Present(64.kib)
            )
            assert(accept.operations == AllowedOperations.copy)
            assert(!accept.accepts(file))
            assert(!Accept.types(mediaTypePattern("application/x-other")).accepts(file))
            assert(Accept.types(mediaTypePattern("application/*")).accepts(file))
        }

        "accepts a file exactly at the size limit" in {
            val file   = Item.File(FileMeta("token", "card.bin", mediaType("application/x-card"), 64.kib, Instant.Epoch))
            val accept = Accept(mediaTypes = Set(mediaTypePattern("application/x-card")), maxFileSize = Present(64.kib))
            assert(accept.accepts(file))
        }

        "checks directory acceptance" in {
            val directory = Item.Directory("token", "assets")
            assert(!Accept().accepts(directory))
            assert(Accept(directories = true).accepts(directory))
        }
    }

    "Move" - {
        "preserves selected key order and stable anchor" in {
            val move = Move(
                keys = Chunk("2", "5"),
                source = Location("backlog"),
                destination = Location("done"),
                anchor = Present("8"),
                position = Position.Before,
                operation = Operation.Move
            )
            assert(move.keys == Chunk("2", "5"))
            assert(move.source == Location("backlog"))
            assert(move.destination == Location("done"))
            assert(move.anchor == Present("8"))
            assert(move.position == Position.Before)
            assert(move.operation == Operation.Move)
        }
    }

end DragTest
