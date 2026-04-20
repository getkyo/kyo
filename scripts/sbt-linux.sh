#!/usr/bin/env bash
set -euo pipefail
#
# Run sbt commands in a fresh Linux container matching CI.
# Uses git archive + git diff to capture current working tree (no cached binaries).
#
# Usage:
#   ./scripts/sbt-linux.sh 'kyo-httpNative/test'
#   ./scripts/sbt-linux.sh 'kyo-httpNative/clean' 'kyo-httpNative/test'
#
# Options (env vars):
#   PLATFORM  - linux/amd64 (default) or linux/arm64
#   DISTRO    - ubuntu:noble (default)
#

PLATFORM="${PLATFORM:-linux/arm64}"
DISTRO="${DISTRO:-ubuntu:noble}"
H2O_PKG="libh2o-evloop-dev=2.2.5+dfsg2-8.1ubuntu3"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

if [ $# -eq 0 ]; then
  echo "Usage: $0 <sbt-command> [sbt-command...]"
  echo "  PLATFORM=linux/amd64 $0 'kyo-httpNative/test'"
  exit 1
fi

# Build sbt command string — escape inner quotes for shell-in-shell
SBT_CMDS=""
for arg in "$@"; do
  SBT_CMDS="$SBT_CMDS '$arg'"
done

# Ensure podman is running
if ! podman info >/dev/null 2>&1; then
  echo "Starting podman..."
  podman machine start 2>/dev/null || true
fi

# Create source archive: git tracked files + uncommitted changes
TMPDIR=$(mktemp -d)
trap "rm -rf '$TMPDIR'" EXIT

echo "Archiving source..."
git -C "$PROJECT_DIR" archive HEAD --format=tar > "$TMPDIR/src.tar"

# Capture uncommitted changes (staged + unstaged)
PATCH="$TMPDIR/changes.patch"
git -C "$PROJECT_DIR" diff HEAD > "$PATCH" 2>/dev/null || true
if [ -s "$PATCH" ]; then
  echo "Including uncommitted changes"
fi

# Also capture untracked files in kyo-http native resources (native_deps.h etc)
UNTRACKED="$TMPDIR/untracked.tar"
(cd "$PROJECT_DIR" && git ls-files --others --exclude-standard \
  kyo-http/native/src/ scripts/ .github/ \
  | tar cf "$UNTRACKED" -T - 2>/dev/null) || tar cf "$UNTRACKED" --files-from=/dev/null

echo "Platform: $PLATFORM"
echo "Commands:$SBT_CMDS"
echo "---"

podman run --rm \
  --platform "$PLATFORM" \
  --security-opt label=disable \
  -v "$TMPDIR:/build-input:ro" \
  "$DISTRO" \
  bash -c "
set -e
mkdir -p /workspace && cd /workspace

# Extract source
tar xf /build-input/src.tar
tar xf /build-input/untracked.tar 2>/dev/null || true
echo \"Patch file size: \$(wc -c < /build-input/changes.patch 2>/dev/null || echo 0)\"
if [ -s /build-input/changes.patch ]; then
  apt-get update -qq >/dev/null 2>&1
  apt-get install -y -qq patch >/dev/null 2>&1
  patch -p1 --fuzz=0 < /build-input/changes.patch
  echo 'Patch applied successfully'
else
  echo 'No uncommitted changes to apply'
fi

# Install native deps
apt-get update -qq >/dev/null 2>&1
apt-get install -y -qq wget clang libcurl4-openssl-dev libidn2-dev $H2O_PKG libssl-dev >/dev/null 2>&1

# Install JDK
ARCH=\$(dpkg --print-architecture)
case \$ARCH in
  amd64) JDK_URL='https://corretto.aws/downloads/latest/amazon-corretto-24-x64-linux-jdk.tar.gz' ;;
  arm64) JDK_URL='https://corretto.aws/downloads/latest/amazon-corretto-24-aarch64-linux-jdk.tar.gz' ;;
  *) echo \"Unsupported: \$ARCH\" && exit 1 ;;
esac
wget -q \"\$JDK_URL\" -O /tmp/jdk.tar.gz
mkdir -p /opt/jdk && tar xzf /tmp/jdk.tar.gz -C /opt/jdk --strip-components=1

# Install sbt
wget -q https://github.com/sbt/sbt/releases/download/v1.10.11/sbt-1.10.11.tgz -O /tmp/sbt.tgz
tar xzf /tmp/sbt.tgz -C /opt/

export JAVA_HOME=/opt/jdk
export PATH=\$JAVA_HOME/bin:/opt/sbt/bin:\$PATH
export JAVA_OPTS='-Xms4G -Xmx6G -Xss10M'
export JVM_OPTS='-Xms4G -Xmx6G -Xss10M'

echo '=== sbt clean +$SBT_CMDS ==='
sbt clean$SBT_CMDS
"
