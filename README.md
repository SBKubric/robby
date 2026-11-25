# Robby

Robby — Android-приложение для автоматизации на отдельном телефоне-сервере, который постоянно лежит дома на зарядке. Работает целиком на устройстве, как IFTTT без облака: события телефона (SMS, звонки, батарея, расписание) запускают сценарии, а сценарии выполняют действия — например, отправляют сообщение в Telegram. Термины проекта — в [GLOSSARY.md](GLOSSARY.md), архитектура — в [ADR 0001](docs/adr/0001-applets-and-onion.md).

## Модули

| Модуль | Что внутри |
|---|---|
| `:core` | Чистый Kotlin/JVM без зависимостей от Android: домен и правила. Тесты — обычные юнит-тесты на JVM. |
| `:app` | Android-приложение: сервис, ресиверы, UI на Jetpack Compose, сборка зависимостей (`AppContainer`). Зависит от `:core`, обратной зависимости нет. |

## Сборка

Нужны JDK 17+ и Android SDK (platform 37). Укажите путь к SDK в переменной `ANDROID_HOME` или в `local.properties` (файл не коммитится):

```properties
sdk.dir=/path/to/android-sdk
```

Тесты и debug-APK:

```sh
./gradlew :core:test :app:assembleDebug
```

APK появится в `app/build/outputs/apk/debug/`. Без ключей подписи `assembleRelease` собирает неподписанный APK; подписанный release собирает GitHub Actions по тегу — см. [docs/release.md](docs/release.md).
