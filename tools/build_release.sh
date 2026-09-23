#!/bin/sh
# Релизные APK обеих сборок в dist/: ruvoice-tts-<v>.apk (full, модель в APK) и ruvoice-tts-<v>-lite.apk
# (без модели, штатные голоса паком ru). Ключ подписи — local.properties (см. app/build.gradle.kts).
set -e
cd "$(dirname "$0")/.."
v=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
# Собирается рабочее дерево, а не коммит: чужая незакоммиченная правка уедет в релиз (так в 0.14.18 попала
# отладочная запись звука). Грязное дерево в app/ — отказ.
if [ -n "$(git status --porcelain -- app)" ]; then echo "app/ не закоммичен, релиз не собираю:"; git status --short -- app; exit 1; fi
./gradlew -q :app:assembleFullRelease :app:assembleLiteRelease
mkdir -p dist
cp app/build/outputs/apk/full/release/app-full-release.apk "dist/ruvoice-tts-$v.apk"
cp app/build/outputs/apk/lite/release/app-lite-release.apk "dist/ruvoice-tts-$v-lite.apk"
ls -la "dist/ruvoice-tts-$v.apk" "dist/ruvoice-tts-$v-lite.apk"
