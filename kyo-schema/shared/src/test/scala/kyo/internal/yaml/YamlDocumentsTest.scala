package kyo.internal.yaml

import kyo.*

class YamlDocumentsTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "YamlDocuments" - {

        "detects document markers only at the start of a line" in {
            val yaml =
                """name: Alice
                  |text: |
                  |  ---
                  |  ...
                  |""".stripMargin

            assert(!YamlDocuments.requiresSplit(yaml))
        }

        "splits explicit streams and preserves empty documents" in {
            val yaml =
                """--- # first
                  |name: Alice
                  |...
                  |--- # empty
                  |...
                  |---
                  |name: Bob
                  |""".stripMargin

            val obtained = (
                requiresSplit = YamlDocuments.requiresSplit(yaml),
                split = YamlDocuments.split(yaml)
            )
            assert(obtained.requiresSplit == true)
            assert(obtained.split == Chunk("name: Alice\n", "", "name: Bob\n"))
        }

        "ignores directives and comments between documents" in {
            val yaml =
                """%YAML 1.2
                  |---
                  |name: Alice
                  |...
                  |# next document
                  |%TAG ! tag:example.com,2026:
                  |---
                  |name: Bob
                  |""".stripMargin

            assert(YamlDocuments.split(yaml) == Chunk("name: Alice\n", "name: Bob\n"))
        }

        "merges non-empty top-level mapping fragments with line separation" in {
            val docs = Chunk("name: Alice", "", "age: 30\n")

            assert(YamlDocuments.mergeTopLevelMappings(docs) == "name: Alice\nage: 30\n")
        }
    }
end YamlDocumentsTest
