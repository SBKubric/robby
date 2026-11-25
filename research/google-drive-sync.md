# Google Drive из sideload-приложения без бэкенда

Исследование к тикету [#4](https://github.com/SBKubric/robby/issues/4). Вопрос: как Robby (Kotlin, minSdk 31, APK вне Google Play, без своего бэкенда) может хранить бэкап сценариев и настроек коннекторов в Google Drive пользователя и переносить его на новый телефон-сервер.

Дата сверки с источниками: 2026-11-25. Каждое утверждение помечено ссылкой на первоисточник. Пометка **[вывод]** значит, что прямой формулировки в документации нет: это заключение из нескольких источников, которое надо проверить на прототипе.

## Коротко

1. **Авторизация:** `AuthorizationClient` из Google Identity Services (`com.google.android.gms:play-services-auth`) со scope `drive.appdata`. Credential Manager для бэкапа не нужен. Старый `GoogleSignIn` объявлен устаревшим. Без бэкенда не нужны ни `requestOfflineAccess`, ни refresh-токен, ни Web-клиент: приложение каждый раз вызывает `authorize()` и молча получает часовой access-токен.
2. **Где хранить:** основной вариант `appDataFolder`. Он скрыт от пользователя, а scope `drive.appdata` несекретный (non-sensitive). Для переноса нужны тот же Google-аккаунт и тот же OAuth-проект (то же имя пакета и тот же ключ подписи). Как запасной выход стоит добавить ручной экспорт и импорт файла через системный выбор файла (SAF), потому что `appDataFolder` пользователь не видит.
3. **Google Cloud Console:** нужен Android OAuth-клиент (имя пакета плюс SHA-1 release-ключа, для debug отдельный клиент). Аудитория External. Приложение **обязательно перевести в статус «In production»**: в «Testing» согласие истекает через 7 дней, а это ломает фоновый бэкап на телефоне-сервере. С одними несекретными scope верификация не обязательна.
4. **Android Auto Backup** годится как бесплатное дополнение, но не как основной механизм. Время бэкапа не управляется, восстановление бывает только при установке, восстановиться на новом устройстве можно только из набора данных, выбранного в мастере настройки, а UI для переноса не сделать.

## 1. API авторизации на Android

### Что актуально

- Google разделяет аутентификацию (кто пользователь) и авторизацию (доступ к данным): «Authentication and authorization calls must be two separate and distinct flows». Для входа рекомендован Credential Manager, для доступа к данным Google `AuthorizationClient`. [developer.android.com/identity/authorization](https://developer.android.com/identity/authorization)
- Legacy Google Sign-In (`GoogleSignInClient`) «is deprecated and will be removed from the Google Play services Auth SDK in a future release». Для доступа к Drive предписано использовать `AuthorizationClient`. [legacy-gsi-migration](https://developer.android.com/identity/sign-in/legacy-gsi-migration)
- Зависимость в документации: `com.google.android.gms:play-services-auth:22.0.0` («latest version»). При реализации нужно взять актуальную версию из Maven. [developer.android.com/identity/authorization](https://developer.android.com/identity/authorization)

### Как выглядит поток

1. `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_APPDATA))).build())`.
2. Если `authorizationResult.hasResolution()`, нужно показать согласие через `pendingIntent` (`ActivityResultContracts.StartIntentSenderForResult`). Иначе `authorizationResult.accessToken` готов к использованию. [developer.android.com/identity/authorization](https://developer.android.com/identity/authorization)
3. Дальше: «call `AuthorizationClient.authorize()` once; in subsequent sessions, and as long as the granted permissions are not removed by the user, call the same method to obtain an access token ... without any user interaction». [там же, «Maintain ongoing access»](https://developer.android.com/identity/authorization#maintain-access)
4. Access-токен живёт час. Токены кэшируются локально. При `IllegalStateException` нужно сбросить кэш через `clearToken()`. [там же](https://developer.android.com/identity/authorization)
5. Refresh-токен и `requestOfflineAccess(serverClientId)` нужны только для доступа с бэкенда. Документация прямо запрещает хранить refresh-токен на устройстве: «Don't store refresh tokens on the device». [там же](https://developer.android.com/identity/authorization#maintain-access) Для Robby это не нужно: Play services сам выдаёт свежий access-токен.
6. Отзыв доступа: `revokeAccess()`. Он отзывает **все** scope приложения, а не только перечисленные. [там же](https://developer.android.com/identity/authorization)
7. Ответ авторизации не говорит, какой аккаунт выбран. Если в UI нужно показать e-mail аккаунта бэкапа, есть два пути: Credential Manager до авторизации или дополнительные scope `openid`/`email` с запросом к userinfo. [там же, «Get user information during authorization»](https://developer.android.com/identity/authorization)

### Следствия для Robby

- Credential Manager не обязателен. Он нужен, только если хотим показывать e-mail аккаунта или явно переключать аккаунт (`clearCredentialState()`). Для первой версии хватит запроса `email` вместе с `drive.appdata` (scope `email`/`openid` тоже несекретные).
- **[вывод]** Фоновый бэкап из WorkManager. `getAuthorizationClient` принимает `Context` (в примере документации передаётся `requireContext()`), поэтому получить токен без UI можно, пока согласие действует. Если пришёл `hasResolution()`, воркер не может показать согласие сам: нужно поставить уведомление «Откройте Robby, чтобы восстановить доступ к Google Drive».
- Сам Drive вызывается обычным REST v3 с заголовком `Authorization: Bearer <accessToken>` (через OkHttp/Ktor или `google-api-services-drive`). Для бэкапа хватит трёх операций: `files.list?spaces=appDataFolder`, `files.create` с `parents=["appDataFolder"]` (multipart), `files.get?alt=media`. [drive/api/guides/appdata](https://developers.google.com/workspace/drive/api/guides/appdata)

## 2. `appDataFolder` или видимая папка

### Факты

| | `appDataFolder` (`drive.appdata`) | Видимая папка (`drive.file`) |
|---|---|---|
| Класс scope | non-sensitive [scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) | non-sensitive [scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) |
| Текст на экране согласия | «See, create, and delete its own configuration data in your Google Drive» [discovery v3](https://www.googleapis.com/discovery/v1/apis/drive/v3/rest) | «See, edit, create, and delete only the specific Google Drive files you use with this app» [discovery v3](https://www.googleapis.com/discovery/v1/apis/drive/v3/rest) |
| Видимость пользователю | Нет: «contents are hidden from the user and from other Google Drive apps», в UI Drive не видна [appdata](https://developers.google.com/workspace/drive/api/guides/appdata) | Да, обычные файлы в «Моём диске» |
| Кто имеет доступ | «Only the application that created the data in the `appDataFolder` can access it» [appdata](https://developers.google.com/workspace/drive/api/guides/appdata) | Только файлы, которые пользователь «использует с этим приложением» (созданные им или выбранные через Picker) [scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) |
| Ограничения | Нельзя делиться, перемещать между spaces, класть в корзину (`notSupportedForAppDataFolderFiles`) [appdata](https://developers.google.com/workspace/drive/api/guides/appdata), [about-files](https://developers.google.com/workspace/drive/api/guides/about-files) | Пользователь может переименовать, переместить или удалить файл |
| Удаление | «deleted when a user uninstalls your app from their My Drive», пользователь может и удалить вручную [appdata](https://developers.google.com/workspace/drive/api/guides/appdata) | Только руками пользователя |

### Что важно для переноса на новый телефон-сервер

- И `appDataFolder`, и доступ `drive.file` привязаны к «приложению» с точки зрения Google OAuth. **[вывод]** «Приложение» здесь означает OAuth-проект Google Cloud (Android-клиент проекта задаётся парой «имя пакета + SHA-1»). Значит, на новом телефоне APK, подписанный **тем же ключом**, с тем же аккаунтом Google увидит свой бэкап. Если ключ подписи потерян, можно добавить в тот же проект новый Android-клиент с новым SHA-1. Ожидаем, что доступ к данным сохранится, но это надо проверить на прототипе.
- **Ключ подписи release-APK нужно хранить как критичный секрет** (бэкап keystore вне телефона). Сменился SHA-1 без обновления клиента в Console, и авторизация ломается.
- **[вывод]** Для `drive.file` неочевидно, видит ли переустановленное приложение файлы, созданные им раньше. Формулировка «files you use with this app» относится к приложению, а не к установке, так что, скорее всего, видит. Но это тоже проверка на прототипе.

### Рекомендация

- Основное хранилище: **`appDataFolder`**. Его не удалить и не переместить случайно, папка не засоряет «Мой диск», согласие формулируется проще всего («свои настройки»). В папке лежит один файл, например `robby-backup.json`: при бэкапе он перезаписывается (`files.update`), при восстановлении берётся последний по `modifiedTime`.
- Минус «пользователь не видит и не может скачать бэкап» закрыть ручным **экспортом и импортом** того же формата через SAF (`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`). Для этого не нужны ни сеть, ни Google.
- **[вывод]** Секреты (токен Telegram-бота) в `appDataFolder` не зашифрованы от владельца аккаунта Google: папка скрыта от UI, но не криптографически. Это входит в открытый пункт карты «Формат файла синхронизации и шифрование секретов».

## 3. OAuth-клиент в Google Cloud Console для APK вне Play

### Шаги (HITL, делает владелец проекта)

По [developer.android.com/identity/authorization](https://developer.android.com/identity/authorization), «Set up your Google Cloud Console project»:

1. Создать проект в Google Cloud Console, включить **Google Drive API**.
2. **Branding:** имя приложения, e-mail поддержки. Документация просит указать homepage, privacy policy и terms of service. **[вывод]** Строго обязательными они становятся при верификации, но страницу с политикой конфиденциальности лучше завести сразу (например, в README или на GitHub Pages).
3. **Audience:** тип пользователей **External** (Internal доступен только для Workspace-организаций) [verification exceptions](https://support.google.com/cloud/answer/13464323).
4. **Data access:** добавить scope `.../auth/drive.appdata` (и, если нужно, `openid`, `email`). Scope в запросе приложения должны совпадать с настройкой, иначе появляется экран «unverified app» [unverified app screen](https://support.google.com/cloud/answer/7454865).
5. **Clients → Create client → Android:** имя пакета и SHA-1 ключа, которым подписан распространяемый APK. Для self-signed: `keytool -list -v -keystore <release.jks> -alias <alias>` или `./gradlew signingReport`. Для debug-сборки отдельный клиент с SHA-1 из `~/.android/debug.keystore` («get both the release and debug certificate fingerprints») [client-auth](https://developers.google.com/android/guides/client-auth).
6. Web-клиент **не нужен**: в документации он описан как идентификатор бэкенда для `requestOfflineAccess` [authorization](https://developer.android.com/identity/authorization).
7. Пункт «Verify app ownership» пропустить: «Android app ownership verification is only available for Google Play apps» [authorization](https://developer.android.com/identity/authorization). Он необязательный.
8. **Publishing status → Publish app (In production).** Подробности ниже.

### Testing или In production

- **Testing:** только тестовые пользователи из списка, не больше 100. «Authorizations by a test user will expire seven days from the time of consent» [publishing status](https://support.google.com/cloud/answer/15549945). Refresh-токен в Testing истекает через 7 дней, если scope не ограничены `openid`/`email`/`profile` [oauth2 overview, Refresh token expiration](https://developers.google.com/identity/protocols/oauth2). **[вывод]** Через `AuthorizationClient` это, по всей видимости, означает, что раз в неделю `authorize()` возвращает `hasResolution()` и требует ручного согласия. Для телефона-сервера, который лежит дома без присмотра, это неприемлемо.
- **In production:** «available to any user with a Google Account» [publishing status](https://support.google.com/cloud/answer/15549945). Если приложение использует только несекретные scope, «it is not mandatory for your app to complete the app verification process» [verification requirements](https://support.google.com/cloud/answer/13463073). Экран «unverified app» и лимит в 100 пользователей привязаны к sensitive/restricted scope [unverified app screen](https://support.google.com/cloud/answer/7454865), [publishing status](https://support.google.com/cloud/answer/15549945). **[вывод]** С одним `drive.appdata` (+`openid`/`email`) Robby в production не получит ни предупреждения, ни лимита и не обязан проходить верификацию.
- **Brand verification** (облегчённая) нужна, только если хотим показывать логотип и имя на экране согласия [verification requirements](https://support.google.com/cloud/answer/13463073). Для v0.1 не нужна.
- **Personal mode** работает только для аккаунта создателя проекта, без верификации и без экрана «unverified app». Выбор **необратим**: такой проект нельзя открыть другим пользователям или перевести в External [verification exceptions](https://support.google.com/cloud/answer/13464323). Подходит, только если Robby навсегда останется для одного Google-аккаунта. Распространяется ли на Personal mode 7-дневное ограничение, документация не говорит. Рекомендация: External + In production.
- Широкий scope `drive` (restricted) брать нельзя: «Only specific application types are permitted to use restricted scopes» [scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth). Он потребовал бы верификации и аудита безопасности.

## 4. Android Auto Backup как альтернатива

### Что даёт

- Включён по умолчанию для targetSdk 23+, хранит данные в скрытой папке Drive пользователя, до 25 МБ на приложение, не расходует квоту Drive. На Android 9+ шифруется сквозным шифрованием с использованием PIN/пароля экрана. [autobackup](https://developer.android.com/identity/data/autobackup)
- Настраивается через `android:dataExtractionRules` (для Android 12+ отдельные разделы `<cloud-backup>` и `<device-transfer>`). [autobackup](https://developer.android.com/identity/data/autobackup), [Android 12 backup changes](https://developer.android.com/about/versions/12/backup-restore)
- Не нужны ни OAuth, ни Cloud Console.

### Чем хуже для Robby

- **Время не управляется:** бэкап делается раз в 24 ч, когда устройство в простое, на Wi-Fi и пользователь включил бэкап в настройках. «a device might never back up». Хранится только последний бэкап. [autobackup, Backup schedule](https://developer.android.com/identity/data/autobackup)
- **Восстановление только при установке:** «Data is restored whenever the app is installed... The restore operation occurs after the APK is installed but before the app is available to be launched». Из приложения его не вызвать, кнопку «восстановить» в UI не сделать. [autobackup, Restore schedule](https://developer.android.com/identity/data/autobackup)
- **Новый телефон:** восстановиться можно из своих бэкапов или из «ancestral dataset», выбранного в мастере начальной настройки. «If the user didn't go through the device setup wizard, then the device can restore only from its own backups». [autobackup](https://developer.android.com/identity/data/autobackup) Если отдельный телефон-сервер настроен «с нуля», бэкап старого аппарата ему недоступен.
- **[вывод]** Мастер настройки переустанавливает только приложения из Play. Robby придётся ставить вручную после мастера. Документация называет восстановление при `adb install`, но про установку APK через системный установщик пакетов не говорит явно.
- Бэкап нельзя прочитать: «The backup data can't be read by the user or other apps», и формат переноса не контролируется. [autobackup](https://developer.android.com/identity/data/autobackup)
- Поведение зависит от производителя: на некоторых устройствах `allowBackup="false"` не отключает D2D-перенос. [Android 12 backup changes](https://developer.android.com/about/versions/12/backup-restore)

### Рекомендация

Auto Backup оставить включённым как бесплатную страховку (и для D2D-переноса кабелем при настройке нового телефона со старого), явно описав `dataExtractionRules`. Основным механизмом его не делать. Документация советует хранить учётные данные не в файлах, а в Block Store [autobackup, tip](https://developer.android.com/identity/data/autobackup). Это можно рассмотреть вместе с вопросом шифрования секретов.

## Что проверить на прототипе

1. Повторяется ли через `AuthorizationClient` в статусе Testing запрос согласия раз в 7 дней и пропадает ли он после перевода в In production.
2. `authorize()` из WorkManager без Activity: молча получает токен, когда согласие есть, и отдаёт `hasResolution()`, когда согласие отозвано.
3. Перенос: APK с тем же ключом на втором устройстве с тем же аккаунтом видит файл в `appDataFolder`.
4. Что происходит с `appDataFolder`, когда пользователь отзывает доступ в «Сторонние приложения и сервисы» аккаунта Google (документация говорит только об «uninstall from My Drive»).
5. Нужна ли в Branding ссылка на privacy policy, чтобы перевести External-приложение с несекретными scope в production.
