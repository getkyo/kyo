package kyo

import java.nio.charset.StandardCharsets

class IonCorpusTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "vendored amazon ion-tests corpus" - {

        "resources are present with upstream license and notice" in {
            for
                license <- resource("/iontestdata/LICENSE")
                notice  <- resource("/iontestdata/NOTICE")
                _ <- Kyo.foreach(IonCorpusTest.RequiredResources) { name =>
                    resource(s"/iontestdata/good/$name").map(content => assert(content.nonEmpty))
                }
            yield
                assert(license.contains("Apache License"))
                assert(notice.contains("Amazon Ion Tests"))
        }

        "decodes typed null values from iontestdata/good/allNulls.ion" in {
            resource("/iontestdata/good/allNulls.ion").map { upstream =>
                assert(upstream.contains("null.int"))
                assert(upstream.contains("null.struct"))

                val options = Ion.decode[List[Option[Int]]](upstream).getOrThrow
                assert(options.size == 14)
                assert(options.forall(_ == None))

                val maybes = Ion.decode[List[Maybe[String]]](upstream).getOrThrow
                assert(maybes.size == 14)
                assert(maybes.forall(_ == Maybe.empty))
            }
        }

        "decodes non-null scalar and container samples from iontestdata/good/nonNulls.ion" in {
            resource("/iontestdata/good/nonNulls.ion").map { upstream =>
                List("0", "0.0", "0d0", "0e0", "\"\"", "''''''", "{{}}", "{{\"\"}}", "[]", "()", "{}").foreach { ion =>
                    assert(upstream.contains(ion))
                }

                assert(Ion.decode[Int]("0").getOrThrow == 0)
                assert(Ion.decode[BigDecimal]("0.0").getOrThrow == BigDecimal("0.0"))
                assert(Ion.decode[BigDecimal]("0d0").getOrThrow == BigDecimal(0))
                assert(Ion.decode[Double]("0e0").getOrThrow == 0.0)
                assert(Ion.decode[String]("\"\"").getOrThrow == "")
                assert(Ion.decode[String]("''''''").getOrThrow == "")
                assert(Ion.decode[Span[Byte]]("{{}}").getOrThrow.toArray.isEmpty)
                assert(Ion.decode[Span[Byte]]("{{\"\"}}").getOrThrow.toArray.isEmpty)
                assert(Ion.decode[List[Int]]("[]").getOrThrow == Nil)
                assert(Ion.decode[List[Int]]("()").getOrThrow == Nil)
                assert(Ion.decode[Map[String, Int]]("{}").getOrThrow == Map.empty)
            }
        }

        "decodes integer samples from iontestdata/good/integer_values.ion and intBinary.ion" in {
            val cases = List(
                IonCorpusTest.IntCase("integer_values.ion", "0", BigInt(0)),
                IonCorpusTest.IntCase("integer_values.ion", "-0", BigInt(0)),
                IonCorpusTest.IntCase("integer_values.ion", "0x1234567890abcdef", BigInt("1234567890abcdef", 16)),
                IonCorpusTest.IntCase("integer_values.ion", "-0xFFFF", BigInt(-65535)),
                IonCorpusTest.IntCase("intBinary.ion", "0b11110000", BigInt(240)),
                IonCorpusTest.IntCase("intBinary.ion", "0B010101", BigInt(21)),
                IonCorpusTest.IntCase("intsWithUnderscores.ion", "1_2_3", BigInt(123)),
                IonCorpusTest.IntCase("intsWithUnderscores.ion", "-0b1111_0000", BigInt(-240))
            )

            Kyo.foreach(cases) { c =>
                resource(s"/iontestdata/good/${c.source}").map { content =>
                    assert(content.contains(c.ion))
                    assert(Ion.decode[BigInt](c.ion).getOrThrow == c.expected)
                }
            }.andThen(succeed)
        }

        "decodes decimal samples from iontestdata/good/decimal_values.ion" in {
            val cases = List(
                IonCorpusTest.DecimalCase("decimal_values.ion", "123456d42", BigDecimal("123456E42")),
                IonCorpusTest.DecimalCase("decimal_values.ion", "123.456d-42", BigDecimal("123.456E-42")),
                IonCorpusTest.DecimalCase("decimalsWithUnderscores.ion", "12_34.56_78", BigDecimal("1234.5678")),
                IonCorpusTest.DecimalCase("decimalsWithUnderscores.ion", "12_34.", BigDecimal("1234")),
                IonCorpusTest.DecimalCase("decimalsWithUnderscores.ion", "1_2_3_4.5_6_7_8", BigDecimal("1234.5678"))
            )

            Kyo.foreach(cases) { c =>
                resource(s"/iontestdata/good/${c.source}").map { content =>
                    assert(content.contains(c.ion))
                    assert(Ion.decode[BigDecimal](c.ion).getOrThrow == c.expected)
                }
            }.andThen(succeed)
        }

