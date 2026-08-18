#!/usr/bin/env bash
# enable.sh — turn on CI in one command.
#
# Moves the workflow from ci/ into .github/workflows/, where GitHub Actions looks.
# This MUST be run by a human (or any token WITH the `workflows` permission): the
# automation agent's GitHub App token cannot push files under .github/workflows/
# (verified empirically — the push is rejected with
#  "refusing to allow a GitHub App to create or update workflow ... without
#   'workflows' permission"). A normal developer push permission is enough.
#
# Usage:
#   bash ci/enable.sh && git push
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ -f .github/workflows/android.yml ]]; then
  echo "CI already enabled (.github/workflows/android.yml exists)."
  exit 0
fi
if [[ ! -f ci/android.yml ]]; then
  echo "ERROR: ci/android.yml not found." >&2
  exit 1
fi

mkdir -p .github/workflows
git mv ci/android.yml .github/workflows/android.yml
git commit -m "ci: enable Android + Python + parity + backend CI workflow"

cat <<'MSG'
CI enabled. The workflow now runs four jobs on push:
  - android        : detekt / lint / kotest unit tests / assembleDebug /
                     assembleRelease (R8 + resource shrink, throwaway keystore)
  - python-oracle  : popcoon-tdd pytest suite
  - parity         : run_all.sh — cross-language parity + data mappers +
                     run_compile_core.sh (Android 非依存部の実コンパイル) +
                     check_overrides.py (override 欠落の静的検査)
  - backend        : Cloudflare Worker tsc + vitest

これが有効になると、本環境では実行できなかった以下が初めて検証されます:
  - Compose/Room/Hilt を含む app モジュール全体のコンパイル (85 ファイル)
  - kotest 単体テスト (本環境では実行不可のまま書かれている)
  - R8 圧縮後の release ビルド

Push to activate:
  git push
MSG
