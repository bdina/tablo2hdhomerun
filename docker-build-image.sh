#!/usr/bin/env bash
# Sync locally published ~/.m2 artifacts into gitignored .m2-local/ (only when changed,
# preserving Docker layer caches) and build Dockerfile.native or Dockerfile.jvm.
# Usage:
#   ./docker-build-image.sh [native|jvm] [docker build args...]
# Examples:
#   ./docker-build-image.sh native --tag tablo2hdhomerun:latest
#   ./docker-build-image.sh jvm --build-arg REQUIRE_CLEAN_VERSION=true --tag tablo2hdhomerun:jvm

set -euo pipefail

TARGET="${1:-native}"
case "${TARGET}" in
  native|jvm)
    if [ "$#" -gt 0 ]; then
      shift
    fi
    DOCKERFILE="Dockerfile.${TARGET}"
    ;;
  *)
    DOCKERFILE="Dockerfile.native"
    ;;
esac

M2_DIR="${M2_DIR:-${HOME}/.m2}"
LOCAL_M2_GROUPS="${LOCAL_M2_GROUPS:-com/scalarometer}"
STAGE_DIR=".m2-local"

mkdir -p "${STAGE_DIR}"
for group in ${LOCAL_M2_GROUPS}; do
  src="${M2_DIR}/repository/${group}"
  dst="${STAGE_DIR}/${group}"
  if [ ! -d "${src}" ]; then
    echo "ERROR: Local Maven artifact directory not found: ${src}" >&2
    echo "Publish the dependency to ${M2_DIR}/repository first (e.g. ./docker-publish-local.sh)." >&2
    exit 1
  fi
  if [ ! -d "${dst}" ] || ! diff -rq "${src}" "${dst}" >/dev/null 2>&1; then
    rm -rf "${dst}"
    mkdir -p "$(dirname "${dst}")"
    cp -a "${src}" "${dst}"
    echo "[m2-sync] updated ${dst} from ${src}"
  else
    echo "[m2-sync] up-to-date ${dst}"
  fi
done

exec docker build -f "${DOCKERFILE}" "$@" .
