#!/usr/bin/env bash
# 一键发布：打 release 包 + 推送 + 创建 GitHub Release
# 用法：GITHUB_TOKEN=ghp_xxx ./publish.sh [owner/repo]
set -euo pipefail
cd "$(dirname "$0")"

GRADLE=${GRADLE:-$(command -v gradle || echo "$HOME/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle")}
REPO=${1:-}

./gradlew --version >/dev/null 2>&1 || true
"$GRADLE" :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/WiFiADB-1.0.0-release.apk
( cd dist && shasum -a 256 WiFiADB-1.0.0-release.apk > SHA256SUMS )

if [ -n "${GITHUB_TOKEN:-}" ]; then
  command -v gh >/dev/null || { echo "需要 gh CLI: brew install gh"; exit 1; }
  printf '%s\n' "$GITHUB_TOKEN" | gh auth login --hostname github.com --git-protocol https --with-token
  gh auth setup-git
  OWNER=${REPO%%/*}; [ "$OWNER" = "$REPO" ] && OWNER=$(gh api user --jq .login)
  gh repo view "$OWNER/wifiadb" >/dev/null 2>&1 || gh repo create "$OWNER/wifiadb" --public \
    --description "WiFi ADB — 在 Android 上一键开启无线调试，并内置 ADB 客户端"
  git remote remove origin 2>/dev/null || true
  git remote add origin "https://github.com/$OWNER/wifiadb.git"
  git branch -M main
  git push -u origin main
  gh release create v1.0.0 dist/WiFiADB-1.0.0-release.apk \
    --repo "$OWNER/wifiadb" --title "WiFi ADB v1.0.0" --notes-file .github/release-notes.md
fi
echo "本地包: dist/WiFiADB-1.0.0-release.apk"