        "decodes float samples from iontestdata/good/floatSpecials.ion" in {
            for
                specialsRaw <- resource("/iontestdata/good/floatSpecials.ion")
                underscores <- resource("/iontestdata/good/floatsWithUnderscores.ion")
            yield
                val specials = Ion.decode[List[Double]](specialsRaw).getOrThrow
                assert(specials.size == 3)
                assert(specials(0).isNaN)
                assert(specials(1) == Double.PositiveInfinity)
                assert(specials(2) == Double.NegativeInfinity)

                val cases = List(
                    "12_34.56_78e0"      -> 1234.5678e0,
                    "12_34e56"           -> 1234e56,
                    "1_2_3_4.5_6_7_8E90" -> 1234.5678e90
                )
                cases.foreach { (ion, expected) =>
                    assert(underscores.contains(ion))
                    assert(Ion.decode[Double](ion).getOrThrow == expected)
                }
        }

        "decodes string and symbol samples from iontestdata/good/strings.ion and symbols.ion" in {
            for
                strings  <- resource("/iontestdata/good/strings.ion")
                symbols  <- resource("/iontestdata/good/symbols.ion")
                symEmpty <- resource("/iontestdata/good/symbolEmpty.ion")
            yield
                assert(strings.contains("'''concatenated'''"))
                assert(
                    Ion.decode[String](
                        "'''concatenated'''  ''' from '''   '''a single line'''"
                    ).getOrThrow == "concatenated from a single line"
                )

                val escaped = "\"\\0 \\a \\b \\t \\n \\f \\r \\v \\\" \\' \\? \\\\ \\/\""
                assert(Ion.decode[String](escaped).getOrThrow == "\u0000 \u0007 \b \t \n \f \r \u000b \" ' ? \\ /")

                assert(symbols.contains("bareSymbol"))
                assert(Ion.decode[String]("bareSymbol").getOrThrow == "bareSymbol")
                assert(Ion.decode[String]("'$99'").getOrThrow == "$99")

                assert(symEmpty.contains("{'':abc}"))
                assert(Ion.decode[String]("''").getOrThrow == "")
                assert(Ion.decode[String]("abc::''").getOrThrow == "")
                assert(Ion.decode[Map[String, String]]("{'':abc}").getOrThrow == Map("" -> "abc"))
        }

        "decodes blob and clob samples from iontestdata/good/blobs.ion and clobs.ion" in {
            val blob = Ion.decode[Span[Byte]](
                """{{
                  |    YSBiIGMgZCBlIGYgZyBoIGkgaiBrIGwgbSBuIG8gcCBxIHIgcyB0IHUgdiB3IHggeSB6
                  |}}""".stripMargin
            ).getOrThrow
            assert(new String(blob.toArray, StandardCharsets.UTF_8) == "a b c d e f g h i j k l m n o p q r s t u v w x y z")

            val clob = Ion.decode[Span[Byte]]("""{{"a b c d e f g h i j k l m n o p q r s t u v w x y z"}}""").getOrThrow
            assert(new String(clob.toArray, StandardCharsets.US_ASCII) == "a b c d e f g h i j k l m n o p q r s t u v w x y z")
        }

        "decodes list, sexp, and struct samples from iontestdata/good containers" in {
            assert(Ion.decode[List[Int]]("[1, 2, 3, 4, 5]").getOrThrow == List(1, 2, 3, 4, 5))
            assert(Ion.decode[List[Int]]("(1_2_3)").getOrThrow == List(123))
            assert(Ion.decode[IonCorpusTest.UpstreamStruct]("{a:b,c:42,d:{e:f,},g:3}").getOrThrow ==
                IonCorpusTest.UpstreamStruct("b", 42, Map("e" -> "f"), 3))
        }

