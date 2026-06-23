package kyo.internal

import kyo.*

class NameCaseConversionTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    val camel     = NameCaseConversion.convert(Schema.NameCase.CamelCase)
    val snake     = NameCaseConversion.convert(Schema.NameCase.SnakeCase)
    val kebab     = NameCaseConversion.convert(Schema.NameCase.KebabCase)
    val pascal    = NameCaseConversion.convert(Schema.NameCase.PascalCase)
    val screaming = NameCaseConversion.convert(Schema.NameCase.ScreamingSnakeCase)

    "camelCase basic" - {
        "firstName FirstName first_name all yield firstName (idempotent)" in {
            assert(camel("firstName") == "firstName")
            assert(camel("FirstName") == "firstName")
            assert(camel("first_name") == "firstName")
        }
    }

    "snake_case basic" - {
        "firstName and FirstName both yield first_name" in {
            assert(snake("firstName") == "first_name")
            assert(snake("FirstName") == "first_name")
        }
    }

    "kebab-case basic" - {
        "firstName yields first-name" in {
            assert(kebab("firstName") == "first-name")
        }
    }

    "PascalCase basic" - {
        "firstName and first_name both yield FirstName" in {
            assert(pascal("firstName") == "FirstName")
            assert(pascal("first_name") == "FirstName")
        }
    }

    "SCREAMING_SNAKE_CASE basic" - {
        "firstName yields FIRST_NAME" in {
            assert(screaming("firstName") == "FIRST_NAME")
        }
    }

    "leading-acronym run HTTPServer all conventions" - {
        "HTTP is one word: camel httpServer, snake http_server, kebab http-server, pascal HttpServer, screaming HTTP_SERVER" in {
            assert(camel("HTTPServer") == "httpServer")
            assert(snake("HTTPServer") == "http_server")
            assert(kebab("HTTPServer") == "http-server")
            assert(pascal("HTTPServer") == "HttpServer")
            assert(screaming("HTTPServer") == "HTTP_SERVER")
        }
    }

    "short leading-cap-run names DList DListItem CharRef" - {
        "DList->dList/d_list; DListItem->dListItem/d_list_item; CharRef->charRef/char_ref" in {
            assert(camel("DList") == "dList")
            assert(snake("DList") == "d_list")
            assert(camel("DListItem") == "dListItem")
            assert(snake("DListItem") == "d_list_item")
            assert(camel("CharRef") == "charRef")
            assert(snake("CharRef") == "char_ref")
        }
    }

    "interior and multi acronym IOError parseXMLToJSON" - {
        "IOError->ioError/io_error/IoError; parseXMLToJSON->parseXmlToJson/parse_xml_to_json/ParseXmlToJson" in {
            assert(camel("IOError") == "ioError")
            assert(snake("IOError") == "io_error")
            assert(pascal("IOError") == "IoError")
            assert(camel("parseXMLToJSON") == "parseXmlToJson")
            assert(snake("parseXMLToJSON") == "parse_xml_to_json")
            assert(pascal("parseXMLToJSON") == "ParseXmlToJson")
        }
    }

    "lone all-caps acronym XML" - {
        "camel/snake/kebab xml, pascal Xml, screaming XML (single acronym word recased per target)" in {
            assert(camel("XML") == "xml")
            assert(snake("XML") == "xml")
            assert(kebab("XML") == "xml")
            assert(pascal("XML") == "Xml")
            assert(screaming("XML") == "XML")
        }
    }

    "trailing-acronym pure-engine value userID" - {
        "camel userId, snake user_id, pascal UserId (pure engine recases trailing ID to Id)" in {
            assert(camel("userID") == "userId")
            assert(snake("userID") == "user_id")
            assert(pascal("userID") == "UserId")
        }
    }

    "digit boundaries and leading digit 2Fast Paragraph" - {
        "2Fast->2_fast/2Fast; Paragraph->paragraph/paragraph" in {
            assert(snake("2Fast") == "2_fast")
            assert(camel("2Fast") == "2Fast")
            assert(snake("Paragraph") == "paragraph")
            assert(camel("Paragraph") == "paragraph")
        }
    }

    "single-char edge and idempotency" - {
        "x->camel/snake/kebab x, pascal/screaming X; snake(snake(first_name))==first_name" in {
            assert(camel("x") == "x")
            assert(snake("x") == "x")
            assert(kebab("x") == "x")
            assert(pascal("x") == "X")
            assert(screaming("x") == "X")
            assert(snake(snake("first_name")) == "first_name")
        }
    }

end NameCaseConversionTest
