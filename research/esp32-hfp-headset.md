# ESP32 (WROOM/WROVER) как HFP-гарнитура с выводом звука в Wi-Fi

Исследование к тикету [#5](https://github.com/SBKubric/robby/issues/5). Вопрос: может ли оригинальный ESP32 на ESP-IDF подключиться к телефону-серверу (Android 12+) как Bluetooth Hands-Free Unit (HFP HF), управлять звонком и одновременно гнать звук звонка в обе стороны по Wi-Fi в канал моста.

Дата сверки с источниками: 2026-11-25. Код ESP-IDF смотрел в `master` на коммите [`ad20ded`](https://github.com/espressif/esp-idf/tree/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81) (2026-11-15) и в релизах v5.5.1, v6.0.3, v6.1.1. Код ESP-ADF смотрел на коммите [`20c1047`](https://github.com/espressif/esp-adf/tree/20c1047e72f4469749cbe46c09a4f82cbdea9619). Каждое утверждение помечено ссылкой на первоисточник. Пометка **[вывод]** значит, что прямой формулировки в источниках нет: это заключение, которое надо проверить на прототипе.

Сокращения ссылок ниже:
- `IDF/…` означает `https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/…`
- `hfp_hf` означает пример `IDF/examples/bluetooth/bluedroid/classic_bt/hfp_hf`
- `api.h` означает `IDF/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h`

## Коротко

1. **Да, реализуемо на ESP-IDF без сторонних стеков.** В Bluedroid есть готовый HFP Hands-Free клиент: ответ (`ATA`), сброс и завершение (`AT+CHUP`), номер звонящего (`+CLIP`), индикаторы состояния звонка (`+CIND`: call, callsetup, callheld), список звонков (`AT+CLCC`), RING, DTMF, громкость, отключение шумоподавления на стороне телефона (`AT+NREC=0`). У Espressif есть официальный пример `hfp_hf` для ESP32.
2. **Путь звука: HCI (Voice over HCI), а не PCM/I2S.** При HCI-пути звук SCO/eSCO приходит из контроллера через Bluedroid в callback приложения, и приложение само отправляет кадры обратно. PCM-путь выводит звук на GPIO для внешнего кодека/I2S, мимо стека, и поддерживает только CVSD. Нам он не нужен.
3. **Кодеки:** CVSD (узкая полоса, 8 кГц) кодирует и декодирует контроллер, приложение видит готовый PCM 16 бит. mSBC (широкая полоса, 16 кГц) включается опцией Wideband Speech и работает только по HCI. Espressif рекомендует для новых проектов режим «внешнего кодека» (`CONFIG_BT_HFP_USE_EXTERNAL_CODEC`): приложение получает сырые кадры mSBC и само декодирует их компонентом `esp_audio_codec`, потому что встроенный кодек в HFP «будет удалён в будущем».
4. **Внешний аудиокодек (микросхема) не нужен.** Звук не выводится в динамик и не снимается с микрофона: он переупаковывается в сеть. Ни I2S, ни ES8388 и подобные не требуются **[вывод из пп. 2–3]**.
5. **Главный риск: совместная работа Wi-Fi и Classic BT во время потокового звука.** Официальная таблица coexistence для ESP32 помечает Wi-Fi STA + BR/EDR Connected как «Y, стабильно», но про SCO/eSCO в ней ничего нет, и официального примера «HFP + Wi-Fi» у Espressif нет. Есть открытый баг про щелчки в mSBC на ESP32 при параллельной нагрузке (BLE/UART), без найденной причины. Это надо измерить первым же прототипом (packet error rate через `esp_hf_client_pkt_stat_nums_get`). Запасной выход: Ethernet (у оригинального ESP32 есть встроенный EMAC), тогда Wi-Fi вообще не нужен.
6. **WROOM или WROVER.** Для схемы «HFP + Wi-Fi + простой RTP/UDP с G.711 или сырым PCM» внутренних 520 КБ SRAM должно хватить, но официальных цифр для такой связки нет **[вывод]**. Если ESP32 сам должен быть SIP-клиентом (ESP-ADF `esp_sip_service`, Opus), нужен PSRAM: пример ESP-ADF собирается с `CONFIG_SPIRAM=y` и рассчитан на ESP32-S3/P4. Рекомендация: брать модуль с PSRAM (WROVER-E или WROOM-32E-NxR2) как дешёвую страховку, а архитектуру строить так, чтобы PSRAM не был обязателен.
7. **Новый факт, который меняет допущение карты:** у Espressif появился **ESP32-S31** (анонс 2026-03-26) с Classic BT (BR/EDR) и Wi-Fi 6. Пример `hfp_hf` в `master` уже официально поддерживает ESP32-S31 (только HCI-путь). Утверждение карты «Classic BT есть только у оригинального ESP32» устарело. Чип свежий, даташиты помечены preliminary, и в трекере уже есть баги контроллера.

## 1. HFP Hands-Free клиент в ESP-IDF (Bluedroid)

### Что есть в API

- Профиль включается опциями `CONFIG_BT_CLASSIC_ENABLED`, `CONFIG_BT_HFP_ENABLE`, `CONFIG_BT_HFP_CLIENT_ENABLE`. Контроллер в режиме `CONFIG_BTDM_CTRL_MODE_BR_EDR_ONLY`, одно синхронное соединение `CONFIG_BTDM_CTRL_BR_EDR_MAX_SYNC_CONN=1`. [hfp_hf/sdkconfig.defaults](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/sdkconfig.defaults)
- Каждое синхронное соединение (SCO/eSCO) занимает 2 КБ DRAM, пока включён контроллер. [IDF/components/bt/controller/esp32/Kconfig.in, `BTDM_CTRL_BR_EDR_MAX_SYNC_CONN`](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/controller/esp32/Kconfig.in)
- HF и AG можно собрать вместе, но «they cannot run simultaneously due to internal limitations». Нам нужен только HF. [IDF/components/bt/host/bluedroid/Kconfig.in](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/Kconfig.in)

Функции, которые закрывают вопросы тикета ([api.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h), онлайн-справочник: [docs.espressif.com, HFP Client API](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/bluetooth/esp_hf_client.html)):

| Задача | API | AT-команда / событие |
|---|---|---|
| Подключиться к телефону (SLC) | `esp_hf_client_connect(bda)` | `CONNECTION_STATE_EVT`, состояние `SLC_CONNECTED` |
| Ответить | `esp_hf_client_answer_call()` | «send ATA command» |
| Отклонить входящий или завершить текущий | `esp_hf_client_reject_call()` | «send AT+CHUP command» |
| Номер звонящего | событие `ESP_HF_CLIENT_CLIP_EVT`, поле `clip.number` | `+CLIP` |
| Звонок идёт / нет | `ESP_HF_CLIENT_CIND_CALL_EVT` | `+CIND: call` |
| Входящий, исходящий, вызов у абонента | `ESP_HF_CLIENT_CIND_CALL_SETUP_EVT` | `+CIND: callsetup` |
| Удержание | `ESP_HF_CLIENT_CIND_CALL_HELD_EVT`, `esp_hf_client_send_chld_cmd()` | `+CIND: callheld`, `AT+CHLD` |
| Звонок звонит | `ESP_HF_CLIENT_RING_IND_EVT` | `RING` |
| Ожидающий второй звонок | `ESP_HF_CLIENT_CCWA_EVT` | `+CCWA` |
| Список звонков с номерами и направлением | `esp_hf_client_query_current_calls()` → `ESP_HF_CLIENT_CLCC_EVT` | `AT+CLCC` |
| Набрать номер / DTMF | `esp_hf_client_dial()`, `esp_hf_client_send_dtmf()` | `ATD`, `AT+VTS` |
| Громкость | `esp_hf_client_volume_update()`, `ESP_HF_CLIENT_VOLUME_CONTROL_EVT` | `AT+VGS`/`AT+VGM` |
| Отключить EC/NR на телефоне | `esp_hf_client_send_nrec()` | «NREC=0 request» |
| Поднять/опустить аудиоканал вручную | `esp_hf_client_connect_audio()` / `disconnect_audio()` | SCO/eSCO, `AUDIO_STATE_EVT` |
| Статистика потерь пакетов SCO | `esp_hf_client_pkt_stat_nums_get(sync_conn_handle)` → `PKT_STAT_NUMS_GET_EVT` | HCI |

- Длина номера в API ограничена 32 символами (`ESP_BT_HF_CLIENT_NUMBER_LEN`). [api.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h)
- Все команды управления звонком требуют установленного Service Level Connection: «As a precondition to use this API, Service Level Connection shall exist with AG». [api.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h)
- По README примера аудиосоединение поднимается при ответе на звонок, при наборе номера и при голосовом наборе, а снимается при отклонении. [hfp_hf/README.md, «Scenarios for Audio Connection»](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md)
- В примере есть ещё PBAP-клиент (`esp_pbac_*`) для телефонной книги. Для Robby он не обязателен, но позволяет показать имя звонящего из контактов телефона-сервера. [hfp_hf/README.md, «Special Configurations for PBA Client»](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md)

### Что это значит для Robby

- Нажатие «ответить» в офисе превращается в `esp_hf_client_answer_call()`, «сбросить» в `esp_hf_client_reject_call()`. Номер для уведомления берётся из `+CLIP` или `+CLCC` **[вывод]**.
- Телефон-сервер и Robby на нём видят ESP32 как обычную BT-гарнитуру, поэтому Android сам направит звук звонка в SCO. Root для этого не нужен **[вывод: стандартное поведение AG; проверить на конкретном телефоне]**.
- `AT+NREC=0` стоит отправлять сразу после SLC: телефон не будет накладывать своё шумоподавление и эхоподавление, рассчитанное на гарнитуру с микрофоном у рта **[вывод]**.

## 2. Путь звука: HCI против PCM

README примера описывает два пути: «ESP32 HFP supports two types of audio datapath: PCM and HCI». [hfp_hf/README.md, «Data Path»](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md)

- **PCM:** «audio stream is directed from Bluetooth controller to the specific GPIO pins … you should link these GPIO pins to a speaker via I2S port. The audio data will not go through the Bluedroid». Этот путь выбран по умолчанию в контроллере ESP32 (`BTDM_CTRL_BR_EDR_SCO_DATA_PATH_PCM`). [там же](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md), [controller Kconfig](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/controller/esp32/Kconfig.in)
- **HCI (vHCI):** «audio data stream will be directed from Bluetooth Controller through vHCI and go through the Bluedroid to the Application layer». Включается двумя опциями: `BTDM_CTRL_BR_EDR_SCO_DATA_PATH_HCI` в контроллере и `BT_HFP_AUDIO_DATA_PATH_HCI` в Bluedroid. [hfp_hf/README.md](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md)
- У ESP32-S31 PCM-пути нет совсем: «ESP32-S31 does not support PCM SCO routing, so only HCI is available». [bluedroid Kconfig.in](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/Kconfig.in)

**Для Robby нужен HCI.** Звук должен попасть в программу, чтобы уйти в сокет. С PCM пришлось бы заворачивать PCM-шину с GPIO обратно в I2S того же чипа, и mSBC при этом недоступен **[вывод]**.

### Два API приёма и отправки

1. **Встроенный кодек (legacy):** `esp_hf_client_register_data_callback(recv, send)` и `esp_hf_client_outgoing_data_ready()`. Входящий callback получает уже декодированный PCM. Исходящий callback стек вызывает сам, чтобы забрать данные, и он должен быть неблокирующим. [IDF/…/esp_hf_client_legacy_api.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_legacy_api.h) Инженер Espressif в issue про стриминг HFP-звука по WebSocket подтвердил, что данные в `incoming_cb` «already decoded to PCM», и посоветовал ring buffer и отдельную задачу отправки. [esp-idf#12446](https://github.com/espressif/esp-idf/issues/12446)
2. **Внешний кодек (рекомендуемый):** `CONFIG_BT_HFP_USE_EXTERNAL_CODEC=y`. Help опции: «user shall encode/decode audio data in application layer. The internal codec in HFP will be remove in the future, it is recommend to use external codec for new design». Опция есть в v5.5.1, v6.0.3, v6.1.1 и `master`. [bluedroid Kconfig.in](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/Kconfig.in)
   - Приём: `esp_hf_client_register_audio_data_callback(cb)`, где `cb(sync_conn_hdl, esp_hf_audio_buff_t *buf, bool is_bad_frame)`. Буфер освобождает приложение через `esp_hf_client_audio_buff_free()`. Флаг `is_bad_frame` выставляет baseband. [api.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h)
   - Отправка: `esp_hf_client_audio_buff_alloc()` и затем `esp_hf_client_audio_data_send(sync_conn_hdl, buf)`. При успехе стек сам освобождает буфер. Если длина равна `preferred_frame_size` из `AUDIO_STATE_EVT`, стек экономит одно копирование. [там же](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h)
   - Событие `AUDIO_STATE_EVT` сообщает выбранный эфирный кодек: `CONNECTED` (CVSD), `CONNECTED_MSBC`, `CONNECTED_LC3`. [там же](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h)
   - Пример `hfp_hf` в этом режиме складывает входящие буферы в очередь FreeRTOS, накапливает 20 штук «to add latency» и отправляет их обратно телефону (эхо-петля). У mSBC-кадров срезает паддинг до 57 байт. [hfp_hf/main/bt_app_hf.c](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/main/bt_app_hf.c) Для Robby это готовая заготовка: вместо петли буферы уходят в сокет, а из сокета приходят обратно.
   - Кодирование и декодирование mSBC и LC3 в режиме внешнего кодека показывает пример `hfp_ag`. Он использует компонент `espressif/esp_audio_codec` (`esp_sbc_enc_*`, `esp_sbc_dec_*`, `ESP_SBC_MODE_MSBC`). [hfp_ag/main/bt_app_hf.c](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_ag/main/bt_app_hf.c), [hfp_ag/main/idf_component.yml](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_ag/main/idf_component.yml)

### Кодеки CVSD и mSBC

- «If you choose PCM for datapath, you can only use CVSD and hardware is responsible for the codec job». mSBC «is implemented in the Bluedroid … by software». С HCI и выключенным Wideband Speech CVSD делает железо. [hfp_hf/README.md, «Codec Choice»](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md)
- `BT_HFP_WBS_ENABLE` (Wideband Speech) зависит от HCI-пути и включён по умолчанию. Кодек выбирается переговорами HF и AG при SLC: если обе стороны умеют mSBC, используется mSBC. [bluedroid Kconfig.in](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/Kconfig.in), [hfp_hf/README.md, «Choice of Codec»](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md)
- Параметры: кадр mSBC 57 байт (59 с заголовком H2), PCM 16 кГц. LC3-SWB: 32 кГц, кадр 58 байт, около 62 кбит/с. [IDF/…/esp_hf_defs.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_defs.h)
- LC3-SWB (HFP 1.9, `BT_HFP_LC3_ENABLE`) есть только в `master`, в v6.1.1 его ещё нет. Нужен он только при поддержке со стороны телефона, для нас это необязательно. [bluedroid Kconfig.in](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/Kconfig.in)
- Есть утилита понижения частоты `esp_hf_client_pcm_resample*` до 8 кГц/16 бит/моно, только для HCI-пути. [api.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h)

**[вывод]** Поток мизерный для Wi-Fi: CVSD после декодирования даёт 8000 × 16 бит = 128 кбит/с PCM в каждую сторону, mSBC 57 байт на 7,5 мс ≈ 61 кбит/с. Узкое место не полоса, а равномерность доставки (джиттер) и потери в эфире BT. Если в канале моста узкополосный кодек (G.711), достаточно CVSD. mSBC имеет смысл, только если и GSM/VoLTE-звонок, и канал моста широкополосные.

### Внешний аудиокодек

Микросхема аудиокодека (ADC/DAC, I2S) нужна только тогда, когда звук выходит в динамик или снимается с микрофона. В HCI-пути звук целиком в памяти приложения, поэтому для моста «BT ↔ сеть» кодек не нужен **[вывод из README и API выше]**. Нужен только программный кодек mSBC (`esp_audio_codec`), если выбран mSBC в режиме внешнего кодека. Если канал моста примет mSBC/SBC или PCM как есть, не нужен и он.

## 3. Wi-Fi и Classic BT одновременно

### Что говорит документация Espressif

- Таблица coexistence для ESP32: Wi-Fi STA Scan/Connecting/Connected вместе с BR/EDR Inquiry/Page/Connected помечены **Y** («supported, stable performance»). SoftAP Connecting/Connected вместе с BR/EDR помечены **C1** («unstable»). [ESP-IDF Programming Guide, RF Coexistence](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/coexist.html)
- Механизм: модуль coexistence делит радио по времени на периоды со слайсами Wi-Fi / BT / BLE и раздаёт доступ по динамическим приоритетам. В состоянии Wi-Fi CONNECTED период начинается от TBTT и длится больше 100 мс. [там же](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/coexist.html)
- Рекомендации: включить `CONFIG_ESP_COEX_SW_COEXIST_ENABLE`. Разнести BT-контроллер и Bluedroid на одно ядро (`CONFIG_BTDM_CTRL_PINNED_TO_CORE_CHOICE`, `CONFIG_BT_BLUEDROID_PINNED_TO_CORE_CHOICE`), а Wi-Fi на другое (`CONFIG_ESP_WIFI_TASK_CORE_ID`). Не трогать параметры Wi-Fi connectionless power-save. [там же](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/coexist.html)
- **Про SCO/eSCO, HFP и задержки в этом документе нет ни слова, цифр пропускной способности тоже нет.** Среди официальных coex-примеров ESP-IDF есть только `a2dp_gatts_coex` и `gattc_gatts_coex`, примера HFP + Wi-Fi нет. [IDF/examples/bluetooth/bluedroid/coex](https://github.com/espressif/esp-idf/tree/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/coex)

### Известные проблемы (трекер esp-idf)

- [esp-idf#17925](https://github.com/espressif/esp-idf/issues/17925) (открыт, ESP-IDF v5.5.1, ESP32-WROOM-32D и WROVER-E): HFP AG по HCI с mSBC даёт слышимые щелчки, когда параллельно идут UART-чтение раз в 10 мс или BLE-уведомления раз в 50 мс. Инженер Espressif выдал отладочную библиотеку контроллера и попросил считать packet error rate. Причина не найдена, и автор написал, что команда отказалась от ESP32 для передачи звука. Wi-Fi в этом кейсе нет, но это ровно наш класс риска: soft real-time звук SCO при параллельной радио- или CPU-нагрузке.
- [esp-idf#12446](https://github.com/espressif/esp-idf/issues/12446) (закрыт за неактивностью): `hfp_hf` + WebSocket по Wi-Fi, «искажённый звук». Ответ Espressif свёлся к тому, что данные уже PCM, и к совету правильно буферизовать. Coexistence не обсуждался, подтверждённого решения нет. Вывод: связка в принципе собирается, но публичного успешного референса нет.
- [esp-idf#12282](https://github.com/espressif/esp-idf/issues/12282) (открыт): системные часы ESP32 отстают примерно на 54 с/ч, когда HF-клиент постоянно переподключается на краю зоны связи. Для нас это повод синхронизировать время по NTP и держать ESP32 рядом с телефоном.
- Форумные сообщения о потере SCO-пакетов при активном Wi-Fi есть (esp32.com, ветка «Bluetooth HFP AG over HCI, outgoing SCO link drops data»), но страницы форума закрыты бот-проверкой, и проверить их по первоисточнику не удалось. Поэтому в выводах я на них не опираюсь.

### Выводы по coexistence

- **[вывод]** Официально связка Wi-Fi STA + BR/EDR Connected стабильна, но гарантий именно для синхронного голосового трафика Espressif не даёт. Возможные дыры в звуке надо измерять, а не предполагать.
- Первый прототип должен мерить: долю `is_bad_frame` и статистику `esp_hf_client_pkt_stat_nums_get()` с выключенным Wi-Fi, с Wi-Fi STA в простое и с Wi-Fi под нагрузкой RTP 50 пакетов/с в обе стороны. Плюс end-to-end задержку.
- Снижение риска: Wi-Fi только в режиме STA (SoftAP помечен C1). Ядра разнести по рекомендации. Отключить BLE (`CONFIG_BT_BLE_ENABLED=n`, как в примере). Держать в джиттер-буфере 20–40 мс.
- **Запасной путь без Wi-Fi:** у оригинального ESP32 есть встроенный Ethernet MAC (RMII), его поддерживает драйвер `esp_eth`. [ESP-IDF, Ethernet](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/network/esp_eth.html) Плата ESP32 с Ethernet PHY полностью снимает конкуренцию Wi-Fi и BT за радио **[вывод]**. Это вопрос к тикету железа.

## 4. Память: WROOM или WROVER

- Оба модуля построены на ESP32-D0WD-V3 (или D0WDR2-V3) с 520 КБ SRAM. У WROVER-E PSRAM 2 или 8 МБ (варианты `-NxR2` / `-NxR8`). У WROOM-32E есть варианты без PSRAM (`-N4/-N8/-N16`) и с 2 МБ PSRAM в корпусе чипа (`-N4R2/-N8R2/-N16R2`). [ESP32-WROVER-E datasheet](https://documentation.espressif.com/esp32-wrover-e_esp32-wrover-ie_datasheet_en.pdf), [ESP32-WROOM-32E datasheet](https://documentation.espressif.com/esp32-wroom-32e_esp32-wroom-32ue_datasheet_en.pdf)
- Официальных цифр RAM для связки «Bluedroid Classic + HFP + Wi-Fi» Espressif не публикует. Руководство по RAM предлагает только способы ужаться: буферы Wi-Fi, `CONFIG_BTDM_CTRL_BR_EDR_MAX_ACL_CONN`, стеки BTC/BTU, отключение `CONFIG_ESP_WIFI_IRAM_OPT`/`RX_IRAM_OPT`, а размер своей сборки советует мерить через `idf.py size`. [ESP-IDF, Minimizing RAM Usage](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/performance/ram-usage.html), [RF Coexistence, раздел про память](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/coexist.html)
- Bluedroid умеет класть свои аллокации в PSRAM: `CONFIG_BT_ALLOCATION_FROM_SPIRAM_FIRST`. Стеки BTU и HCI-задач тоже можно вынести, но на ESP32 только с `SPIRAM_CACHE_WORKAROUND`. [bluedroid Kconfig.in](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/Kconfig.in)
- Если ESP32 сам будет SIP-клиентом: в ESP-ADF есть `esp_sip_service` (SIP + RTP + SRTP, G.711 A/µ-law и Opus). Его пример `sip_cli` собирается с `CONFIG_SPIRAM=y` и рассчитан на платы ESP32-S3-Korvo-2 и ESP32-P4 (есть `sdkconfig.defaults.esp32s3/esp32p4`, для ESP32 отдельного файла нет). [esp-adf/components/esp_sip_service/README.md](https://github.com/espressif/esp-adf/blob/20c1047e72f4469749cbe46c09a4f82cbdea9619/components/esp_sip_service/README.md), [esp-adf/adf_examples/protocols/sip_cli](https://github.com/espressif/esp-adf/tree/20c1047e72f4469749cbe46c09a4f82cbdea9619/adf_examples/protocols/sip_cli)

**[вывод]** Есть две архитектуры, и память зависит от выбора:

1. **ESP32 как тупой мост BT ↔ LAN:** PCM или G.711 по UDP/RTP на телефон-сервер (или на шлюз) в домашней сети, а SIP и связь с офисом делает Robby на телефоне или сторонний сервис. ESP32 держит только Bluedroid HFP, Wi-Fi и лёгкий UDP. Это должно уместиться в WROOM без PSRAM.
2. **ESP32 как самостоятельный SIP UA:** SIP, RTP, джиттер-буфер, возможно Opus, TLS/SRTP. Здесь PSRAM почти наверняка нужен, а ESP-ADF не даёт готового примера для оригинального ESP32.

Рекомендация: заложить модуль с PSRAM (WROVER-E-N8R8 или WROOM-32E-N8R2) как дешёвую страховку на прототип, а решение о финальном модуле принять по `idf.py size` и `heap_caps_get_minimum_free_size()` под нагрузкой.

## 5. Готовые проекты и референсы

- **ESP-IDF `hfp_hf`:** официальный пример HF для ESP32 и ESP32-S31 с консольными командами (`ac` ответить, `rc` отклонить, `qc` список звонков, `cona`/`disa` аудио). В режиме внешнего кодека отражает принятые кадры обратно в телефон. [hfp_hf/README.md](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md)
- **ESP-ADF / ESP-GMF `esp_bt_audio`:** новый компонент Espressif, унифицирующий A2DP/HFP HF/AVRCP/PBAP поверх Bluedroid, с событиями состояния звонка и пакетным вводом-выводом. [esp-gmf/packages/esp_bt_audio](https://github.com/espressif/esp-gmf/tree/main/packages/esp_bt_audio) В документации ADF прямо сказано: «ESP32 can be used when the product only needs A2DP, AVRCP, and HFP». [ESP-ADF Solution Center, Bluetooth Audio](https://docs.espressif.com/projects/esp-adf/en/latest/solution-center/bluetooth-audio.html)
- **Публичного проекта «ESP32 HFP HF → VoIP/SIP по Wi-Fi» с подтверждённо рабочим звуком в первоисточниках не нашлось.** Ближайший кейс ([esp-idf#12446](https://github.com/espressif/esp-idf/issues/12446)) закончился без подтверждённого результата.

## 6. ESP32-S31: новая альтернатива

- Espressif анонсировал ESP32-S31 (2026-03-26): двухъядерный RISC-V до 320 МГц, Wi-Fi 6 2.4 ГГц, Bluetooth 5.4 LE **и Classic (BR/EDR)**, 802.15.4. [espressif.com, ESP32-S31](https://www.espressif.com/en/products/socs/esp32-s31), [ESP32-S31 datasheet (preliminary)](https://documentation.espressif.com/esp32-s31_datasheet_en.pdf)
- Пример `hfp_hf` в `master` указывает поддерживаемые цели «ESP32 | ESP32-S31». Для S31 доступен только HCI-путь. [hfp_hf/README.md](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md) Документация ADF называет S31 рекомендуемым чипом для Bluetooth-аудио. [ESP-ADF, Bluetooth Audio](https://docs.espressif.com/projects/esp-adf/en/latest/solution-center/bluetooth-audio.html)
- Зрелость низкая. Баг контроллера: в dual-mode S31 объявлял «BR/EDR Not Supported», исправлено в `master` в августе 2026, бэкпорт в v6.1 на тот момент шёл. [esp-idf#18785](https://github.com/espressif/esp-idf/issues/18785)
- **[вывод]** Для v0.2 разумно остаться на оригинальном ESP32 (зрелый стек, дешёвые модули), но держать S31 как путь миграции. Формулировку карты «Classic BT / HFP есть только у оригинального ESP32» нужно поправить.

## 7. Риски и что проверить прототипом

| Риск | Чем проверить | Запасной выход |
|---|---|---|
| Потери и щелчки SCO при активном Wi-Fi | `pkt_stat_nums_get`, доля `is_bad_frame`, прослушивание; сравнить Wi-Fi off / idle / RTP-нагрузка | Ethernet (EMAC ESP32), CVSD вместо mSBC, ESP32-S31 |
| Нехватка RAM при SIP на борту | `idf.py size`, минимум свободной кучи под нагрузкой | Модуль с PSRAM или вынос SIP с ESP32 на телефон/шлюз |
| Переподключение HFP после ребута ESP32 или телефона | Логика автоконнекта к сохранённому BDA | Сценарий Robby, который следит за BT-устройством |
| Android не отдаёт звук в SCO или ведёт себя нестандартно на конкретной прошивке | Тест на целевом телефоне-сервере | Ручной `esp_hf_client_connect_audio()` после ответа |
| Задержка end-to-end (BT + джиттер-буфер + сеть + VoIP) | Замер петлёй | Уменьшить буферы, CVSD/G.711 без перекодирования |
| Удаление встроенного HFP-кодека в будущих IDF | Сразу писать под `BT_HFP_USE_EXTERNAL_CODEC` | — |
| Дрейф часов при плохом линке ([#12282](https://github.com/espressif/esp-idf/issues/12282)) | — | NTP, ESP32 рядом с телефоном |

## Источники

- ESP-IDF, пример `hfp_hf`: [README](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/README.md), [bt_app_hf.c](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/main/bt_app_hf.c), [sdkconfig.defaults](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_hf/sdkconfig.defaults)
- ESP-IDF, пример `hfp_ag` (внешний кодек mSBC/LC3): [bt_app_hf.c](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/examples/bluetooth/bluedroid/classic_bt/hfp_ag/main/bt_app_hf.c)
- ESP-IDF, заголовки API: [esp_hf_client_api.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_api.h), [esp_hf_client_legacy_api.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_client_legacy_api.h), [esp_hf_defs.h](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/api/include/api/esp_hf_defs.h); справочник: [HFP Client API](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/bluetooth/esp_hf_client.html)
- ESP-IDF, Kconfig: [Bluedroid](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/host/bluedroid/Kconfig.in), [контроллер ESP32](https://github.com/espressif/esp-idf/blob/ad20ded62a2d67a26e501fdc1905f43ba7ad6c81/components/bt/controller/esp32/Kconfig.in)
- ESP-IDF Programming Guide: [RF Coexistence](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/coexist.html), [Minimizing RAM Usage](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/performance/ram-usage.html), [Ethernet](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/network/esp_eth.html)
- Трекер esp-idf: [#17925](https://github.com/espressif/esp-idf/issues/17925), [#12446](https://github.com/espressif/esp-idf/issues/12446), [#12282](https://github.com/espressif/esp-idf/issues/12282), [#18785](https://github.com/espressif/esp-idf/issues/18785)
- ESP-ADF: [Bluetooth Audio](https://docs.espressif.com/projects/esp-adf/en/latest/solution-center/bluetooth-audio.html), [esp_sip_service](https://github.com/espressif/esp-adf/blob/20c1047e72f4469749cbe46c09a4f82cbdea9619/components/esp_sip_service/README.md), [sip_cli](https://github.com/espressif/esp-adf/tree/20c1047e72f4469749cbe46c09a4f82cbdea9619/adf_examples/protocols/sip_cli); ESP-GMF: [esp_bt_audio](https://github.com/espressif/esp-gmf/tree/main/packages/esp_bt_audio)
- Даташиты: [ESP32-WROVER-E/IE](https://documentation.espressif.com/esp32-wrover-e_esp32-wrover-ie_datasheet_en.pdf), [ESP32-WROOM-32E/UE](https://documentation.espressif.com/esp32-wroom-32e_esp32-wroom-32ue_datasheet_en.pdf), [ESP32-S31](https://documentation.espressif.com/esp32-s31_datasheet_en.pdf), [страница ESP32-S31](https://www.espressif.com/en/products/socs/esp32-s31)
