# Ограничения Android 12–15 для фоновой работы Robby

Исследование к тикету #2. Вопрос: что нужно sideload-приложению (minSdk 31, без root), чтобы на телефоне-сервере круглосуточно, без открытого UI, ловить входящие SMS, входящие и пропущенные звонки, события батареи и слать HTTP-запросы (Telegram).

Источники: developer.android.com (руководства, behavior changes, справочник API) и исходники AOSP (android.googlesource.com, ветки `android13-release`, `android15-release`, `main`). Ссылки стоят при каждом утверждении. Если утверждение выведено из кода AOSP, а не из документации, это сказано явно. Сборки производителей (Samsung, Xiaomi и т. д.) могут вести себя иначе, см. «Неопределённости».

## TL;DR

1. **Один постоянный foreground-сервис типа `specialUse`**, запускаемый из UI и из `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`. `dataSync` не подходит: с Android 15 у него лимит 6 ч в сутки, и его нельзя запускать из `BOOT_COMPLETED`.
2. **SMS и звонки ловим receiver'ами в манифесте**: `SMS_RECEIVED` и `PHONE_STATE` входят в список исключений из ограничений неявных broadcast'ов. Они будят приложение даже без работающего процесса.
3. **События батареи ловим только receiver'ом, зарегистрированным в коде работающего сервиса.** `ACTION_BATTERY_CHANGED` нельзя получить через манифест. `POWER_CONNECTED/DISCONNECTED` и `BATTERY_LOW/OKAY` не входят в исключения, поэтому при targetSdk ≥ 26 через манифест они тоже не приходят.
4. **Номер звонящего** приходит в `EXTRA_INCOMING_NUMBER` broadcast'а `PHONE_STATE`, если выданы **оба** разрешения: `READ_PHONE_STATE` и `READ_CALL_LOG`. Broadcast при этом приходит дважды, с номером и без. Альтернатива: `CallScreeningService` с ролью `ROLE_CALL_SCREENING`.
5. **Исключение из оптимизации батареи («Без ограничений») обязательно.** Оно разрешает перезапуск FGS из фона, сеть в Doze (если пропадёт питание) и выводит приложение из ограничений App Standby и корзины `restricted`.
6. **Restricted settings, главная ловушка Android 15.** Если APK установлен открытием файла (из браузера или файлового менеджера), запрос SMS-разрешений блокируется. Снимается через «О приложении → ⋮ → Разрешить доступ к настройкам с ограниченным доступом» и повторный запрос. На Android 13–14 под ограничение попадают только спецвозможности и подобное, SMS там не блокируется.
7. **Отключить «Приостанавливать работу, если приложение не используется» (гибернацию).** Неявные broadcast'ы (SMS) не считаются использованием. Через несколько месяцев без открытия UI система сбросит runtime-разрешения.
8. **`BOOT_COMPLETED` приходит только после первой разблокировки.** С PIN на телефоне-сервере после перезагрузки Robby молчит, пока кто-то не введёт PIN.

## 1. Разрешения

| Разрешение | Тип | Для чего | Примечание |
|---|---|---|---|
| `RECEIVE_SMS` | runtime (группа SMS) | `SMS_RECEIVED` | На Android 15+ под защитой Restricted settings (ECM), см. §5 |
| `READ_PHONE_STATE` | runtime | `PHONE_STATE`, `TelephonyCallback` | Без него `PHONE_STATE` не приходит |
| `READ_CALL_LOG` | runtime | номер в `EXTRA_INCOMING_NUMBER`, проверка пропущенного по `CallLog` | Без него номер не приходит |
| `READ_CONTACTS` | runtime | имя звонящего / отправителя (`PhoneLookup`); для `CallScreeningService` также чтобы видеть звонки от контактов | Опционально |
| `POST_NOTIFICATIONS` | runtime (Android 13+) | показывать уведомление FGS в шторке и собственные уведомления | Для запуска FGS не нужен |
| `FOREGROUND_SERVICE` | normal | любой FGS | |
| `FOREGROUND_SERVICE_SPECIAL_USE` | normal | FGS типа `specialUse` (Android 14+) | |
| `RECEIVE_BOOT_COMPLETED` | normal | автозапуск | |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | normal | системный диалог исключения из оптимизации | |
| `INTERNET`, `ACCESS_NETWORK_STATE` | normal | HTTP / Telegram | `ACCESS_NETWORK_STATE` обязателен для JobScheduler/WorkManager с сетевыми ограничениями при target 34+ |

