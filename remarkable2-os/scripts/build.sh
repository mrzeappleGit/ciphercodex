#!/usr/bin/env bash
# Cross-compile ciphercodex-shell for reMarkable 2 using the official SDK in Docker.
# Usage: scripts/build.sh          (from remarkable2-os/ or repo root)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/sdk-version.env
[ -n "$SDK_URL" ] || { echo "SDK_URL not set in scripts/sdk-version.env" >&2; exit 1; }

IMAGE=ccx-rm2-sdk:$SDK_OS_VERSION

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "Building SDK image $IMAGE ..."
    docker build -t "$IMAGE" --build-arg SDK_URL="$SDK_URL" -f - . <<'EOF'
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates wget xz-utils file python3 cmake ninja-build make g++ locales \
    && rm -rf /var/lib/apt/lists/* \
    && sed -i 's/# en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen
ENV LANG=en_US.UTF-8
ARG SDK_URL
RUN wget -q "$SDK_URL" -O /tmp/sdk.sh && chmod +x /tmp/sdk.sh \
    && /tmp/sdk.sh -y -d /opt/codex-sdk && rm /tmp/sdk.sh
EOF
fi

# MSYS_NO_PATHCONV: stop Git Bash rewriting /work into a Windows path
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W 2>/dev/null || pwd):/work" -w /work "$IMAGE" bash -c '
    set -e
    source /opt/codex-sdk/environment-setup-*
    cmake -S . -B build-rm2 -G Ninja
    cmake --build build-rm2
'
echo "Built: build-rm2/ciphercodex-shell, build-rm2/input-probe"
