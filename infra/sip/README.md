# SIP-сервер канала моста (Flexisip на `sip.sbkubric.com`)

Здесь лежит **инфраструктура канала моста v0.2**, и ничего больше. Приложение Robby по-прежнему работает без бэкенда: вся логика исполняется на телефоне-сервере. VPS владельца только передаёт SIP и звук между Robby и офисным Linphone. Решение принято в [#17](https://github.com/SBKubric/robby/issues/17): свой Flexisip вместо `sip.linphone.org`.

```
Robby (телефон-сервер, дома)            VPS: sip.sbkubric.com                Офис (CGNAT)
pjsua2, учётка robby  ── SIP/TLS 5061 ──▶ Flexisip 2.6.2               ◀── SIP/TLS ── стоковый Linphone,
                      ◀═ SRTP (UDP) ════▶ регистратор + прокси           ═ SRTP ═▶     учётка office
                                          + MediaRelay (UDP 40000–40199)
                                          + STUN (UDP 3478)
```

- **Flexisip** — SIP-прокси от Belledonne, авторов Linphone. На нём работает и `sip.linphone.org`, поэтому со стоковым Linphone он совместим лучше всего.
- **MediaRelay** пропускает звук через VPS. Офис сидит за CGNAT, и прямой путь между двумя NAT часто не находится. Flexisip подставляет в SDP свой адрес и порт, а для клиентов с ICE добавляет кандидат `relay`. SDES-SRTP проходит насквозь: Flexisip меняет только адреса и порты, ключи `a=crypto` не трогает, звук не расшифровывает.
- **Доступ:** звонить могут только `robby` и `office`, только друг другу и только после digest-аутентификации. Других пользователей, домены и открытый relay сервер не обслуживает.

## Состав

| Файл | Что это |
|---|---|
| `docker-compose.yml` | Сервисы `flexisip` и `certbot` (`certbot` включается профилем `letsencrypt`) |
| `.env.example` | Шаблон `.env`: домен, e-mail для Let's Encrypt, пароли, диапазон портов |
| `flexisip/Dockerfile` | Образ из официальных Debian-пакетов Belledonne: `bc-flexisip=2.6.2-1`, Debian 13 (trixie), всё закреплено |
| `flexisip/flexisip.conf.template` | Конфигурация Flexisip с комментариями |
| `flexisip/entrypoint.sh` | При старте собирает файл учёток (хеши паролей), сертификат (или самоподписанный запасной) и конфиг, затем запускает Flexisip от непривилегированного пользователя |
| `certbot/run.sh`, `certbot/deploy-hook.sh` | Выпуск и продление сертификата Let's Encrypt (HTTP-01 на 80/tcp), передача нового сертификата Flexisip |
| `test/run-check.sh` | Локальная проверка: два pjsua с настройками спайка (см. «Локальная проверка») |

**Почему образ свой.** У Belledonne нет готового образа Flexisip. В их реестре `gitlab.linphone.org:4567/bc/public/flexisip` нет ни одного тега, а образы на Docker Hub выложены третьими лицами. Поэтому `Dockerfile` ставит официальный пакет из apt-репозитория `https://download.linphone.org/snapshots/debian` (компонент `stable`), как это делает их собственный рецепт `docker/flex-from-deb`. Закреплено всё:
- базовый образ — по digest;
- пакет — по версии;
- ключи репозитория — по отпечаткам. Если в keyring появится чужой ключ, сборка упадёт.

Пакеты есть только для **amd64**.

## Требования к VPS

- Linux x86_64 (amd64), 1 vCPU, 1 ГБ RAM, около 1 ГБ на диске (образ весит ~460 МБ).
- Публичный IPv4. Подойдёт и облачный 1:1 NAT: Flexisip узнаёт публичный адрес из A-записи домена.
- Docker Engine с плагином Compose v2.24 или новее.
- Порт 80/tcp свободен: он нужен certbot для выпуска и продления сертификата.

## DNS

- **A-запись** `sip.sbkubric.com` → публичный IPv4 VPS. Сейчас (2026-11-27) домен уже резолвится в `46.253.143.178`; проверьте, что это адрес нужного VPS.
- **AAAA не заводить**, пока VPS не слушает IPv6: клиенты могут попытаться пойти по IPv6 и не дойти.
- **SRV** `_sips._tcp.sip.sbkubric.com. 3600 IN SRV 0 0 5061 sip.sbkubric.com.` — по желанию. В Robby и Linphone транспорт TLS задаётся явно, а порт 5061 стандартный, поэтому без SRV всё работает.

## Порты для файрвола VPS

| Порт | Зачем |
|---|---|
| 5061/tcp | SIP over TLS (Robby, Linphone) |
| 40000–40199/udp | MediaRelay: звук (SRTP). Диапазон задаётся `RTP_PORT_MIN`/`RTP_PORT_MAX` |
| 3478/udp | STUN Flexisip (для ICE клиентов) |
| 80/tcp | Let's Encrypt HTTP-01: первый выпуск и продление раз в ~60 дней. Должен быть открыт из интернета |
| 22/tcp | SSH (ваш) |

5060/tcp открывать не нужно. Он слушается только при `SIP_TCP_DIAG=1` и только для диагностики.

Пример для `ufw`:
```
ufw allow 22/tcp
ufw limit 5061/tcp            # не больше ~6 новых TLS-соединений за 30 с с одного IP
ufw allow 40000:40199/udp
ufw allow 3478/udp
ufw allow 80/tcp
ufw enable
```
У Flexisip сеть хоста (`network_mode: host`), поэтому правила `ufw` действуют на него как обычно. Порт 80 certbot публикует через Docker, а Docker открывает его в обход `ufw`.

## Развёртывание по шагам

1. **Docker** (Debian/Ubuntu): `curl -fsSL https://get.docker.com | sh`, затем проверить `docker compose version`.
2. **Код:**
   ```
   git clone https://github.com/SBKubric/robby.git
   cd robby/infra/sip
   ```
3. **`.env`:**
   ```
   cp .env.example .env
   chmod 600 .env
   openssl rand -base64 24    # дважды: пароль robby и пароль office
   nano .env                  # LE_EMAIL, ROBBY_PASSWORD, OFFICE_PASSWORD
   ```
   Пароль должен быть не короче 16 символов. С заглушкой из примера контейнер не стартует. `.env` в git не попадает (см. `.gitignore`).
4. **Файрвол:** открыть порты из таблицы выше.
5. **Запуск:**
   ```
   docker compose up -d --build
   ```
   Сборка образа занимает 1–2 минуты. Flexisip сразу стартует с **самоподписанным** сертификатом, а certbot в это время запрашивает настоящий.

   Если хочется сначала прогнать всё вхолостую, поставьте `LE_STAGING=1`: выпустится тестовый сертификат, которому телефоны не доверяют. Перед боевым выпуском удалите его: `docker compose down && rm -rf data/letsencrypt data/tls`. Затем верните `LE_STAGING=0` и снова выполните `up -d`.
6. **Сертификат:**
   ```
   docker compose logs -f certbot      # ждём: "[certbot] certificate for sip.sbkubric.com installed for Flexisip"
   ```
   Flexisip проверяет файл сертификата раз в минуту и подхватывает новый **без перезапуска**: локально новый сертификат начал отдаваться через 30 с. Проверка снаружи:
   ```
   openssl s_client -connect sip.sbkubric.com:5061 -servername sip.sbkubric.com </dev/null 2>/dev/null \
     | openssl x509 -noout -subject -issuer -enddate     # issuer: Let's Encrypt
   ```
   Продление идёт само: certbot проверяет сертификат каждые 12 ч и продлевает его в своё штатное окно (обычно примерно за 30 дней до конца).
7. **Логи:**
   ```
   docker compose ps                    # flexisip: healthy
   docker compose logs -f flexisip      # регистрации: "Registration successful", звонки: INVITE/200/BYE
   ```
   Flexisip дублирует лог в файл `data/log/flexisip-proxy.log` и сам его не ротирует. Нужен `logrotate` на хосте, например `/etc/logrotate.d/robby-sip`:
   ```
   /путь/к/robby/infra/sip/data/log/*.log {
     weekly
     rotate 4
     compress
     missingok
     copytruncate
   }
   ```
   Можно ещё поставить `FLEXISIP_LOG_LEVEL=warning`.
8. **Обновление:** `git pull`, затем `docker compose up -d --build`. Новая версия Flexisip — это правка `FLEXISIP_VERSION` в `flexisip/Dockerfile`; доступные версии лежат в `https://download.linphone.org/snapshots/debian/dists/trixie/stable/binary-amd64/Packages.gz`.

## Учётки `robby` и `office`

Учёток ровно две: `robby@sip.sbkubric.com` и `office@sip.sbkubric.com`. Других пользователей сервер не знает.

- **Создание.** Учётки появляются из `.env` при каждом старте контейнера. Пароли попадают в контейнер как Docker secrets (`/run/secrets/*`), а не через переменные окружения, поэтому в `docker inspect` их нет. Сам Flexisip читает только digest-хеши (MD5 и SHA-256 от `user:realm:password`) из файла на tmpfs.
- **Смена пароля (ротация):**
  1. Новый пароль: `openssl rand -base64 24`, вписать в `.env`.
  2. `docker compose up -d --force-recreate flexisip`.
  3. Ввести новый пароль в Robby (спайк-APK) и в офисном Linphone.

  При перезапуске Flexisip теряет регистрации: они хранятся в памяти. Клиенты регистрируются заново сами, когда рвётся TLS-соединение. Спайк повторяет попытку через `retryIntervalSec` = 60 с; Linphone тоже переподключается сам, но как быстро, на телефоне не проверялось.
- **Отключить учётку** (например, при утечке пароля): поставить ей длинный случайный пароль и пересоздать контейнер.

## Офисный телефон: стоковый Linphone для Android

Названия пунктов зависят от версии Linphone. Ниже они даны по Linphone 5.x/6.x.

1. Установить Linphone из Google Play (Belledonne Communications).
2. В помощнике выбрать **«Использовать SIP-аккаунт»** (сторонний SIP, не `sip.linphone.org`):
   - Имя пользователя: `office`
   - Пароль: `OFFICE_PASSWORD` из `.env`
   - Домен: `sip.sbkubric.com`
   - Транспорт: **TLS**
3. Настройки → **Безопасность / Шифрование медиа**: **SRTP**, включить «**Шифрование медиа обязательно**».
4. Настройки аккаунта → Дополнительно:
   - **ICE** вкл.;
   - STUN-сервер `sip.sbkubric.com` (порт 3478);
   - **push-уведомления выкл.**: для чужих серверов push стокового Linphone не работает;
   - AVPF оставить по умолчанию.
5. Чтобы Linphone держал регистрацию в фоне:
   - Настройки → Дополнительно → «**Сервис для работы в фоне**» / «Keep alive service»: вкл. В шторке появится постоянное уведомление.
   - Android → Настройки → Приложения → Linphone → Батарея → **«Без ограничений»** (отключить оптимизацию батареи).
   - Включить автозапуск после перезагрузки, если такой пункт есть.
6. Проверка: в списке аккаунтов у `office@sip.sbkubric.com` зелёная точка («Подключено»). В `docker compose logs flexisip` есть `REGISTER … office` и `Registration successful`.

Если регистрация в фоне всё-таки отваливается (агрессивная прошивка), можно отвечать с настольного Linphone в офисе той же учёткой `office`. Регистратор принимает до трёх устройств на учётку и звонит на все сразу.

## Спайк-APK (#17) на этом сервере

APK пересобирать не нужно: всё вводится в форме «Robby SIP-спайк».

| Поле | Значение |
|---|---|
| SIP-логин / пароль | `robby` / `ROBBY_PASSWORD` из `.env` |
| Домен | `sip.sbkubric.com` |
| Кому звонить | `office` |
| Транспорт | TLS |
| SRTP | обязательный |
| Проверка сертификата | вкл.: сертификат Let's Encrypt доверенный |
| ICE | вкл. |
| STUN | `sip.sbkubric.com` (свой STUN Flexisip). Можно оставить `stun.linphone.org` или очистить поле: звук всё равно пойдёт через MediaRelay |

Дальше по протоколу проверки из `spike/sip/README.md` в ветке `spike/sip-linphone`.

## Безопасность

- **Шифрование.** Сигнализация только по TLS (`sips:` на 5061). Шифры по умолчанию Flexisip: `HIGH`, без SSLv3/TLS 1.0. Звук идёт по SRTP, обязательность задаётся в клиентах. Ключи SDES едут внутри TLS, поэтому включать `SIP_TCP_DIAG=1` можно только для коротких проверок.
- **Нет открытого relay** (проверено локально):
  - неверный пароль → `403 Forbidden`;
  - INVITE без учётных данных → `407`;
  - звонок аутентифицированного `robby` на `sip:someone@example.com` → `403 Domain forbidden` (`module::Authorization` с фильтром по домену `To`);
  - запросы от других доменов отклоняет `module::Authentication`: он проверяет `auth-domains`.
- **Лимиты:**
  - MediaRelay: не больше 4 одновременных звонков;
  - до 3 устройств на учётку, по 1 Contact на REGISTER;
  - `expires` 60–3600 с;
  - звонок без ответа обрывается через 60 с.
- **DoSProtection Flexisip выключен.** Ему нужен `iptables` внутри контейнера и право `NET_ADMIN` на файрвол хоста. Вместо него на хосте:
  - `ufw limit 5061/tcp`;
  - пароли по 24+ случайных символа (подбор бесполезен);
  - fail2ban для `sshd`.

  Jail fail2ban для SIP не поставляется. Flexisip 2.6 на уровне `message` не пишет IP-адрес в строке о неверном пароле («Passwords did not match»), так что фильтр ненадёжен. Это проверено на локальном логе.
- **Привилегии.** Flexisip работает от пользователя `flexisip` (uid 5061). Root нужен только entrypoint, чтобы подготовить файлы. Закрытый ключ сертификата: `0640 root:5061`.
- **Секреты в git не попадают.** `.env`, `data/` (ключи, сертификаты, логи) и результаты тестов перечислены в `.gitignore`. В репозитории лежат только плейсхолдеры.

## Локальная проверка (`test/run-check.sh`)

Запускается на любой Linux-машине с Docker и root, VPS не нужен:

```
./test/build-pjsua-linux.sh                                  # PJSIP 2.18 + OpenSSL, печатает путь к pjsua
PJSUA=/путь/к/pjsua-x86_64-pc-linux-gnu ./test/run-check.sh   # за TLS-прокси: EXTRA_CA_FILE=/путь/к/ca.pem
```

Скрипт поднимает настоящий `docker-compose.yml` с `test/compose.test.yml`: свои папки данных, `sip.sbkubric.com` → `127.0.0.1`, без certbot. Flexisip работает на самоподписанном запасном сертификате. Дальше запускаются два клиента `pjsua` с настройками спайка: TLS с проверкой сертификата, digest, SRTP обязательный, только G.711. Клиенты живут в отдельном mount namespace со своим `/etc/hosts`. Что проверяется:

1. Негатив: неверный пароль, INVITE без учётных данных, звонок в чужой домен.
2. Звонок A с настройками спайка: ICE + STUN Flexisip.
3. Звонок B с выключенным ICE. Здесь звук может идти только через MediaRelay, как у офиса за CGNAT.
4. В обоих звонках `robby` звонит `office`, тот отвечает автоматически. Стороны шлют друг другу тоны 1000 и 440 Гц, записи проверяются на тишину и на частоту другой стороны. Проверяются набор SRTP и чистый отбой.
5. Продление сертификата: настоящий `certbot/deploy-hook.sh` кладёт новый сертификат, Flexisip отдаёт его без перезапуска, клиент с новым корнем доверия регистрируется.

Итог прогона 2026-11-27: **PASS, 23/23**. Подробности в описании PR.

Здесь **не проверено**:
- выпуск сертификата Let's Encrypt: к этой машине нет входящих соединений на 80/tcp;
- работа на настоящем VPS с публичным IP;
- офис за CGNAT;
- совместимость со стоковым Linphone (AVPF, bundle, его наборы SRTP).

Всё это проверяется спайком после развёртывания.
