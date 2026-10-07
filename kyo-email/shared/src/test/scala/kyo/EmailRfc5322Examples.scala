package kyo

/** Example messages of RFC 5322 Appendix A, as the RFC prints them with their three columns of indent removed and CRLF line ends. */
object EmailRfc5322Examples:

    /** A.1.1, the first message. */
    val simple: String =
        "From: John Doe <jdoe@machine.example>\r\n" +
            "To: Mary Smith <mary@example.net>\r\n" +
            "Subject: Saying Hello\r\n" +
            "Date: Fri, 21 Nov 1997 09:55:06 -0600\r\n" +
            "Message-ID: <1234@local.machine.example>\r\n" +
            "\r\n" +
            "This is a message just to say hello.\r\n" +
            "So, \"Hello\".\r\n"

    /** A.1.1, the message with a Sender. */
    val withSender: String =
        "From: John Doe <jdoe@machine.example>\r\n" +
            "Sender: Michael Jones <mjones@machine.example>\r\n" +
            "To: Mary Smith <mary@example.net>\r\n" +
            "Subject: Saying Hello\r\n" +
            "Date: Fri, 21 Nov 1997 09:55:06 -0600\r\n" +
            "Message-ID: <1234@local.machine.example>\r\n" +
            "\r\n" +
            "This is a message just to say hello.\r\n" +
            "So, \"Hello\".\r\n"

    /** A.1.2. */
    val mailboxes: String =
        "From: \"Joe Q. Public\" <john.q.public@example.com>\r\n" +
            "To: Mary Smith <mary@x.test>, jdoe@example.org, Who? <one@y.test>\r\n" +
            "Cc: <boss@nil.test>, \"Giant; \\\"Big\\\" Box\" <sysservices@example.net>\r\n" +
            "Date: Tue, 1 Jul 2003 10:52:37 +0200\r\n" +
            "Message-ID: <5678.21-Nov-1997@example.com>\r\n" +
            "\r\n" +
            "Hi everyone.\r\n"

    /** A.1.3. */
    val groups: String =
        "From: Pete <pete@silly.example>\r\n" +
            "To: A Group:Ed Jones <c@a.test>,joe@where.test,John <jdoe@one.test>;\r\n" +
            "Cc: Undisclosed recipients:;\r\n" +
            "Date: Thu, 13 Feb 1969 23:32:54 -0330\r\n" +
            "Message-ID: <testabcd.1234@silly.example>\r\n" +
            "\r\n" +
            "Testing.\r\n"

    /** A.2, the reply. */
    val reply: String =
        "From: Mary Smith <mary@example.net>\r\n" +
            "To: John Doe <jdoe@machine.example>\r\n" +
            "Reply-To: \"Mary Smith: Personal Account\" <smith@home.example>\r\n" +
            "Subject: Re: Saying Hello\r\n" +
            "Date: Fri, 21 Nov 1997 10:01:10 -0600\r\n" +
            "Message-ID: <3456@example.net>\r\n" +
            "In-Reply-To: <1234@local.machine.example>\r\n" +
            "References: <1234@local.machine.example>\r\n" +
            "\r\n" +
            "This is a reply to your hello.\r\n"

    /** A.2, the reply to the reply. */
    val replyToReply: String =
        "To: \"Mary Smith: Personal Account\" <smith@home.example>\r\n" +
            "From: John Doe <jdoe@machine.example>\r\n" +
            "Subject: Re: Saying Hello\r\n" +
            "Date: Fri, 21 Nov 1997 11:00:00 -0600\r\n" +
            "Message-ID: <abcd.1234@local.machine.test>\r\n" +
            "In-Reply-To: <3456@example.net>\r\n" +
            "References: <1234@local.machine.example> <3456@example.net>\r\n" +
            "\r\n" +
            "This is a reply to your reply.\r\n"

    /** A.5. */
    val oddities: String =
        "From: Pete(A nice \\) chap) <pete(his account)@silly.test(his host)>\r\n" +
            "To:A Group(Some people)\r\n" +
            "     :Chris Jones <c@(Chris's host.)public.example>,\r\n" +
            "         joe@example.org,\r\n" +
            "  John <jdoe@one.test> (my dear friend); (the end of the group)\r\n" +
            "Cc:(Empty list)(start)Hidden recipients  :(nobody(that I know))  ;\r\n" +
            "Date: Thu,\r\n" +
            "      13\r\n" +
            "        Feb\r\n" +
            "          1969\r\n" +
            "      23:32\r\n" +
            "               -0330 (Newfoundland Time)\r\n" +
            "Message-ID:              <testabcd.1234@silly.test>\r\n" +
            "\r\n" +
            "Testing.\r\n"

    /** A.6.1. */
    val obsoleteAddressing: String =
        "From: Joe Q. Public <john.q.public@example.com>\r\n" +
            "To: Mary Smith <@node.test:mary@example.net>, , jdoe@test  . example\r\n" +
            "Date: Tue, 1 Jul 2003 10:52:37 +0200\r\n" +
            "Message-ID: <5678.21-Nov-1997@example.com>\r\n" +
            "\r\n" +
            "Hi everyone.\r\n"

    /** A.6.2. */
    val obsoleteDate: String =
        "From: John Doe <jdoe@machine.example>\r\n" +
            "To: Mary Smith <mary@example.net>\r\n" +
            "Subject: Saying Hello\r\n" +
            "Date: 21 Nov 97 09:55:06 GMT\r\n" +
            "Message-ID: <1234@local.machine.example>\r\n" +
            "\r\n" +
            "This is a message just to say hello.\r\n" +
            "So, \"Hello\".\r\n"

    /** A.6.3. The RFC prints the line of two spaces after `To` as `__`, since a text file cannot show trailing white space. */
    val obsoleteWhiteSpace: String =
        "From  : John Doe <jdoe@machine(comment).  example>\r\n" +
            "To    : Mary Smith\r\n" +
            "  \r\n" +
            "          <mary@example.net>\r\n" +
            "Subject     : Saying Hello\r\n" +
            "Date  : Fri, 21 Nov 1997 09(comment):   55  :  06 -0600\r\n" +
            "Message-ID  : <1234   @   local(blah)  .machine .example>\r\n" +
            "\r\n" +
            "This is a message just to say hello.\r\n" +
            "So, \"Hello\".\r\n"

end EmailRfc5322Examples
