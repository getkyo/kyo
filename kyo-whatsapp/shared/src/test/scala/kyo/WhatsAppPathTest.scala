package kyo

import WhatsAppInvalidPathException.Problem

class WhatsAppPathTest extends BaseWhatsAppTest:

    def refused(text: String)(using Frame): Result[WhatsAppInvalidPathException, WhatsAppPath] = WhatsAppPath.init(text)

    "relative segments of letters, digits, dot, underscore and dash are accepted as written" in {
        Seq("me/phone_numbers", "106540352242922/message_templates", "a.b-c_d", "...").foreach { text =>
            assert(WhatsAppPath.init(text).map(_.value) == Result.succeed(text), s"for $text")
        }
        succeed
    }

    "an empty path is refused" in {
        assert(refused("") == Result.fail(WhatsAppInvalidPathException(Problem.Empty)))
    }

    "a leading, trailing or doubled slash is an empty segment at its index" in {
        assert(refused("/me") == Result.fail(WhatsAppInvalidPathException(Problem.EmptySegment(0))))
        assert(refused("me/") == Result.fail(WhatsAppInvalidPathException(Problem.EmptySegment(1))))
        assert(refused("me//x") == Result.fail(WhatsAppInvalidPathException(Problem.EmptySegment(1))))
    }

    "a . or .. segment is refused at its index" in {
        assert(refused("../me") == Result.fail(WhatsAppInvalidPathException(Problem.DotSegment(0))))
        assert(refused("me/./x") == Result.fail(WhatsAppInvalidPathException(Problem.DotSegment(1))))
    }

    "a character that would leave the path is refused at its position" in {
        Seq("me?x=1" -> 2, "me#f" -> 2, "me%2F" -> 2, "me x" -> 2, "mé" -> 1, "me\\x" -> 2).foreach { case (text, position) =>
            assert(refused(text) == Result.fail(WhatsAppInvalidPathException(Problem.Character(position))), s"for $text")
        }
    }

    // Built apart from the construction line: a leaf's development-mode message renders the source lines around its frame.
    val planted = Seq("PLANTED", "PATH", "7e2d").mkString("-")

    "a refusal's message names the problem, not the path" in {
        val e = refused(s"$planted?x")
        assert(e.failure.map(_.getMessage).exists(_.contains(
            "WhatsAppPath is not usable: the character at position 17 is not one of A-Z a-z 0-9 . _ - /."
        )))
        assert(e.failure.forall(f => BaseWhatsAppTest.renderings(f).forall(!_.contains(planted))))
    }

end WhatsAppPathTest
