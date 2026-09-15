# Сборка и телефон

Debug-сборка подписывается тем же релизным ключом из `local.properties`
(`app/build.gradle.kts`, блок `debug`), поэтому `adb install -r` обновляет
приложение поверх установленного без удаления и без потери данных. Debug-ключ
Android Studio не использовать.

Инструментальные тесты только так:

```
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class <классы> ru.kost.ruvoice.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall ru.kost.ruvoice.test
```

`gradlew connectedAndroidTest` не запускать: он переустанавливает пакет и
стирает настройки, словари и паки на телефоне.

Телефон Xiaomi: при `INSTALL_FAILED_USER_RESTRICTED` установку надо
подтвердить на экране, это подтверждение «Установка через USB» в MIUI, а не
конфликт подписи. Повторить `adb install` после подтверждения.
