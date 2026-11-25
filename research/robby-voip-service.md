# VoIP-сервис Robby: запуск из фона и звук от ESP32

Исследование к тикету [#16](https://github.com/SBKubric/robby/issues/16) (карта: [#1](https://github.com/SBKubric/robby/issues/1)). Дата: 2026-11-25.

Контекст: v0.2 «Удалённый приём звонка», схема «мост через телефон-сервер». ESP32-WROVER работает Bluetooth HFP-гарнитурой телефона-сервера и гонит PCM GSM-звонка (8 кГц, узкая полоса) по UDP в домашней сети в Robby. Robby сам SIP-клиент: регистрируется на `sip.linphone.org` и звонит на офисный Linphone (Android), который звонит нативно по push. Кодек G.711. Робби уже держит постоянный FGS `specialUse` и исключён из оптимизации батареи (см. [#2](https://github.com/SBKubric/robby/issues/2)).

## Вопрос

1. Можно ли в момент входящего GSM-звонка поднять VoIP из фона: какой тип FGS, какие исключения из запрета фонового старта применимы на Android 12–15?
2. Linphone SDK или PJSIP: можно ли подать свой источник и приёмник звука (PCM от ESP32 по UDP) вместо микрофона и динамика; лицензии.
3. Регистрация на `sip.linphone.org`, NAT (ICE/TURN), как достучаться до офисного Linphone.
4. Нужен ли Robby `ConnectionService` (self-managed), чтобы не конфликтовать с GSM-звонком на этом же телефоне?

## Короткий ответ

**Реализуемо, и проще, чем казалось: VoIP-звонку Robby не нужны ни новый FGS, ни микрофон, ни Telecom.**

- **FGS.** SIP-стек живёт внутри уже работающего FGS `specialUse`. Он не трогает микрофон и динамик, поэтому типы `microphone`/`phoneCall` не нужны. Если всё же понадобится отдельный FGS в момент звонка, его старт из фона разрешён двумя независимыми исключениями: «пользователь отключил оптимизацию батареи» (документировано) и «у процесса уже есть обычный FGS» (флаг `PROCESS_CAPABILITY_BFSL` в AOSP). Тип `microphone` из фона на Android 14+ запустить **нельзя** (`SecurityException`), но он и не нужен.
- **SIP-стек: PJSIP (pjsua2).** Там есть ровно нужный API: `AudDevManager.setNullDev()` (звуковое устройство-заглушка, «will not interract with any hardware») и `AudioMediaPort` с колбэками `onFrameRequested` / `onFrameReceived` (с PJSIP 2.14, доступен из Java/Kotlin через SWIG director). В Linphone SDK такого Java-API нет: свой источник звука там только через C-плагин звуковой карты mediastreamer2 (`ms_snd_card_manager_register_desc`) и JNI.
- **Telecom не трогать.** Если Robby оформит SIP-звонок как self-managed `ConnectionService`, Telecom при исходящем вызове **поставит GSM-звонок на удержание** (`makeRoomForOutgoingCall` → `liveCall.hold()`). Robby должен быть «невидимым» для Telecom и AudioManager: без `ConnectionService`, без audio focus, без `setMode`.
- **Офисная сторона.** Аккаунты `sip.linphone.org` в стоковом Linphone настроены на `media_encryption=srtp` + `media_encryption_mandatory=1` и `transport=tls`. Значит, Robby обязан предлагать **SDES-SRTP поверх SIP/TLS**, иначе офис отклонит вызов. ICE со STUN `stun.linphone.org` есть, TURN у Linphone нет.

## 1. Запуск из фона и тип FGS

### Нужен ли новый FGS вообще

Нет. Ограничения Android 12+ касаются **старта** FGS из фона. Robby уже держит постоянный FGS `specialUse`, который стартует из UI / `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (решение [#2](https://github.com/SBKubric/robby/issues/2)). Процесс с таким FGS имеет состояние `PROCESS_STATE_FOREGROUND_SERVICE` и может держать сеть и потоки. SIP-стеку больше ничего не нужно, если он не открывает микрофон и динамик (раздел 2).

Описание `specialUse` — «any valid foreground service use cases that aren't covered by the other foreground service types». Подтип в `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` проверяет только ревью Google Play ([FGS types: Special use](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)), для sideload это формальность. Текст подтипа стоит дополнить VoIP-мостом.

### Если всё же отдельный FGS при звонке

Правило: «Apps that target Android 12 (API level 31) or higher can't start foreground services while the app is running in the background, except for a few special cases», иначе `ForegroundServiceStartNotAllowedException` ([Restrictions on starting FGS from background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)). Для Robby в момент звонка подходят два исключения:

1. **«The user turns off battery optimizations for your app.»** Это документированное исключение из того же списка, а у Robby исключение из оптимизации и так обязательно (ловушка №2 в [#2](https://github.com/SBKubric/robby/issues/2)).
2. **У процесса уже работает обычный (не `shortService`) FGS.** В публичном списке этого нет, но в AOSP это так. `OomAdjuster` выдаёт процессу с не-short FGS флаг `PROCESS_CAPABILITY_BFSL` («Flag used to indicate whether an app is allowed to start a foreground service from the background»; «procstates <= BFGS (i.e. BFGS, FGS, BTOP, TOP, ...) are BFSL-allowed») ([ActivityManager.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/app/ActivityManager.java), [OomAdjuster.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/am/OomAdjuster.java): `capabilityFromFGS |= PROCESS_CAPABILITY_BFSL` для «fg-service»). `ActiveServices.shouldAllowFgsStartForegroundNoBindingCheckLocked` разрешает старт, если `uidBfsl && procstate <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE` ([ActiveServices.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/am/ActiveServices.java)). Исключение недокументированное, поэтому рассчитывать стоит в первую очередь на п. 1.

Кроме того, если процесс Robby убит, а FGS перезапускается из receiver'а `PHONE_STATE`, работает исключение п. 1 (так и было заложено в [#2](https://github.com/SBKubric/robby/issues/2)).

### Какие типы годятся и какие нет

По [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types):

| Тип | Условие | Годится? |
|---|---|---|
| `specialUse` (уже есть) | `FOREGROUND_SERVICE_SPECIAL_USE`, runtime-условий нет | **Да, достаточно его.** |
| `phoneCall` | `FOREGROUND_SERVICE_PHONE_CALL` + `MANAGE_OWN_CALLS` в манифесте **или** роль `ROLE_DIALER`. Назначение: «Continue an ongoing call using the `ConnectionService` APIs». На Android 15 его нельзя запускать из `BOOT_COMPLETED`. | Технически можно (`MANAGE_OWN_CALLS` — normal-разрешение), но тип рассчитан на `ConnectionService`, а он нам вреден (раздел 4). Пользы нет. |
| `microphone` | `RECORD_AUDIO`, «subject to while-in-use restrictions… you cannot create a `microphone` foreground service while your app is in the background» | **Нет.** На Android 14+ создание из фона бросает `SecurityException` даже при исключениях из фонового старта ([while-in-use](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions)); на 12–13 сервис создастся, но без доступа к микрофону. Исключения для while-in-use (виджет, уведомление, системный компонент и т. п.) к звонку не подходят. Микрофон Robby не нужен. |
| `connectedDevice` | `CHANGE_WIFI_STATE` / `CHANGE_NETWORK_STATE` и т. п. | Можно, но ничего не даёт по сравнению с `specialUse`. |
| `mediaPlayback` | нет условий | Не по назначению; на Android 15 запрещён из `BOOT_COMPLETED`. |

Итог: **не заводить новый FGS и не менять тип; весь VoIP — в существующем `specialUse`.** Разрешение `RECORD_AUDIO` не нужно.

### Чем держать CPU и Wi-Fi во время звонка

- **CPU.** FGS не держит CPU. На время звонка нужен `PowerManager.PARTIAL_WAKE_LOCK` (разрешение `WAKE_LOCK`), иначе с выключенным экраном медиапоток таймерного «null-устройства» PJSIP может проседать ([PowerManager](https://developer.android.com/reference/android/os/PowerManager#PARTIAL_WAKE_LOCK)). Doze на зарядке не наступает, а исключение из оптимизации батареи уже есть.
- **Wi-Fi.** Здесь реальный риск. `WIFI_MODE_FULL_LOW_LATENCY` «is only active when the screen is on» и «only active when the acquiring app is running in the foreground». `WIFI_MODE_FULL_HIGH_PERF` (который раньше работал и с выключенным экраном, «a voice connection needs to be kept active even after the device screen goes off») с API 34 deprecated и «automatically replaced with `WIFI_MODE_FULL_LOW_LATENCY` with all the restrictions» ([WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY)). То есть на Android 14+ фоновое приложение с выключенным экраном **не может запретить Wi-Fi power save**. При потоке 50 пакетов/с драйвер обычно не засыпает, но задержку и джиттер UDP ESP32 → телефон и RTP нужно измерить на спайке (для Android 12–13 брать `HIGH_PERF`).

## 2. SIP-стек и свой источник звука

Звук GSM-звонка приложению недоступен (`CAPTURE_AUDIO_OUTPUT` только для системы, см. [#6](https://github.com/SBKubric/robby/issues/6)). Кроме того, во время голосового звонка («audio mode … `MODE_IN_CALL` or `MODE_IN_COMMUNICATION`») обычное приложение микрофон не получает: «The call always receives audio», остальные получают тишину ([Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input#voice_call_ordinary_app)). Поэтому SIP-стек Robby **вообще не должен открывать звуковые устройства Android**: звук только из UDP и обратно в UDP.

### PJSIP (pjsua2) — рекомендуется

- **Без железа:** `AudDevManager::setNullDev()` — «Set pjsua to use null sound device. The null sound device only provides the timing needed by the conference bridge, and will not interract with any hardware» ([pjsua2/media.hpp](https://github.com/pjsip/pjproject/blob/master/pjsip/include/pjsua2/media.hpp)). Есть ещё `setNoDev()`, отдающий порт конференц-моста приложению.
- **Свой источник и приёмник:** класс `AudioMediaPort` (появился в PJSIP 2.14, в 2.13 его нет): `createPort(name, MediaFormatAudio)` регистрирует порт в конференц-мосте. Колбэк `onFrameRequested(MediaFrame&)` вызывается, когда мост просит кадр (сюда кладём PCM от ESP32), `onFrameReceived(MediaFrame&)` — когда порт получает кадр (это звук из офиса, отправляем его в ESP32) (там же). В SWIG-интерфейсе стоит `%feature("director") AudioMediaPort` ([pjsua2.i](https://github.com/pjsip/pjproject/blob/master/pjsip-apps/src/swig/pjsua2.i)), то есть колбэки переопределяются прямо в Kotlin/Java. Схема: `AudioMediaPort.startTransmit(call.getAudioMedia())` и `callAudio.startTransmit(port)`.
- **Формат:** `EpConfig.medConfig.clockRate` / `sndClockRate` = 8000 и ptime 20 мс дают 160 сэмплов / 320 байт PCM16 на кадр, ровно как CVSD 8 кГц от ESP32. G.711 (PCMU/PCMA) в PJSIP встроен. Эхоподавление на стороне Robby лучше отключить (`ecTailLen = 0`): эхо гасит телефонная сеть, а у ESP32 есть `AT+NREC=0` ([#5](https://github.com/SBKubric/robby/issues/5)).
- **NAT и шифрование:** PJSIP реализует «SIP, SDP, RTP, STUN, TURN, and ICE» ([README](https://github.com/pjsip/pjproject)), SRTP (SDES) и TLS (через OpenSSL).
- **Сборка:** готовых бинарников для Android нет. Собирается из исходников (NDK + CMake toolchain), результат — «`libpjsua2.so` and the `org.pjsip.pjsua2` Java classes»; сборка AAR помечена как экспериментальная ([Android build instructions](https://docs.pjsip.org/en/latest/get-started/android/build_instructions.html)). Для TLS/SRTP нужно собрать с OpenSSL.
- **Лицензия:** GPLv2 (файл [COPYING](https://github.com/pjsip/pjproject/blob/master/COPYING)), коммерческая — отдельно. Для личного sideload ограничений нет. При публикации APK или исходников Robby приложение должно распространяться под GPL-совместимой лицензией.

### Linphone SDK — запасной

- **Плюсы:** готовый AAR `org.linphone:linphone-sdk-android` из `https://download.linphone.org/maven_repository` (стоковый Linphone сейчас на 5.6) ([linphone-android settings.gradle.kts](https://github.com/BelledonneCommunications/linphone-android/blob/master/settings.gradle.kts), [libs.versions.toml](https://github.com/BelledonneCommunications/linphone-android/blob/master/gradle/libs.versions.toml)). Гарантированная совместимость с `sip.linphone.org` (SRTP, ICE, AVPF, bundle).
- **Своего аудио-API на Java нет.** В публичном C API есть только выбор существующих устройств (`linphone_core_set_input_audio_device` / `..._output_...`) и режим «файлы вместо звуковой карты» (`linphone_core_set_use_files` + WAV в `set_play_file` / `set_record_file`) ([liblinphone core.h](https://github.com/BelledonneCommunications/liblinphone/blob/master/include/linphone/core.h)). Это не потоковый ввод. Потоковый источник делается только своей звуковой картой mediastreamer2 на C: `ms_snd_card_manager_register_desc(MSSndCardManager*, MSSndCardDesc*)` ([mssndcard.h](https://gitlab.linphone.org/BC/public/mediastreamer2/-/blob/master/include/mediastreamer2/mssndcard.h)), фабрика берётся через `linphone_core_get_ms_factory`. Нужны NDK, заголовки той же версии SDK и JNI-прослойка.
- **Сам трогает AudioManager.** Java-хелпер SDK по умолчанию запрашивает audio focus (`AUDIOFOCUS_GAIN_TRANSIENT`, `USAGE_VOICE_COMMUNICATION`) и ставит `MODE_IN_COMMUNICATION`, а при потере фокуса ставит звонки на паузу (`android_pause_calls_when_audio_focus_lost`, по умолчанию true) ([AudioHelper.java, AndroidPlatformHelper.java](https://gitlab.linphone.org/BC/public/liblinphone/-/tree/master/wrappers/java/classes/org/linphone/core/tools)). Отключается конфигом `[audio] android_disable_audio_focus_requests=1`, `android_monitor_audio_devices=0`. Так делает и сам стоковый Linphone ([linphonerc_factory](https://github.com/BelledonneCommunications/linphone-android/blob/master/app/src/main/assets/linphonerc_factory)).
- **Лицензия:** AGPLv3 или коммерческая ([liblinphone README / LICENSE.txt](https://gitlab.linphone.org/BC/public/liblinphone)). Для личного sideload допустимо, но AGPL строже GPL.

**Вывод:** PJSIP — потому что свой звук подаётся на Kotlin без C-кода и без звуковой подсистемы Android. Цена: собственная сборка `.so` с OpenSSL. Linphone SDK оставить на случай, если PJSIP не договорится с `sip.linphone.org`.

## 3. `sip.linphone.org`, NAT и офисный Linphone

- **Что ждёт офисный Linphone.** Стоковое приложение при подключении аккаунта `sip.linphone.org` применяет: `reg_proxy=<sip:sip.linphone.org;transport=tls>`, `media_encryption=srtp`, **`media_encryption_mandatory=1`**, `avpf=1`, `rtp_bundle=1`, NAT-политика `stun_server=stun.linphone.org`, `protocols=stun,ice`, `push_notification_allowed=1` ([assistant_linphone_default_values](https://github.com/BelledonneCommunications/linphone-android/blob/master/app/src/main/assets/assistant_linphone_default_values)). Robby должен звонить по **SIP/TLS с SDES-SRTP** (в PJSIP `AccountConfig.mediaConfig.srtpUse = PJMEDIA_SRTP_MANDATORY`, `srtpSecureSignaling` требует TLS). Иначе офис отклонит INVITE. Совместимость с `avpf=1` / bundle проверить на спайке.
- **Push в офис** — забота сервера. Flexisip у `sip.linphone.org` будит Linphone по push сам («we manage the push mechanism for you», см. [#6](https://github.com/SBKubric/robby/issues/6)). Robby просто шлёт INVITE на `sip:<офис>@sip.linphone.org`; push-параметры нужны только тому, кто принимает.
- **ICE/TURN.** «ICE is always on, and the STUN server is configured to stun.linphone.org», «stun.linphone.org is only a STUN server now». Медиа-релей Flexisip «isn't really a standard» и глючит при двойном IPv4/IPv6 (Belledonne, рассылка linphone-users, 2020-07, [архив](https://lists.libreplanet.org/archive/html/linphone-users/2020-07/msg00020.html)). Более свежих сведений о TURN для `sip.linphone.org` в первоисточниках не нашлось. Значит, Robby включает ICE + STUN `stun.linphone.org`. Если в офисе симметричный NAT или CGNAT, ICE может не сойтись, и тогда нужен свой TURN (например, coturn), что противоречит «без своего бэкенда».
- **Регистрация в фоне.** Для исходящего вызова регистрация не обязательна: INVITE через прокси авторизуется digest-ответом на 407. Но держать её полезно: быстрее старт, и на будущее можно принимать звонки из офиса. В FGS при исключении из оптимизации батареи и на зарядке сеть не режется (см. [#2](https://github.com/SBKubric/robby/issues/2)), поэтому регистрация по TLS с keepalive живёт. Стоковый Linphone ставит `reg_expires=31536000`, потому что полагается на push. Robby без push лучше взять короткий `expires` (минуты) и keepalive.
- **Аккаунт.** Robby нужен **отдельный** аккаунт `sip.linphone.org`, не офисный. На [subscribe.linphone.org](https://subscribe.linphone.org/) использование описано только «using the Linphone app» и «we do not offer any service guarantee». Явного разрешения или запрета на сторонние SIP-клиенты нет, так что это проверяется спайком.

## 4. Нужен ли `ConnectionService` / self-managed

**Нет, и он вреден.**

- AOSP Telecom, `CallsManager.makeRoomForOutgoingCall`: если уже есть «живой» звонок (`LIVE_CALL_STATES` = CONNECTING, DIALING, ACTIVE, …) из другого `PhoneAccount`, а новый звонок self-managed, Telecom выполняет «Try to hold the live call before attempting the new outgoing call» → `liveCall.hold(...)`. Если удержать нельзя — «There's no room», и исходящий не создаётся ([CallsManager.java](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/CallsManager.java)). То есть self-managed SIP-звонок Robby либо поставит GSM-звонок на удержание, либо не создастся. Симметрично, ответ на GSM-звонок при активном self-managed вызове удержит или прервёт уже его.
- Self-managed звонок к тому же переводит аудиосистему в режим связи и может перехватить маршрут на Bluetooth, то есть на ту самую HFP-гарнитуру ESP32.
- PJSIP с `setNullDev()` не обращается ни к Telecom, ни к `AudioManager`, ни к audio focus. Для Android это обычный сетевой трафик процесса с FGS, а GSM-звонок спокойно идёт по HFP в ESP32. `MANAGE_OWN_CALLS` и `ConnectionService` не нужны.

## Рекомендуемая схема для v0.2

1. Существующий FGS `specialUse` (+ `WAKE_LOCK` на время звонка, Wi-Fi lock `HIGH_PERF` на 12–13).
2. В нём PJSIP (pjsua2, ≥ 2.14, с OpenSSL): `setNullDev()`, `clockRate=8000`, PCMU/PCMA, SIP/TLS, SDES-SRTP mandatory, ICE + STUN `stun.linphone.org`, аккаунт Robby на `sip.linphone.org`.
3. `AudioMediaPort` ↔ UDP-сокет к ESP32: кольцевой буфер с адаптивной глубиной 40–80 мс.
4. Триггер: `RINGING` из `PHONE_STATE` (уже есть) или управляющее сообщение от ESP32 → INVITE в офис. Когда офис ответил, ESP32 отвечает на GSM (`ATA`).
5. Никакого `ConnectionService`, `RECORD_AUDIO`, audio focus и `setMode`.

## Риски и открытые вопросы

1. **Сторонний SIP-клиент на `sip.linphone.org`** (TLS, SRTP-mandatory, AVPF/bundle у офисной стороны) — главный риск совместимости. Первый спайк: PJSIP на Linux/Android звонит на офисный Linphone.
2. **NAT без TURN.** ICE со STUN может не сойтись, если в офисе симметричный NAT; релей Flexisip нестандартный. Если не сойдётся — свой TURN или платный SIP-провайдер.
3. **Wi-Fi power save на Android 14+.** Фоновое приложение с выключенным экраном не может держать low-latency Wi-Fi lock. Нужно измерить задержку и джиттер ESP32 → Robby → офис.
4. **Дрейф часов.** SCO-часы ESP32 и таймер null-устройства PJSIP на телефоне независимы. На длинном звонке буфер переполнится или опустеет, если не выбрасывать или не дублировать сэмплы (инженерная оценка, не из источников).
5. **Время дозвона.** Push-пробуждение офисного Linphone плюс установка ICE должны уложиться до того, как оператор переведёт GSM-звонок на автоответчик. Решать на уровне потока «звонок → мост».
6. **Сборка PJSIP под Android** (NDK, SWIG, OpenSSL, 4 ABI) — разовая, но заметная работа; готового AAR нет.
7. Исключение «процесс уже с FGS» (`PROCESS_CAPABILITY_BFSL`) недокументировано. Опираться на исключение из оптимизации батареи, а ещё лучше не стартовать новый FGS вовсе.

## Источники

- Android: [Restrictions on starting FGS from background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types), [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager), [PowerManager](https://developer.android.com/reference/android/os/PowerManager), [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input).
- AOSP (android15-release): [ActivityManager.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/app/ActivityManager.java), [OomAdjuster.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/am/OomAdjuster.java), [ActiveServices.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/am/ActiveServices.java); Telecom: [CallsManager.java](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/CallsManager.java).
- PJSIP: [pjsua2/media.hpp](https://github.com/pjsip/pjproject/blob/master/pjsip/include/pjsua2/media.hpp), [pjsua2/endpoint.hpp](https://github.com/pjsip/pjproject/blob/master/pjsip/include/pjsua2/endpoint.hpp), [pjsua2.i](https://github.com/pjsip/pjproject/blob/master/pjsip-apps/src/swig/pjsua2.i), [Android build](https://docs.pjsip.org/en/latest/get-started/android/build_instructions.html), [COPYING](https://github.com/pjsip/pjproject/blob/master/COPYING).
- Linphone: [liblinphone](https://gitlab.linphone.org/BC/public/liblinphone) (core.h, wrappers/java/.../tools/audio/AudioHelper.java, AndroidPlatformHelper.java, LICENSE.txt), [mediastreamer2 mssndcard.h](https://gitlab.linphone.org/BC/public/mediastreamer2/-/blob/master/include/mediastreamer2/mssndcard.h), [linphone-android assets](https://github.com/BelledonneCommunications/linphone-android/tree/master/app/src/main/assets), [subscribe.linphone.org](https://subscribe.linphone.org/), [linphone-users 2020-07](https://lists.libreplanet.org/archive/html/linphone-users/2020-07/msg00020.html).
