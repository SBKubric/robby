# Выбор канала моста для удалённого приёма звонка

Исследование к тикету [#6](https://github.com/SBKubric/robby/issues/6) (карта: [#1](https://github.com/SBKubric/robby/issues/1)). Дата: 2026-11-25.

## Вопрос

Через какой VoIP-канал (в терминах `GLOSSARY.md`: **канал моста**) GSM-звонок, пойманный ESP32-устройством в роли Bluetooth HFP-гарнитуры телефона-сервера, дойдёт до пользователя в офисе так, чтобы тот ответил **нативной кнопкой** VoIP-приложения? Сравниваются SIP, Telegram-звонки, WebRTC-сервисы и мост через сам телефон-сервер.

## Короткий ответ

**Рекомендация для v0.2: SIP, где SIP-клиентом выступает сама прошивка ESP32 (`esp_sip_service` из ESP-ADF), регистратор берётся у стороннего SIP-сервиса (по умолчанию бесплатный `sip.linphone.org`), а в офисе звонок принимает официальное приложение Linphone с push-уведомлениями.**

- Это единственный вариант, где у ESP32 есть официальный стек от производителя чипа (SIP + RTP + SRTP, G.711/Opus), а у офисной стороны готовое приложение с нативным экраном входящего вызова и push.
- Своего бэкенда нет: регистратор и push у стороннего сервиса, так же как сервером Telegram владеет Telegram.
- Запасной вариант, если ESP32 не потянет Wi-Fi + SCO + SIP одновременно: **мост через телефон-сервер** (ESP32 только гоняет PCM по Wi-Fi, SIP-клиент работает в Robby). Канал при этом тот же, SIP.
- Telegram-звонки и WebRTC-сервисы (Google Meet, Jitsi) отпадают: у первых нет API для бота и нет реализации под ESP32, у вторых нет API «позвонить пользователю».

## Исходное ограничение: звук звонка есть только у гарнитуры

Обычное приложение на Android не может само снять звук GSM-звонка. Источники `VOICE_CALL`, `VOICE_UPLINK`, `VOICE_DOWNLINK` требуют разрешения `CAPTURE_AUDIO_OUTPUT`, которое «reserved for use by system components and is not available to third-party applications» ([MediaRecorder.AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_CALL)). Root запрещён, поэтому звук звонка можно получить только на стороне Bluetooth-гарнитуры. Отсюда ESP32-устройство в любом варианте: оно и есть точка, где звонок превращается в PCM.

Встроенный SIP-стек Android тоже не помощник: `android.net.sip.SipManager` помечен deprecated в API 31 и «should not be used as the basis of future VOIP apps» ([SipManager](https://developer.android.com/reference/android/net/sip/SipManager)). minSdk проекта как раз 31.

## Звено HFP на ESP32 (общее для всех вариантов)

Факты из примера `hfp_hf` ESP-IDF ([README](https://github.com/espressif/esp-idf/blob/master/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md)) и заголовка `esp_hf_client_api.h` ([исходник](https://github.com/espressif/esp-idf/blob/master/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h)):

- Два пути звука SCO: **PCM** (звук уходит на GPIO в I2S, «will not go through the Bluedroid») и **vHCI** (звук идёт через Bluedroid «to the Application layer»). Для моста в сеть нужен **vHCI**: только так PCM попадает в код прошивки.
- Кодеки: CVSD (8 кГц, по умолчанию) и mSBC (16 кГц, «Wideband Speech»). mSBC доступен только при vHCI, его кодирует Bluedroid программно.
- API для моста есть: события `ESP_HF_CLIENT_RING_IND_EVT` и `ESP_HF_CLIENT_CIND_CALL_SETUP_EVT` (входящий звонок), `esp_hf_client_answer_call()` / `esp_hf_client_reject_call()`, приём звука через `esp_hf_client_register_audio_data_callback()`, отправка через `esp_hf_client_audio_buff_alloc()` + `esp_hf_client_audio_data_send()`, ресемплер `esp_hf_client_pcm_resample*()` (только при vHCI).
- В README `hfp_hf` сказано: «Currently, ESP32-S31 HFP only supports HCI». То есть кроме оригинального ESP32 Classic BT / HFP есть у нового **ESP32-S31**; документация ESP-ADF прямо советует «use ESP32-S31 when classic Bluetooth is required» ([Voice Intercom and VoIP](https://github.com/espressif/esp-adf/blob/master/docs/en/solution-center/voice-call.rst)). Это расходится с заметкой карты «Classic BT / HFP есть только у оригинального ESP32» (см. открытые вопросы).

Сосуществование Wi-Fi и Classic BT на ESP32: таблица ESP-IDF помечает «STA Connected» + BR/EDR «Connected» как «Y: supported and the performance is stable», но **SCO/eSCO в таблице отдельно не указан**; рекомендуется включить `CONFIG_ESP_COEX_SW_COEXIST_ENABLE`, BT на одном ядре, Wi-Fi на другом ([Coexistence](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/coexist.html)). Это главный технический риск вариантов, где ESP32 держит и SCO, и Wi-Fi (а это все варианты, кроме экзотики с SPP).

## Варианты

### A. SIP прямо на ESP32 (рекомендуется)

**Осуществимость на ESP32.** У Espressif есть официальный SIP-стек `esp_rtc` в компоненте `esp_media_protocols`; среди целевых чипов компонента указан `esp32` ([idf_component.yml](https://github.com/espressif/esp-adf-libs/blob/master/esp_media_protocols/idf_component.yml)). Высокоуровневая обёртка `esp_sip_service` ([README](https://github.com/espressif/esp-adf/blob/master/components/esp_sip_service/README.md)) умеет:

- регистрацию «over UDP, TCP or TLS» с keepalive и OPTIONS, а также P2P-режим без регистратора;
- исходящие и входящие вызовы (`call`, `answer`, `bye`), CANCEL;
- G.711 A-law / u-law и Opus; SDES-SRTP; DTMF RFC2833; SIP MESSAGE; свои заголовки INVITE (можно передать номер звонящего из HFP CLIP в `From`/display name);
- связку «uplink sink / downlink source» с произвольным источником и приёмником, то есть вместо микрофона и динамика можно подключить HFP-звук.

Ресурсы: сам стек занимает около 52 КБ, из них ~50 КБ в PSRAM (замер на ESP32-S3) ([SIP_README](https://github.com/espressif/esp-adf-libs/blob/master/esp_media_protocols/docs/en/SIP_README.md)); старый пример VoIP целиком на ESP32-LyraT-Mini (ESP32-WROVER) брал ~392 КБ, из них ~156 КБ PSRAM ([voip README, v2.x](https://github.com/espressif/esp-adf/blob/release/v2.x/examples/protocols/voip/README.md)). Вывод: **нужен WROVER (с PSRAM)**, WROOM без PSRAM вместе с Bluedroid + Wi-Fi скорее всего не влезет (вывод по цифрам, не проверено).

Ограничение: в документации `esp_rtc` нет ICE/STUN/TURN. Для NAT есть только keepalive, OPTIONS ping и «RFC3581 public address» (`esp_sip_service_set_nat_traversal()`), а в разделе неполадок прямо сказано: «Call connects but there is no audio: NAT is the usual cause» ([sip_cli README](https://github.com/espressif/esp-adf/blob/master/adf_examples/protocols/sip_cli/README.md)). Значит, медиа между домом и офисом должен релеить SIP-сервер (см. риски).

Кодек: CVSD 8 кГц из HFP один в один ложится на G.711 8 кГц (без ресемплинга и тяжёлого кодирования). mSBC 16 кГц потребует Opus (G.722 стек не поддерживает) или даунсемпла до 8 кГц. Для v0.2 разумно CVSD + G.711.

**Сервер / провайдер.** Нужен SIP-регистратор, доступный обеим сторонам. Варианты без своего бэкенда:

- **`sip.linphone.org`** — бесплатные SIP-аккаунты на прокси Flexisip; «make audio and video calls to other SIP addresses»; «we do not offer any service guarantee and no commercial support» ([subscribe.linphone.org](https://subscribe.linphone.org/)).
- Платный SIP-провайдер или облачная АТС (выбор не делался, см. открытые вопросы).
- Свой Asterisk/FreeSWITCH (так тестирует Espressif) — противоречит «своего бэкенда нет».

**Нативность приёма в офисе.** Офисная сторона — обычный SIP-софтфон. Официальное приложение Linphone для Android регистрирует `android.telecom.ConnectionService` и просит `USE_FULL_SCREEN_INTENT` ([linphone-android, TelecomConnectionService.kt](https://github.com/BelledonneCommunications/linphone-android/blob/master/app/src/main/java/org/linphone/telecom/TelecomConnectionService.kt)), то есть входящий показывается как системный звонок, в том числе на экране блокировки. Push: для аккаунтов `sip.linphone.org` «we manage the push mechanism for you», а для чужих SIP-серверов в стоковом приложении push включить нельзя ([linphone-developers, 2021-05](https://lists.nongnu.org/archive/html/linphone-developers/2021-05/msg00010.html)). Отсюда связка «аккаунты `sip.linphone.org` + стоковый Linphone» как вариант по умолчанию. Для других провайдеров нужен софтфон с собственным push-шлюзом у провайдера или приложение, которое держит регистрацию в фоне.

**Задержка и качество.** Путь: SCO (единицы мс) → кадр RTP 20 мс → интернет → релей → офис. Узкополосный звук (CVSD/G.711, как у обычного GSM-звонка), задержка в основном сетевая. Цифр замеров в первоисточниках нет; проверять спайком.

**Поток вызова.** ESP32 получает `RING`/`CALL_SETUP` → шлёт SIP INVITE на офисный адрес (номер звонящего в заголовке) → офисный Linphone звонит (push) → пользователь жмёт «Ответить» → 200 OK → ESP32 вызывает `esp_hf_client_answer_call()` → SCO поднимается, PCM ↔ RTP. Звонящий в GSM слышит гудки, пока офис не ответил. Отбой с любой стороны превращается в BYE/CANCEL или в отбой HFP. Robby в этом варианте только настраивает устройство и пишет уведомления; сам мост живёт в прошивке.

### B. Мост через телефон-сервер (запасной)

ESP32 остаётся HFP-гарнитурой, но не говорит по SIP, а гоняет сырые PCM-кадры по Wi-Fi (UDP) в Robby; SIP-клиентом становится приложение на телефоне-сервере.

- **Осуществимость на ESP32:** проще, чем A (нет SIP/RTP), но Wi-Fi + SCO одновременно всё равно нужно, так что главный риск сосуществования остаётся.
- **Стек на Android:** встроенный SIP мёртв (см. выше). Варианты: PJSIP, у которого есть руководство под Android с TLS и SRTP ([PJSIP Android](https://docs.pjsip.org/en/latest/get-started/android/index.html)), лицензия GPL v2 или коммерческая ([PJSIP license](https://docs.pjsip.org/en/latest/overview/license_pjsip.html)); либо Linphone SDK. Здесь доступны ICE/STUN/TURN, то есть NAT решается лучше, чем в A.
- **Сервер/провайдер и нативность в офисе:** те же, что в A (канал всё тот же SIP).
- **Минусы:** лишнее звено (свой протокол ESP32 ↔ телефон, джиттер-буфер), фоновая работа Robby с VoIP-стеком (foreground service), GPL-зависимость в APK. Плюс: логика звонка в Kotlin, проще отлаживать и обновлять, чем прошивку.

### C. Telegram-звонки

- Метод `phone.requestCall`: «Only users can use this method» ([phone.requestCall](https://core.telegram.org/method/phone.requestCall)) — бот звонить не может, нужен пользовательский аккаунт через MTProto.
- Медиа идёт через библиотеку tgcalls, которая «uses an optimized version of MTProto 2.0», один из каналов WebRTC-based, через reflectors Telegram ([Video calls](https://core.telegram.org/api/end-to-end/video-calls)). Порта tgcalls/WebRTC+MTProto на ESP32 нет; на телефоне это TDLib + tgcalls в нативном коде на отдельном пользовательском аккаунте.
- Нативность в офисе была бы отличной (звонок Telegram), но карта уже вынесла Telegram VoIP в Out of scope. **Не годится.**

### D. WebRTC-сервисы (Google Meet, Jitsi и т.п.)

- **Google Meet REST API** создаёт и настраивает пространства, управляет участниками, отдаёт записи конференций; API «позвонить пользователю» в обзоре нет ([Meet REST API](https://developers.google.com/workspace/meet/api/guides/overview)). Meet Media API даёт приложению доступ к медиа конференции (сценарии: транскрипция, ИИ-боты, OAuth-скоуп `meetings.conference.media.audio.readonly`), то есть ориентирован на приём, а не на «входящий звонок» ([Meet Media API](https://developers.google.com/meet/media-api/guides/overview); сведения из поисковой выдачи, на 2026-11-25 страница отдаёт 404).
- **Jitsi** работает комнатами; звонка «на пользователя» с push нет. SIP-шлюз Jigasi есть, но это «server-side application» рядом с Jitsi Videobridge ([jigasi](https://github.com/jitsi/jigasi)) — свой сервер.
- **WebRTC на ESP32** возможен (`esp_peer` из esp-webrtc-solution: ICE, TURN), но «does not provide a built-in signaling transport» ([esp_peer README](https://github.com/espressif/esp-webrtc-solution/blob/main/components/esp_peer/README.md)): сигналинг, push и приложение-приёмник пришлось бы писать самим. Это и есть свой бэкенд.
- **Не годится** для v0.2: нет готового приложения с нативным входящим вызовом.

## Сравнение

| Критерий | A. SIP на ESP32 | B. Мост через телефон (SIP) | C. Telegram | D. WebRTC-сервисы |
|---|---|---|---|---|
| ESP32 | Официальный `esp_sip_service`, нужен WROVER | Только PCM по Wi-Fi | Невозможно | `esp_peer` есть, сигналинг свой |
| Сервер / провайдер | Сторонний SIP (`sip.linphone.org`) | То же | Telegram, но только user-аккаунт | Meet/Jitsi без API звонка, иначе свой сервер |
| Нативный приём в офисе | Linphone: ConnectionService + push | То же | Да, но вне scope | Нет |
| NAT | Только rport/keepalive, нужен релей сервера | ICE/TURN в PJSIP/Linphone SDK | Reflectors Telegram | ICE/TURN |
| Качество | 8 кГц G.711 (как GSM), Opus возможен | То же + лишний хоп | Хорошее | Хорошее |
| Сложность | Прошивка: HFP + SIP + coex | Прошивка проще, Android сложнее | Очень высокая | Очень высокая |
| Вердикт | **Рекомендуется** | Запасной | Отклонён | Отклонён |

## Риски, которые закрывает первый спайк

1. **Wi-Fi + SCO (vHCI) + SIP на одном ESP32-WROVER:** стабильность сосуществования (в таблице ESP-IDF SCO не указан) и память.
2. **NAT и медиа:** будет ли `sip.linphone.org` (Flexisip) релеить RTP для клиента без ICE (ESP32). По старой ветке рассылки Linphone (2015) релей используется, когда один из клиентов без ICE; официального описания модуля MediaRelay я не нашёл. Проверить: звонок ESP32 ↔ Linphone из мобильной сети.
3. **Регистрация ESP32 на `sip.linphone.org`:** какой транспорт и аутентификация требуются стороннему (не Linphone) UA; не проверено.
4. **Отсутствие гарантий у бесплатного сервиса:** держать запасного провайдера в настройках.

## Открытые вопросы для карты

- **Сетевой путь офис → дом:** хватит ли релея SIP-сервиса, или нужен VPN/mesh (и куда его ставить, если ESP32 не умеет VPN, а Android держит один VPN за раз).
- **Выбор SIP-провайдера:** `sip.linphone.org` по умолчанию против платного провайдера/облачной АТС (push для стороннего софтфона, SLA, цена).
- **Офисное приложение:** только Linphone или любой софтфон; Android или ещё и десктоп/iOS.
- **ESP32 vs ESP32-S31:** документация Espressif указывает Classic BT / HFP у ESP32-S31 (HFP только через HCI). Стоит пересмотреть заметку карты про «только оригинальный ESP32» и требование PSRAM (WROVER).
- **Роль Robby в v0.2:** при варианте A Robby только конфигурирует ESP32 (учётка SIP, адрес офиса) и шлёт уведомления; нужен ли канал настройки ESP32 с телефона (BLE/SPP/Wi-Fi).

## Источники

- Android: [MediaRecorder.AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource), [SipManager](https://developer.android.com/reference/android/net/sip/SipManager)
- ESP-IDF: [hfp_hf README](https://github.com/espressif/esp-idf/blob/master/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md), [esp_hf_client_api.h](https://github.com/espressif/esp-idf/blob/master/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h), [Coexistence](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/coexist.html)
- ESP-ADF: [esp_sip_service README](https://github.com/espressif/esp-adf/blob/master/components/esp_sip_service/README.md), [sip_cli README](https://github.com/espressif/esp-adf/blob/master/adf_examples/protocols/sip_cli/README.md), [voip README v2.x](https://github.com/espressif/esp-adf/blob/release/v2.x/examples/protocols/voip/README.md), [Voice Intercom and VoIP](https://github.com/espressif/esp-adf/blob/master/docs/en/solution-center/voice-call.rst), [esp_media_protocols idf_component.yml](https://github.com/espressif/esp-adf-libs/blob/master/esp_media_protocols/idf_component.yml), [SIP_README](https://github.com/espressif/esp-adf-libs/blob/master/esp_media_protocols/docs/en/SIP_README.md)
- WebRTC на ESP32: [esp-webrtc-solution](https://github.com/espressif/esp-webrtc-solution), [esp_peer README](https://github.com/espressif/esp-webrtc-solution/blob/main/components/esp_peer/README.md)
- PJSIP: [Android](https://docs.pjsip.org/en/latest/get-started/android/index.html), [License](https://docs.pjsip.org/en/latest/overview/license_pjsip.html)
- Linphone: [Free SIP service](https://subscribe.linphone.org/), [push для sip.linphone.org (рассылка)](https://lists.nongnu.org/archive/html/linphone-developers/2021-05/msg00010.html), [Flexisip Push Gateway](https://wiki.linphone.org/xwiki/wiki/public/view/Flexisip/HOWTOs/Push%20Gateway/), [linphone-android TelecomConnectionService](https://github.com/BelledonneCommunications/linphone-android/blob/master/app/src/main/java/org/linphone/telecom/TelecomConnectionService.kt)
- Telegram: [phone.requestCall](https://core.telegram.org/method/phone.requestCall), [Video calls](https://core.telegram.org/api/end-to-end/video-calls)
- Google Meet: [REST API overview](https://developers.google.com/workspace/meet/api/guides/overview), [Media API overview](https://developers.google.com/meet/media-api/guides/overview) (на момент проверки 404)
- Jitsi: [jigasi](https://github.com/jitsi/jigasi)
