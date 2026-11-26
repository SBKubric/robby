# Выпуск Robby: чеклист владельца

Release-APK собирает GitHub Actions (`.github/workflows/release.yml`) по тегу `vX.Y.Z`: подписывает его ключом из GitHub Secrets и прикладывает к GitHub Release. Шаги 1–4 делаются один раз.

> ⚠️ **Ключ подписи нельзя терять.** Android ставит обновление поверх установленного приложения, только если оно подписано тем же ключом. Без ключа новую версию придётся ставить начисто: удалить Robby вместе со всеми сценариями и настройками.

## 1. Создать ключ подписи

На своём компьютере (нужен JDK, `keytool` входит в него):

```sh
keytool -genkeypair -v \
  -keystore robby-release.jks \
  -alias robby \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

`keytool` спросит пароль хранилища и имя/организацию (можно любые). Для формата PKCS12 (по умолчанию) пароль ключа совпадает с паролем хранилища.

## 2. Сохранить ключ вне репозитория

- Положить `robby-release.jks`, пароль и alias (`robby`) в менеджер паролей; сделать вторую копию в другом месте.
- **Не коммитить** файл ключа: `*.jks`, `*.keystore`, `*.p12` уже в `.gitignore`.

## 3. Добавить секреты в GitHub

Закодировать ключ в base64 одной строкой:

```sh
base64 -w0 robby-release.jks > robby-release.jks.b64          # Linux
base64 -i robby-release.jks | tr -d '\n' > robby-release.jks.b64  # macOS
```

В репозитории: **Settings → Secrets and variables → Actions → Secrets → New repository secret**, четыре секрета:

| Секрет | Значение |
|---|---|
| `ROBBY_KEYSTORE_BASE64` | содержимое `robby-release.jks.b64` |
| `ROBBY_KEYSTORE_PASSWORD` | пароль хранилища |
| `ROBBY_KEY_ALIAS` | `robby` |
| `ROBBY_KEY_PASSWORD` | пароль ключа (для PKCS12 — тот же, что у хранилища) |

То же через `gh`:

```sh
gh secret set ROBBY_KEYSTORE_BASE64 < robby-release.jks.b64
gh secret set ROBBY_KEYSTORE_PASSWORD
gh secret set ROBBY_KEY_ALIAS --body robby
gh secret set ROBBY_KEY_PASSWORD
```

После этого удалить `robby-release.jks.b64`.

## 4. Включить или выключить сборку на push

Сборка APK и тесты `:core` на каждый push (`.github/workflows/ci.yml`) работают, только когда переменная репозитория `BUILD_ON_PUSH` равна `true`.

**Settings → Secrets and variables → Actions → Variables → New repository variable**: `BUILD_ON_PUSH` = `true`. Или: `gh variable set BUILD_ON_PUSH --body true`.

Чтобы выключить — поставить `false` или удалить переменную. Выпуск по тегу от неё не зависит.

## 5. Выпустить версию

```sh
git checkout main && git pull
git tag v0.1.0
git push origin v0.1.0
```

- Формат тега строго `vX.Y.Z`, `Y` и `Z` меньше 100. Из тега получаются `versionName` = `X.Y.Z` и `versionCode` = `X·10000 + Y·100 + Z`.
- Каждый следующий тег должен быть больше предыдущего, иначе Android не поставит обновление.
- Ход сборки — во вкладке **Actions**; готовый `robby-vX.Y.Z.apk` — в **Releases**. Если сборка упала на подписи, проверьте четыре секрета.

## 6. Установить на телефон-сервер

- **Тапом:** открыть на телефоне страницу Releases, скачать `robby-vX.Y.Z.apk`, открыть и разрешить установку из этого источника.
- **Через Obtainium:** «Добавить приложение» → URL `https://github.com/sbkubric/robby` → «Добавить». Obtainium сам находит новые релизы и ставит обновления.

Если APK открыт из «Загрузок» или файлового менеджера, на Android 13+ часть разрешений может оказаться под «настройками с ограниченным доступом»; как это снять — в [исследовании ограничений Android](https://github.com/sbkubric/robby/issues/2).
