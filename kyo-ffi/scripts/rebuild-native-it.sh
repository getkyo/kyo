#!/bin/bash
# Full clean rebuild for kyo-ffi Native integration tests.
# Required when codegen changes need to propagate through the sbt plugin's bundled classloader.
#
# Build chain: kyo-ffi (shared API) → codegen → plugin (bundles codegen) → meta-build → IT codegen
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

echo "==> Step 1: Clean + rebuild kyo-ffi + codegen + plugin..."
sbt --batch \
    'kyo-ffi/clean' \
    'kyo-ffi-codegen/clean' \
    'kyo-ffi-plugin/clean' \
    'kyo-ffiNative/compile' \
    'kyo-ffi-plugin/package' \
    2>&1 | tail -5

BUNDLE_DIR="kyo-ffi-plugin/target/scala-2.12/sbt-1.0/resource_managed/main/kyo-ffi-plugin"
if [ ! -f "$BUNDLE_DIR/bundled.txt" ]; then
    echo "ERROR: bundled.txt not found at $BUNDLE_DIR"
    exit 1
fi
echo "    Bundle verified: $(cat "$BUNDLE_DIR/bundled.txt" | wc -l | tr -d ' ') JARs"

echo "==> Step 2: Clear meta-build cache + IT Native target..."
rm -rf project/target kyo-ffi/it/native/target

echo "==> Step 3: Run Native IT tests (fresh sbt picks up new plugin bundle)..."
sbt --batch 'kyo-ffi-itNative/test'
