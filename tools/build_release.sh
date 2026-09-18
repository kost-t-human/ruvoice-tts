#!/bin/sh
# Релизные APK обеих сборок в dist/: ruvoice-tts-<v>.apk (full, модель в APK) и ruvoice-tts-<v>-lite.apk
# (без модели, штатные голоса паком ru). Ключ подписи — local.properties (см. app/build.gradle.kts).
set -e
cd "$(dirname "$0")/.."
v=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
./gradlew -q :app:assembleFullRelease :app:assembleLiteRelease
mkdir -p dist
cp app/build/outputs/apk/full/release/app-full-release.apk "dist/ruvoice-tts-$v.apk"
cp app/build/outputs/apk/lite/release/app-lite-release.apk "dist/ruvoice-tts-$v-lite.apk"
ls -la "dist/ruvoice-tts-$v.apk" "dist/ruvoice-tts-$v-lite.apk"
