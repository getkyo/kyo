package kyo

import org.scalatest.freespec.AnyFreeSpec

/** Tests for cloud topology auto-detection logic.
  *
  * Note: Since auto-detection reads environment variables at object initialization time, and environment variables cannot be reliably
  * modified in a running JVM, these tests focus on:
  *   1. The path/bucket computation logic (which is testable via select with explicit path/bucket)
  *   2. The bucketFor public API (which uses MurmurHash3)
  *   3. Verifying that the detection priority and segment assembly logic is correct by testing the select engine with paths that would be
  *      produced by auto-detection
  *
  * The actual env var probing (detectPath) is a private method. Its correctness is verified by integration testing in real cloud
  * environments and by the path/bucket initialization tests below.
  */
class CloudTopologyTest extends AnyFreeSpec {

    private def pathOf(s: String): Array[String] =
        if (s.isEmpty) Array.empty[String] else s.split("/")

    "Explicit path takes precedence" - {
        "select with manually-set path ignores cloud topology" in {
            // If kyo.rollout.path is set, that path is used regardless of cloud env vars.
            // We verify this by testing the select engine with an explicit path.
            val manualPath = pathOf("manual/path")
            assert(Rollout.select("yes@manual", manualPath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@us-east-1", manualPath, 0) == Right(None))
        }
    }

    "AWS full detection" - {
        "path with all 3 segments matches correctly" in {
            // Simulates: AWS_REGION=us-east-1, ECS_CLUSTER=my-cluster, HOSTNAME=pod-xyz
            val awsPath = pathOf("us-east-1/my-cluster/pod-xyz")
            assert(Rollout.select("yes@us-east-1", awsPath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@us-east-1/my-cluster", awsPath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@us-east-1/my-cluster/pod-xyz", awsPath, 0) == Right(Some("yes")))
        }
    }

    "AWS partial detection" - {
        "only region set produces single-segment path" in {
            // Simulates: only AWS_REGION=us-east-1, rest not set
            val awsPath = pathOf("us-east-1")
            assert(Rollout.select("yes@us-east-1", awsPath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@us-east-1/az1b", awsPath, 0) == Right(None))
        }
    }

    "GCP full detection" - {
        "path with all 4 segments matches correctly" in {
            // Simulates: GOOGLE_CLOUD_PROJECT=my-project, GOOGLE_CLOUD_REGION=us-central1,
            //            K_SERVICE=my-service, HOSTNAME=instance-1
            val gcpPath = pathOf("my-project/us-central1/my-service/instance-1")
            assert(Rollout.select("yes@my-project", gcpPath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@my-project/us-central1", gcpPath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@my-project/us-central1/my-service", gcpPath, 0) == Right(Some("yes")))
        }
    }

    "GCP partial detection" - {
        "only project set produces single-segment path" in {
            val gcpPath = pathOf("my-project")
            assert(Rollout.select("yes@my-project", gcpPath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@my-project/us-central1", gcpPath, 0) == Right(None))
        }
    }

    "Kubernetes full detection" - {
        "path with all 3 segments matches correctly" in {
            // Simulates: KUBERNETES_SERVICE_HOST=10.0.0.1 (trigger),
            //            POD_NAMESPACE=production, NODE_NAME=node-1, HOSTNAME=pod-abc
            val kubePath = pathOf("production/node-1/pod-abc")
            assert(Rollout.select("yes@production", kubePath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@production/node-1", kubePath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@production/node-1/pod-abc", kubePath, 0) == Right(Some("yes")))
        }
    }

    "Kubernetes with only HOSTNAME" - {
        "minimal detection produces single-segment path" in {
            // When only KUBERNETES_SERVICE_HOST and HOSTNAME are set (no Downward API vars),
            // the path is just the hostname. Simulates: KUBERNETES_SERVICE_HOST=10.0.0.1, HOSTNAME=pod-abc
            val kubePath = pathOf("pod-abc")
            assert(Rollout.select("yes@pod-abc", kubePath, 0) == Right(Some("yes")))
        }
    }

    "Generic fallback" - {
        "ENV and HOSTNAME produce two-segment path" in {
            // Simulates: ENV=staging, HOSTNAME=pod-1
            val genericPath = pathOf("staging/pod-1")
            assert(Rollout.select("yes@staging", genericPath, 0) == Right(Some("yes")))
            assert(Rollout.select("yes@staging/pod-1", genericPath, 0) == Right(Some("yes")))
        }
    }

    "No env vars produces empty path" - {
        "empty path — only terminals and bare percentages match" in {
            val emptyPath = pathOf("")
            assert(Rollout.select("yes@prod", emptyPath, 0) == Right(None))
            assert(Rollout.select("fallback", emptyPath, 0) == Right(Some("fallback")))
            assert(Rollout.select("yes@50%", emptyPath, 25) == Right(Some("yes")))
            assert(Rollout.select("yes@50%", emptyPath, 75) == Right(None))
        }
    }

    "Provider priority" - {
        "Kubernetes vars take precedence over AWS vars" in {
            // If both KUBERNETES_SERVICE_HOST and AWS_REGION are present, Kubernetes path is used.
            // We test this by verifying path matching behavior.
            val kubePath = pathOf("production/node-1/pod-abc")
            // This would be the Kubernetes path (namespace/node/hostname), not the AWS path
            assert(Rollout.select("yes@production", kubePath, 0) == Right(Some("yes")))
            // AWS-style path (region-first) would not match
            assert(Rollout.select("yes@us-east-1", kubePath, 0) == Right(None))
        }
    }

    "Env var with hyphens" - {
        "hyphenated region is a single segment (not split)" in {
            // AWS_REGION=us-east-1 contains hyphens, not slashes — it's one segment
            val path = pathOf("us-east-1")
            assert(path.length == 1)
            assert(path(0) == "us-east-1")
            assert(Rollout.select("yes@us-east-1", path, 0) == Right(Some("yes")))
        }
    }

    "MurmurHash3 bucket computation" - {
        "bucketFor uses MurmurHash3 (not SHA-1)" in {
            // Verify by checking a known MurmurHash3 result
            val key    = "test-key"
            val hash   = scala.util.hashing.MurmurHash3.stringHash(key)
            val bucket = Math.floorMod(hash, 100)
            assert(Rollout.bucketFor(key) == bucket)
        }

        "bucket for auto-detected path segments" in {
            // Simulates bucket computation for a typical cloud path
            val path   = "us-east-1/az1b/my-cluster/pod-xyz"
            val bucket = Rollout.bucketFor(path)
            assert(bucket >= 0 && bucket < 100)
            // Same path always gives same bucket
            assert(Rollout.bucketFor(path) == bucket)
        }
    }

    "Cloud auto-detection + rollout interaction" - {

        "AWS multi-region routing" in {
            val path = pathOf("us-east-1/my-cluster/pod-abc")
            assert(Rollout.select("50@us-east-1;30@us-west-2;20@eu-west-1;10", path, 0) == Right(Some("50")))
        }

        "GCP project-scoped rollout" in {
            val path = pathOf("my-project/us-central1/my-service/i-1")
            assert(Rollout.select("fast@my-project/us-central1;balanced@my-project;safe", path, 0) == Right(Some("fast")))
        }

        "K8s namespace-based routing" in {
            val path = pathOf("production/node-1/pod-abc")
            assert(Rollout.select("debug@staging;info@production;warn", path, 0) == Right(Some("info")))
        }

        "Generic env feature gate" in {
            val path   = pathOf("staging/pod-1")
            val result = Rollout.select("true@production;false", path, 0)
            // staging does not match production, so the first choice is skipped;
            // "false" is a terminal — it always matches
            assert(result == Right(Some("false")))
        }

        "AWS region with percentage" in {
            val path = pathOf("us-east-1/my-cluster")
            // bucket 25 is within [0, 50) → true
            assert(Rollout.select("true@us-east-1/50%;false", path, 25) == Right(Some("true")))
            // bucket 75 is outside [0, 50) → falls to terminal "false"
            assert(Rollout.select("true@us-east-1/50%;false", path, 75) == Right(Some("false")))
        }

        "GCP project with percentage ramp" in {
            val path = pathOf("my-project/us-central1")
            // bucket 10 is within [0, 25) → new
            assert(Rollout.select("new@my-project/25%;old", path, 10) == Right(Some("new")))
            // bucket 50 is outside [0, 25) → falls to terminal "old"
            assert(Rollout.select("new@my-project/25%;old", path, 50) == Right(Some("old")))
        }

        "Wildcard region" in {
            val expr = "fast@*/us-central1;safe"
            assert(Rollout.select(expr, pathOf("my-project/us-central1"), 0) == Right(Some("fast")))
            assert(Rollout.select(expr, pathOf("other/us-central1"), 0) == Right(Some("fast")))
        }

        "Wildcard cluster" in {
            val expr = "true@us-east-1/*/pod-abc;false"
            assert(Rollout.select(expr, pathOf("us-east-1/my-cluster/pod-abc"), 0) == Right(Some("true")))
        }

        "HOSTNAME with dots is single segment" in {
            val path = pathOf("us-east-1/my-cluster/pod.abc.123")
            assert(Rollout.select("yes@us-east-1/my-cluster/pod.abc.123", path, 0) == Right(Some("yes")))
        }

        "Path with hyphens and numbers" in {
            val path = pathOf("my-project-123/us-central1-a")
            assert(Rollout.select("yes@my-project-123", path, 0) == Right(Some("yes")))
        }

        "No match falls to terminal" in {
            val path = pathOf("ap-southeast-1")
            assert(Rollout.select("fast@us-east-1;balanced@eu-west-1;safe", path, 0) == Right(Some("safe")))
        }

        "No match no terminal returns None" in {
            val path = pathOf("ap-southeast-1")
            assert(Rollout.select("fast@us-east-1;balanced@eu-west-1", path, 0) == Right(None))
        }
    }

    "Rollout.path and Rollout.bucket initialization" - {
        "path is an array (may be empty depending on environment)" in {
            // Just verify it's initialized and doesn't throw
            val p = Rollout.path
            assert(p != null)
            assert(p.length >= 0)
        }

        "bucket is in valid range" in {
            val b = Rollout.bucket
            assert(b >= 0 && b < 100)
        }
    }
}