        "accepts upstream annotation files while treating annotations as schema metadata" in {
            val annotationCases = List(
                "annotationQuotedTrue.ion"     -> "'true'::23",
                "annotationQuotedFalse.ion"    -> "'false'::23",
                "annotationQuotedNan.ion"      -> "'nan'::23",
                "annotationQuotedNull.ion"     -> "'null'::23",
                "annotationQuotedNullInt.ion"  -> "'null.int'::23",
                "annotationQuotedOperator.ion" -> "'@'::23",
                "annotationQuotedPosInf.ion"   -> "'+inf'::23",
                "annotationQuotedNegInf.ion"   -> "'-inf'::23"
            )

            for
                multi <- resource("/iontestdata/good/multipleAnnotations.ion")
                _ <- Kyo.foreach(annotationCases) { case (file, ion) =>
                    resource(s"/iontestdata/good/$file").map { content =>
                        assert(content.trim == ion)
                        assert(Ion.decode[Int](ion).getOrThrow == 23)
                    }
                }
                annotatedNull   <- resource("/iontestdata/good/structFieldAnnotationsUnquotedThenQuoted.ion")
                quotedNullField <- resource("/iontestdata/good/fieldNameQuotedNullInt.ion")
            yield
                assert(multi.trim == "annot1::annot2::value")
                assert(Ion.decode[String]("annot1::annot2::value").getOrThrow == "value")
                assert(Ion.decode[Map[String, Option[String]]](annotatedNull).getOrThrow == Map("f" -> None))
                assert(Ion.decode[Map[String, Boolean]](quotedNullField).getOrThrow == Map("null.int" -> false))
            end for
        }

        "rejects invalid annotation and numeric syntax" in {
            val invalid = List(
                "null::1",
                "null.int::1",
                "true::1",
                "false::1",
                "nan::1",
                "null.nope",
                "+1",
                "0123",
                "1_",
                "1__2",
                "0x_12",
                "123_._456",
                "123.456_",
                "-_123.456",
                "12__34.56"
            )

            invalid.foreach { ion =>
                assert(Ion.decode[Int](ion).isFailure)
            }
            succeed
        }
    }

    // The JS test runner's working directory is the build root, while the JVM and Native runners use the
    // platform subproject directory. Resolve the repository root by walking up to the directory that holds
    // build.sbt, then read the shared corpus from there. This keeps the suite cross-platform with no classpath.
    private def corpusRoot(using Frame): Path < Sync =
        Path.cwd.map { cwd =>
            cwd.ancestors.run.map { chain =>
                def search(candidates: List[Path]): Path < Sync =
                    candidates match
                        case Nil => cwd
                        case head :: tail =>
                            (head / "build.sbt").isRegularFile.map { found =>
                                if found then head else search(tail)
                            }
                search(chain.toList)
            }
        }

    private def resource(name: String)(using Frame): String < (Sync & Abort[FileReadException]) =
        corpusRoot.map { root =>
            (root / "kyo-schema" / "shared" / "src" / "test" / "resources" / name.stripPrefix("/"))
                .read(StandardCharsets.UTF_8)
        }

end IonCorpusTest

object IonCorpusTest:

    val RequiredResources: List[String] =
        List(
            "allNulls.ion",
            "annotationQuotedFalse.ion",
            "annotationQuotedNan.ion",
            "annotationQuotedNegInf.ion",
            "annotationQuotedNull.ion",
            "annotationQuotedNullInt.ion",
            "annotationQuotedOperator.ion",
            "annotationQuotedPosInf.ion",
            "annotationQuotedTrue.ion",
            "blobs.ion",
            "booleans.ion",
            "clobs.ion",
            "decimal_values.ion",
            "decimalsWithUnderscores.ion",
            "fieldNameQuotedNullInt.ion",
            "floatSpecials.ion",
            "floatsWithUnderscores.ion",
            "intBinary.ion",
            "integer_values.ion",
            "intsWithUnderscores.ion",
            "lists.ion",
            "multipleAnnotations.ion",
            "nonNulls.ion",
            "sexps.ion",
            "strings.ion",
            "structFieldAnnotationsUnquotedThenQuoted.ion",
            "structs.ion",
            "symbolEmpty.ion",
            "symbols.ion",
            "whitespace.ion"
        )

    case class IntCase(source: String, ion: String, expected: BigInt)
    case class DecimalCase(source: String, ion: String, expected: BigDecimal)
    case class UpstreamStruct(a: String, c: Int, d: Map[String, String], g: Int) derives CanEqual

end IonCorpusTest