Источники:
- `PHONE_STATE` требует `READ_PHONE_STATE` (`@RequiresPermission`), а номер приходит только при `READ_CALL_LOG`: [AOSP TelephonyManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/TelephonyManager.java).
- `SMS_RECEIVED_ACTION`: «Requires RECEIVE_SMS to receive»: [AOSP Telephony.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/provider/Telephony.java).
- `POST_NOTIFICATIONS`: [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission): «Apps don't need to request the POST_NOTIFICATIONS permission in order to launch a foreground service». Если разрешение не выдано, уведомление FGS видно только в Task Manager, а в шторке его нет.
- `ACCESS_NETWORK_STATE` для JobScheduler: [Android 14 behavior changes (target 34)](https://developer.android.com/about/versions/14/behavior-changes-14).
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS](https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).

**«Hard restricted» разрешения (SMS, Call log) для sideload не проблема.** Это ограничение уровня установщика: по умолчанию все restricted-разрешения в allowlist («Initially, all restricted permissions are allowlisted»), см. javadoc `SessionParams.setWhitelistedRestrictedPermissions` в [AOSP PackageInstaller.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/content/pm/PackageInstaller.java). Политика Google Play по SMS/Call log к sideload не применяется. Restricted settings (§5) — отдельный механизм.

## 2. Получение событий

### SMS

