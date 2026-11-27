# Локальная проверка SIP-настроек спайка на Asterisk (#17)

Дата прогона: 2026-11-27. Результат: **PASS**. Все 10 проверок прошли.

Это проверка в окружении агента до того, как появится VPS. Продакшен-сервер другой: Flexisip на `sip.sbkubric.com`, конфигурация в `infra/sip/`. Телефоны сюда не достают, поэтому APK здесь не запускался. Вместо него работали два Linux-клиента `pjsua` с теми же настройками, что у спайка.

## Что проверялось

- **Сервер:** Asterisk 22.11.0 (текущий LTS) в Docker. Образ `andrius/asterisk:22.11.0_debian-trixie`, закреплён по digest. Официального образа у Sangoma нет, а этот самый популярный на Docker Hub (1,3 млн загрузок), на Debian trixie, в нём есть `chan_pjsip` и `res_srtp`. Собирать свой образ из пакетов Debian смысла нет: в Debian 13 пакета `asterisk` нет.
  - Канал PJSIP, транспорт **только TLS** на 5061 (TLS 1.2), сертификат самоподписанный, его делает `gen-cert.sh`: свой CA и SAN `127.0.0.1`, `localhost`, `sip.test`.
  - Две учётки, `robby` и `office`, с digest-аутентификацией. Пароли случайные, их генерирует каждый прогон, в git они не попадают.
  - `media_encryption=sdes` без `optimistic`, то есть SRTP обязателен. `direct_media=no`: звук идёт через Asterisk. Только `ulaw`/`alaw`. Включены `rtp_symmetric`, `force_rport`, `rewrite_contact`, `ice_support`.
  - Диалплан: `robby` может звонить только `office`, `office` только `robby`.
- **Клиенты:** `pjsua` из PJSIP 2.18 с OpenSSL (`build-pjsua-linux.sh`), настройки как в `SipEngine.kt`:
  - SIP/TLS с проверкой сертификата сервера (`--tls-verify-server`, CA из прогона);
  - SRTP обязательный (`--use-srtp=2 --srtp-secure=1`, SDES);
  - ICE включён;
  - только G.711, `ptime` 20 мс, без VAD и эхоподавления, null-устройство, такт 8 кГц;
  - outbound proxy = регистратор (`sip:<хост>:5061;transport=tls;lr`), как в спайке.
- **Сценарий:** `office` регистрируется и отвечает автоматически (200). `robby` регистрируется и звонит на `sip:office@sip.test`. `robby` шлёт тон 1000 Гц, `office` тон 440 Гц, каждая сторона пишет, что слышит. Через 12 с `robby` кладёт трубку. Потом записи проверяются: в них не должно быть тишины, и должна преобладать частота другой стороны (фильтр Гёрцеля).

## Итог прогона (`out/summary.txt`)

```
Local SIP check, 2026-11-27 17:24:50 UTC
PJSUA: PJSIP 2.18
Asterisk: Asterisk 22.11.0

[robby]
  registration: sip:robby@sip.test: registration success, status=200 (OK), will re-register in 299 seconds
  TLS:          SIP TLS transport is connected to 127.0.0.1:5061; TLS cipher used: 0x00C030/ECDHE-RSA-AES256-GCM-SHA384
  SRTP:         SRTP status: Active Crypto-suite: AES_CM_128_HMAC_SHA1_80
  ICE:          ICE negotiation success after 0s:022
  codec:        PCMU @8kHz
  disconnect:   Call 0 is DISCONNECTED [reason=200 (OK)]

[office]
  registration: sip:office@sip.test: registration success, status=200 (OK), will re-register in 299 seconds
  TLS:          SIP TLS transport is connected to 127.0.0.1:5061; TLS cipher used: 0x00C030/ECDHE-RSA-AES256-GCM-SHA384
  SRTP:         SRTP status: Active Crypto-suite: AES_CM_128_HMAC_SHA1_80
  ICE:          ICE negotiation success after 0s:045
  codec:        PCMU @8kHz
  disconnect:   Call 0 is DISCONNECTED [reason=200 (Normal call clearing)]

[asterisk, mid-call: media anchored, packets counted by Asterisk]
 cb772f17 office-00000001    00:00:04 ulaw      188       0    0   0.000    186       0    0   0.000   0.000
 cb772f17 robby-00000000     00:00:04 ulaw      186       0    0   0.000    188       0    0   0.000   0.000

[audio]
robby-heard.wav: 12.0 s, 8000 Hz, RMS -9.0 dBFS, frames with signal 598/598 (100%), 440 Hz vs 1000 Hz: +58.1 dB -> OK
office-heard.wav: 12.0 s, 8000 Hz, RMS -9.0 dBFS, frames with signal 598/600 (100%), 1000 Hz vs 440 Hz: +88.6 dB -> OK

[checks]
OK    robby registered over TLS
OK    office registered over TLS
OK    call confirmed (robby)
OK    SRTP active (robby)
OK    SRTP active (office)
OK    media relayed by Asterisk (both legs)
OK    office hears robby (1000 Hz)
OK    robby hears office (440 Hz)
OK    robby hung up normally
OK    office got BYE

RESULT: PASS
```

## Выводы

- **Регистрация по TLS с digest-аутентификацией:** работает у обоих (200 OK). Сертификат проверяется по своему CA. Шифр ECDHE-RSA-AES256-GCM-SHA384.
- **SRTP:** обязательный SDES договаривается. pjsua предлагает четыре набора: `AES_256_CM_HMAC_SHA1_80`, `AES_256_CM_HMAC_SHA1_32`, `AES_CM_128_HMAC_SHA1_80`, `AES_CM_128_HMAC_SHA1_32`. Asterisk выбирает `AES_CM_128_HMAC_SHA1_80`. В SDP обеих сторон `m=audio … RTP/SAVP 0 8 120`.
- **Звук в обе стороны:** есть. Обе записи по 12 с, сигнал почти в каждом кадре 20 мс. Частота другой стороны сильнее чужой на 58 и 89 дБ.
- **Звук идёт через сервер:** да. В середине звонка `pjsip show channelstats` показывает пакеты по обеим ногам, потерь 0. ICE сходится на парах с адресом Asterisk.
- **Отбой:** чистый. `robby` отправил BYE (`reason=200`), `office` его получил («Normal call clearing»).

## Чего эта проверка не покрывает

- **Сам APK (pjsua2 на Android).** Здесь работал CLI `pjsua` той же версии PJSIP с теми же параметрами. Собственный код спайка (TonePort, фон, Doze, Wi-Fi) проверяется только на телефоне.
- **NAT, CGNAT, STUN.** Всё шло через один хост и сеть Docker. STUN не использовался, у ICE были только host-кандидаты.
- **Стоковый Linphone с его AVPF, bundle и своими наборами SRTP.** Это проверка на Flexisip с офисным телефоном.
- **Сертификат Let's Encrypt и проверка по системным корневым сертификатам Android.**

## Как повторить

```
./build-pjsua-linux.sh                 # один раз: PJSIP 2.18 + OpenSSL, печатает путь к pjsua
PJSUA=/путь/к/pjsua-x86_64-pc-linux-gnu ./run-check.sh
```

Нужны Docker с compose, `openssl` и `python3`. Порты на хосте: 5061/tcp и 10000–10049/udp. Логи, записи и итог складываются в `out/`, сертификаты и отрисованный `pjsip.conf` в `generated/`. Обе папки в `.gitignore`. После прогона контейнер удаляется; `KEEP=1` оставляет его работать.