- Manifest-receiver на `android.provider.Telephony.SMS_RECEIVED` работает при любом targetSdk: `SMS_RECEIVED_ACTION` есть в [списке исключений неявных broadcast'ов](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions) («SMS recipient apps rely on these broadcasts»).
- Разбор: `Telephony.Sms.Intents.getMessagesFromIntent(intent)`. Extra `pdus` — массив PDU, из которых состоит сообщение ([AOSP Telephony.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/provider/Telephony.java)). Многочастное SMS нужно склеить из всех PDU одного intent'а.
- Быть SMS-приложением по умолчанию (`SMS_DELIVER`, `ROLE_SMS`) не нужно, и этого стоит избегать: `ROLE_SMS` тоже под защитой ECM (§5).
- Практика (не из первичного источника): защитить receiver атрибутом `android:permission="android.permission.BROADCAST_SMS"`, чтобы чужие приложения не подделывали SMS.

### Входящий и пропущенный звонок

- Manifest-receiver на `android.intent.action.PHONE_STATE` (`TelephonyManager.ACTION_PHONE_STATE_CHANGED`) тоже в [списке исключений](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
- Номер (из javadoc [AOSP TelephonyManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/TelephonyManager.java)):
  - `EXTRA_INCOMING_NUMBER` заполняется только у получателей с `READ_CALL_LOG` и `READ_PHONE_STATE`.
  - С обоими разрешениями приложение получит broadcast **дважды**: один раз с номером, один раз без. Порядок не гарантирован. Broadcast без extra нужно игнорировать (`hasExtra() == false`).
  - Для входящего номер гарантирован только на переходе `IDLE → RINGING`. Для скрытого номера extra содержит пустую строку.
  - Extra помечен `@Deprecated` с API 29. Вместо него рекомендуют `CallScreeningService`, но константа и поведение сохранены по крайней мере до `main`.
- `TelephonyCallback.CallStateListener` (API 31+) отдаёт только состояние без номера, поэтому номер всё равно берётся из broadcast'а или `CallScreeningService`.
- **Пропущенный звонок** — это переход `RINGING → IDLE` без `OFFHOOK`. Надёжнее подтвердить его записью в `CallLog.Calls` с `TYPE = MISSED_TYPE` (нужен `READ_CALL_LOG`). Запись в журнал звонков появляется с задержкой, поэтому читать её нужно с повтором или через `ContentObserver`. Это вывод из практики, для конкретной прошивки его нужно проверить.
- **Альтернатива: `CallScreeningService`** ([AOSP CallScreeningService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/telecomm/java/android/telecom/CallScreeningService.java)):
  - нужна роль `ROLE_CALL_SCREENING` через `RoleManager.createRequestRoleIntent`, роль одна на устройство;
  - Telecom биндит сервис до звонка, и приложение обязано ответить `respondToCall` в течение 5 с, иначе задерживается звонок;
  - сервис не видит звонки от контактов без `READ_CONTACTS` (проверено в [CallScreeningServiceFilter.java](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/android15-release/src/com/android/server/telecom/callfiltering/CallScreeningServiceFilter.java)) и не видит скрытые номера;
  - для v0.1 он избыточен, но это официальный путь и запасной вариант, если OEM вырежет номер из broadcast'а.

### Батарея

- `ACTION_BATTERY_CHANGED` — sticky broadcast: «You *cannot* receive this through components declared in manifests, only by explicitly registering for it with Context.registerReceiver()» ([AOSP Intent.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/content/Intent.java)). Вызов `registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))` синхронно возвращает последнее состояние.
- Extras ([AOSP BatteryManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/os/BatteryManager.java)): `EXTRA_LEVEL`/`EXTRA_SCALE` (уровень), `EXTRA_PLUGGED` (0 означает работу от батареи), `EXTRA_STATUS`, `EXTRA_HEALTH`, `EXTRA_TEMPERATURE`, `EXTRA_VOLTAGE`, `EXTRA_BATTERY_LOW`. **Температура приходит в десятых долях °C**: `BatteryService` кладёт туда `batteryTemperatureTenthsCelsius` ([AOSP BatteryService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/BatteryService.java)). Процент без события можно получить через `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`.
- `ACTION_POWER_CONNECTED/DISCONNECTED`, `ACTION_BATTERY_LOW/OKAY`. Javadoc в `Intent.java` по-прежнему говорит, что их можно ловить через манифест, но это устарело. Их **нет** в [списке исключений](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions), поэтому при targetSdk ≥ 26 manifest-receiver их не получит. Ловить их нужно в работающем процессе через `registerReceiver`.
- Android 14+: context-registered broadcast'ы ставятся в очередь, пока процесс в состоянии cached ([Android 14 behavior changes, все приложения](https://developer.android.com/about/versions/14/behavior-changes-all)). Процесс с FGS не cached, но это ещё одна причина держать receiver'ы батареи именно в сервисе.
- Android 14+ (target 34): у `registerReceiver` нужно указать `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED`. Исключение — receiver'ы только системных broadcast'ов ([Android 14 behavior changes, target 34](https://developer.android.com/about/versions/14/behavior-changes-14)). Проще всегда передавать `ContextCompat.RECEIVER_NOT_EXPORTED`.

**Итог по событиям:** SMS и звонки придут даже в «спящее» приложение (manifest-receiver поднимет процесс). Батарея придёт только пока жив процесс с FGS. Если FGS убит, события батареи теряются до перезапуска.

## 3. Foreground-сервис

### Тип (Android 14+)

С Android 14 (target 34) у каждого FGS должен быть объявлен тип и соответствующее `FOREGROUND_SERVICE_*`-разрешение ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)). Для Robby типы выглядят так:

| Тип | Подходит? | Почему |
|---|---|---|
| `specialUse` | **Да** | «Covers any valid foreground service use cases that aren't covered by the other foreground service types». Нет runtime-предусловий, нет таймаута, разрешён запуск из `BOOT_COMPLETED`. Нужен `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="..."/>`. Ревью значения бывает только в Play Console, к sideload оно не относится |
| `dataSync` | Нет | Android 15 (target 35): лимит 6 ч на 24 ч, затем `onTimeout`. Запуск из `BOOT_COMPLETED` запрещён ([Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)) |
| `remoteMessaging` | Частично | «Transfer text messages from one device to another» подходит для пересылки SMS, но не для батареи и звонков |
| `shortService` | Нет | ~3 минуты, без sticky |
| `phoneCall` | Нет (v0.1) | Нужен `MANAGE_OWN_CALLS` или `ROLE_DIALER`. Запрещён из `BOOT_COMPLETED` на 15+. Понадобится для VoIP в v0.2 |
| `connectedDevice` | v0.2 | Для связи с ESP32 по BT: нужен `BLUETOOTH_CONNECT` или аналог |
| `systemExempted` | Нет | Только для DO/PO, VPN, `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` и т. п. Иначе выбрасывается `ForegroundServiceTypeNotAllowedException` |

На Android 12–13 тип не обязателен. Через `ServiceCompat.startForeground(service, id, notification, type)` тип `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` стоит передавать только на API 34+ ([Launch FGS](https://developer.android.com/develop/background-work/services/fgs/launch)).

### Запуск из фона (Android 12+)

При target ≥ 31 FGS нельзя запустить из фона, иначе будет `ForegroundServiceStartNotAllowedException`. Исключения, важные для Robby ([Restrictions on starting FGS from background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)):
- переход из видимого состояния (пользователь открыл UI);
- получение `ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`;
- **«The user turns off battery optimizations for your app»**: с этим исключением FGS можно (пере)запустить из любого receiver'а, включая `SMS_RECEIVED` и `PHONE_STATE`;
- срабатывание exact alarm для действия по запросу пользователя.

`SMS_RECEIVED` и `PHONE_STATE` сами по себе исключениями из запрета на запуск FGS **не являются**. Без исключения из оптимизации батареи receiver не сможет поднять убитый сервис и должен уложиться в обычное окно broadcast'а (`goAsync`, ~10 с) или передать работу в expedited WorkManager.

### Поведение и ловушки FGS

- Android 13+: пользователь может остановить приложение через Task Manager в шторке. Процесс убивается целиком, колбэка нет, причина видна в `ApplicationExitInfo.REASON_USER_REQUESTED` ([Handle user-initiated stop](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)). Это не force-stop: manifest-receiver'ы продолжают работать, поэтому SMS/звонок поднимут процесс, который перезапустит FGS (при исключении из оптимизации).
- Android 13+: уведомление FGS можно смахнуть ([Android 13 behavior changes](https://developer.android.com/about/versions/13/behavior-changes-all)), а с Android 14 это относится и к `setOngoing(true)`, кроме заблокированного экрана ([Android 14 behavior changes](https://developer.android.com/about/versions/14/behavior-changes-all)). Сервис при этом продолжает работать.
- Ручное ограничение батареи («Restricted» в настройках батареи) запрещает запуск FGS и снимает существующие из foreground ([Android 13 behavior changes](https://developer.android.com/about/versions/13/behavior-changes-all)).
- Android 15: исключение по `SYSTEM_ALERT_WINDOW` теперь требует видимого overlay-окна, на него полагаться нельзя ([Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)).

## 4. Doze, App Standby, гибернация, автозапуск

### Doze и App Standby на телефоне на зарядке

- Doze включается, только если устройство **не на зарядке**, неподвижно и с выключенным экраном. Подключение зарядки выводит из Doze ([Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)). При подключении к питанию App Standby тоже снимается.
- Ограничения по корзинам App Standby (jobs, alarms, сеть) действуют только от батареи: «While the device is charging, the system doesn't impose these restrictions». **Исключение — корзина `restricted`**: её ограничения действуют и на зарядке ([App Standby Buckets](https://developer.android.com/topic/performance/appstandby)).
- Процесс с FGS: сеть без ограничений, но jobs и alarms по-прежнему лимитируются корзиной ([Power management resource limits](https://developer.android.com/topic/performance/power/power-details)). В Doze сеть ограничена («Restricted during doze»).
- **Практический вывод:** пока телефон на зарядке, Doze не мешает. Но сценарий «пропало питание дома» — это ровно тот момент, когда начнётся Doze, а уведомление о нём самое важное. Приложения из списка исключений «can use the network and hold partial wake locks during Doze and App Standby». Отсюда и требование исключения из оптимизации.

### Исключение из оптимизации батареи

- Проверка: `PowerManager.isIgnoringBatteryOptimizations(pkg)`. Запрос: `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` с `package:<pkg>` (нужно `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) или экран `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` ([Settings](https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)).
- Политика Play против прямого запроса к sideload не относится. К тому же «Task automation app» в таблице приемлемых случаев отмечен как «Acceptable» ([Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)).
- Что даёт исключение: разрешённый запуск FGS из фона (§3), сеть и wakelock в Doze, иммунитет к ограничениям корзин App Standby («Apps that are on the Doze exemption list are exempted from the App Standby Bucket-based restrictions»). Кроме того, приложения в режиме «unrestricted» исключены из попадания в корзину `restricted` ([App Standby Buckets](https://developer.android.com/topic/performance/appstandby)).
- Без исключения на Android 13+ приложение уходит в корзину `restricted` после **8 дней** без взаимодействия пользователя (45 дней на 12/12L). Там один job в день и один alarm в день, и это действует даже на зарядке. Для телефона-сервера, где UI открывают редко, это реальный риск.

### Гибернация приложения (Android 12+)

- Если несколько месяцев нет «использования», при target ≥ 31 система сбрасывает runtime-разрешения, останавливает jobs и alarms и чистит кэш ([App hibernation](https://developer.android.com/topic/performance/app-hibernation)).
- Явно **не считаются использованием**: получение неявного broadcast'а (а `SMS_RECEIVED` неявный), jobs и alarms. Про FGS в документации ничего не сказано, поэтому считаем, что он не спасает.
- Решение: проверять `PackageManagerCompat.getUnusedAppRestrictionsStatus()` и вести пользователя через `IntentCompat.createManageUnusedAppRestrictionsIntent()` (`startActivityForResult`) к переключателю «Pause app activity if unused» (13+) / «Remove permissions and free up space» (12).

### Автозапуск после перезагрузки

- `ACTION_BOOT_COMPLETED` (нужен `RECEIVE_BOOT_COMPLETED`) приходит **после того, как пользователь разблокировал устройство**. До разблокировки приходит только `ACTION_LOCKED_BOOT_COMPLETED`, и только компонентам с `directBootAware`, которым доступно лишь device-protected хранилище ([AOSP Intent.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/content/Intent.java)).
- **Следствие:** если на телефоне-сервере стоит PIN или графический ключ, после перезагрузки (обновление ОС, разряд в ноль при отключении света) Robby не запустится, пока кто-то не разблокирует телефон. Варианты: без экрана блокировки, или receiver'ы и сервис с `directBootAware` и хранением настроек и токена в device-protected storage. Это решение для отдельного тикета.
- Из `BOOT_COMPLETED` можно запускать FGS `specialUse`. Нельзя (target 35): `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` ([Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)).
- `ACTION_MY_PACKAGE_REPLACED` тоже даёт право запустить FGS. Его стоит ловить, чтобы сервис поднимался после обновления APK.

## 5. Restricted settings (Android 13+) и Enhanced Confirmation Mode (Android 15+)

### Когда приложение «под ограничением»

Установщик помечает приложение app-op'ом `OP_ACCESS_RESTRICTED_SETTINGS = MODE_ERRORED`, если источник пакета `PACKAGE_SOURCE_LOCAL_FILE` или `PACKAGE_SOURCE_DOWNLOADED_FILE` ([AOSP InstallPackageHelper.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/pm/InstallPackageHelper.java)). Системный Package Installer ставит `DOWNLOADED_FILE`, если у intent'а есть referrer (скачано браузером), и `LOCAL_FILE` в остальных случаях, когда APK открыт из файла ([AOSP InstallStaging.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/packages/PackageInstaller/src/com/android/packageinstaller/InstallStaging.java)). Итог: **любой APK, открытый тапом из «Загрузок» или файлового менеджера, попадает под ограничение.**

### Что блокируется

- **Android 13–14:** «специальные» настройки. По AOSP подтверждено для включения службы специальных возможностей ([Settings RestrictedPreferenceHelper.java](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android13-release/src/com/android/settings/accessibility/RestrictedPreferenceHelper.java)). По [справке Google](https://support.google.com/android/answer/12623953) пример тот же. Доступ к уведомлениям (notification listener) обычно упоминается как второй, но в этом проходе по исходникам не подтверждён. Для v0.1 Robby ни то ни другое не нужно.
- **Android 15+ (ECM):** список защищённых настроек в [AOSP EnhancedConfirmationService.java (android15-release)](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android15-release/service/java/com/android/ecm/EnhancedConfirmationService.java):
  - runtime-разрешения **`SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, `READ_CELL_BROADCASTS`** (группа SMS);
  - `BIND_DEVICE_ADMIN`;
  - app-ops: спецвозможности, доступ к уведомлениям, `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`, `LOADER_USAGE_STATS`;
  - роли `ROLE_DIALER`, `ROLE_SMS`;
  - в `main` (Android 16+) добавлено ещё `REQUEST_INSTALL_PACKAGES`.

  Флаг `enhanced_confirmation_mode_apis_enabled` включён в релизных конфигурациях Android 15 и 16 (`ap3a`, `ap4a`, `bp1a` в [build/release](https://android.googlesource.com/platform/build/release/+/refs/heads/main/aconfig/)). Окно запроса разрешений (`GrantPermissionsActivity.blockRestrictedPermissions`) на Android 15+ не выдаёт ограниченное разрешение и показывает диалог ECM ([AOSP GrantPermissionsActivity.java](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android15-release/PermissionController/src/com/android/permissioncontroller/permission/ui/GrantPermissionsActivity.java)).
- **Не защищены** (нужное Robby): `READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`, `POST_NOTIFICATIONS`, исключение из оптимизации батареи, роль `ROLE_CALL_SCREENING`. Это следует из отсутствия в списке выше.

### Как снять

1. Запросить разрешение (или попытаться включить настройку). Система покажет диалог «Настройка с ограниченным доступом». Только после этого показа ECM переводит приложение в состояние `GUARDED_AND_ACKNOWLEDGED`, когда снятие становится возможным: `clearRestriction` бросает исключение, если состояние иное (тот же EnhancedConfirmationService.java).
2. «Настройки → Приложения → Robby → ⋮ → Разрешить доступ к настройкам с ограниченным доступом» ([справка Google](https://support.google.com/android/answer/12623953)), затем подтверждение.
3. Повторить запрос разрешения.

### Как избежать

- **`adb install`** с компьютера: источник пакета не `LOCAL_FILE`/`DOWNLOADED_FILE`, и на Android 13–14 ограничение не ставится. На Android 15 решение принимается лениво. В AOSP-конфигурации (пустой `enhanced-confirmation.xml`, то есть «доверять всем установщикам») такой пакет не считается guarded ([логика `isPackageEcmGuarded`](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android15-release/service/java/com/android/ecm/EnhancedConfirmationService.java), [enhanced-confirmation.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/data/etc/enhanced-confirmation.xml)). Если же OEM прописал доверенные установщики, пакет без установщика (adb) окажется guarded. Это вывод из кода, на целевом телефоне его нужно проверить.
- Установка через сторонний магазин, использующий сессии (F-Droid, Obtainium и т. п.), тоже не ставит `LOCAL_FILE`. Оговорка про OEM та же.
- Для v0.1 надёжнее всего, чтобы онбординг сам вёл пользователя по шагам «Как снять». Это работает при любом способе установки.

## 6. Прочие ловушки

- **Data Saver:** даже у исключённого из оптимизации приложения сеть в фоне ограничена, если включена экономия трафика ([Power management resource limits](https://developer.android.com/topic/performance/power/power-details)). Для мобильного интернета на телефоне-сервере нужно проверить или выключить.
- **Exact alarms:** с Android 14 `SCHEDULE_EXACT_ALARM` по умолчанию запрещён для новых установок с target ≥ 33 ([Android 14 behavior changes](https://developer.android.com/about/versions/14/behavior-changes-all)). Если сценариям понадобятся триггеры по времени, это отдельное разрешение или экран настроек.
- **Android 16:** JobScheduler/WorkManager-квоты считаются и для приложений с работающим FGS ([Power management resource limits](https://developer.android.com/topic/performance/power/power-details)). Отправку в Telegram лучше делать прямо в FGS (корутины плюс ретраи с журналом), а не в WorkManager.
- **TLS 1.0/1.1** запрещены при target 35 ([Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)). Для `api.telegram.org` это неважно.
- **Агрессивные OEM-оптимизаторы** (Xiaomi/MIUI, Huawei, Samsung «спящие приложения»): вне AOSP, первичных источников нет. Нужен ручной чек-лист под конкретный аппарат. Справочно см. неофициальный dontkillmyapp.com (низкое доверие).

## 7. Рекомендуемая конфигурация для v0.1

**Манифест:**
- `<uses-permission>`: `RECEIVE_SMS`, `READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS` (опц.), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`.
- `<service android:foregroundServiceType="specialUse" android:exported="false">` с `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
- receiver'ы в манифесте:
  - `SMS_RECEIVED` (`android:permission="android.permission.BROADCAST_SMS"`, `exported="true"`);
  - `PHONE_STATE`;
  - `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` (для старта FGS);
  - опционально `LOCKED_BOOT_COMPLETED` с `directBootAware`, если решим работать до разблокировки.

**В FGS:** `registerReceiver` для `BATTERY_CHANGED`, `POWER_CONNECTED/DISCONNECTED`, `BATTERY_LOW/OKAY` (с `RECEIVER_NOT_EXPORTED`). Receiver'ы SMS и звонков передают событие в движок сценариев. Если сервиса нет, они его поднимают (это законно при исключении из оптимизации).

**Онбординг (чек-лист в UI с живыми статусами):**
1. Runtime-разрешения.
2. Если SMS заблокирован ECM, инструкция «Разрешить настройки с ограниченным доступом».
3. Исключение из оптимизации батареи.
4. Отключение гибернации.
5. Уведомления.
6. Предупреждение про экран блокировки и Data Saver.
7. OEM-специфичные пункты.

## Неопределённости и что проверить на устройстве

- Поведение ECM при `adb install` на конкретной прошивке. Проверка: `adb shell appops get <pkg> ACCESS_RESTRICTED_SETTINGS` и попытка выдать `RECEIVE_SMS`.
- Считается ли работающий FGS «использованием» для гибернации. Документация молчит, поэтому закладываемся на «нет».
- Задержка появления записи о пропущенном звонке в `CallLog` на целевом аппарате.
- Подтвердить по исходникам, что на Android 13 доступ к уведомлениям тоже под Restricted settings (для v0.1 не нужно).
- Android 16/17: в этом проходе отдельно не исследовались, кроме упомянутого выше. Если телефон-сервер будет на 16+, нужен короткий дополнительный проход по behavior changes.
