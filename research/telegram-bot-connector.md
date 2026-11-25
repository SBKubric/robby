# Telegram Bot API для коннектора

Исследование к тикету #3. Вопрос: как **Коннектор** Telegram (бот, который только пишет в группу) настраивается и работает с **Телефона-сервера** без собственного бэкенда.

Источники проверены 2026-11-25. Актуальная версия на момент проверки: **Bot API 10.5** (22 ноября 2026).

- [API] https://core.telegram.org/bots/api
- [FAQ] https://core.telegram.org/bots/faq
- [FEAT] https://core.telegram.org/bots/features
- [SRC] исходники Bot API сервера `tdlib/telegram-bot-api`, ветка master, коммит `5df614c9`: `telegram-bot-api/Client.cpp`, `Query.cpp`, `ClientManager.cpp` (https://github.com/tdlib/telegram-bot-api). Документация не перечисляет тексты ошибок, поэтому они взяты из исходников сервера.
- [PROBE] запросы к `api.telegram.org` с заведомо поддельным токеном (2026-11-25).

## Короткий ответ

1. Работа идёт только исходящими HTTPS-запросами к `https://api.telegram.org/bot<token>/<method>`. Ни бэкенд, ни webhook не нужны.
2. **Постоянный long polling не нужен.** `getUpdates` нужен только в мастере настройки, чтобы узнать `chat_id`. После этого достаточно `sendMessage`. Миграция группы в супергруппу ловится по ответу на отправку (`migrate_to_chat_id`), а удаление бота из группы ловится по 403.
3. Самый надёжный способ получить `chat_id`: deep link `https://t.me/<bot>?startgroup=<nonce>`. Приложение генерирует nonce, затем делает long polling `getUpdates`, пока не придёт `/start@<bot> <nonce>`, и берёт `message.chat.id`. Это работает при включённом privacy mode, отключать его не нужно.
4. Лимит группы: **20 сообщений в минуту** на бота. На 429 надо ждать `parameters.retry_after` секунд и повторить.
5. Форматирование: **HTML**. В подставляемых значениях (номер, текст SMS) экранируются только `&`, `<`, `>`. MarkdownV2 требует экранировать 18 символов и `\`, поэтому ошибиться в нём легче.
6. Диагностику строим по HTTP-коду и `parameters`. Поле `error_code` по документации «subject to change», а `description` показываем пользователю как есть.

## 1. Получение chat_id группы

### Что приходит боту при добавлении в группу

- Privacy mode включён по умолчанию. В группе такой бот видит только команды, адресованные ему (`/command@this_bot`), общие команды, если он последним из ботов писал в группу, сообщения через себя и ответы на свои сообщения. **Служебные сообщения он получает при любых настройках** [FAQ «What messages will my bot get?»; FEAT «Privacy Mode»].
- Поэтому при добавлении бота приходит:
  - `message.new_chat_members`, где среди участников может быть сам бот [API, Message];
  - отдельный апдейт `my_chat_member` (тип `ChatMemberUpdated`): «The bot's chat member status was updated in a chat» [API, Update]. Он входит в набор по умолчанию: пустой `allowed_updates` означает «все типы, кроме `chat_member`, `message_reaction`, `message_reaction_count`» [API, getUpdates].
- В обоих случаях `chat.id` есть в объекте `Chat`, а `chat.type` равен `group` или `supergroup` [API, Chat].

### Рекомендуемый сценарий мастера (без ручного ввода id)

1. Пользователь вводит токен. Приложение вызывает `getMe`: «A simple method for testing your bot's authentication token» [API, getMe]. Из ответа берём `username` бота.
2. Приложение генерирует nonce (до 64 символов `A-Z a-z 0-9 _ -`) и показывает кнопку или QR со ссылкой `https://t.me/<bot_username>?startgroup=<nonce>`. По ссылке Telegram предлагает выбрать группу, а бот получает текст `/start@your_bot <nonce>` [FEAT, Deep Linking → Groups]. Это команда, адресованная боту, поэтому privacy mode её пропускает.
3. Пока открыт экран мастера, приложение крутит `getUpdates?timeout=30&offset=<last+1>`. `timeout` > 0 включает long polling, а короткий polling документация советует только для тестов [API, getUpdates]. Подходящим считается апдейт, где `message.text == "/start@<bot> <nonce>"`, и из него берётся `message.chat.id`. Запасной путь: `my_chat_member` со статусом `member` или `administrator`, показать пользователю название группы (`chat.title`) и попросить подтверждение.
4. Чтобы эти апдейты больше не приходили, вызываем `getUpdates` с `offset = update_id + 1`. Документация: «An update is considered confirmed as soon as getUpdates is called with an offset higher than its update_id» [API, getUpdates; FAQ «Long polling gives me the same updates»].
5. Проверочное сообщение `sendMessage` в найденный чат подтверждает, что бот может писать.

Если бот уже состоит в группе, тот же nonce можно ввести вручную: пользователь пишет в группе `/start@<bot> <nonce>`.

### Подводные камни getUpdates

- **Апдейты хранятся не дольше 24 часов** [API, Getting updates]. Если бот был добавлен давно, его апдейт уже пропал. Поэтому мастер должен сначала включить polling и только потом просить добавить бота.
- **Webhook и getUpdates взаимоисключающие.** Если для токена установлен webhook, `getUpdates` вернёт 409 `Conflict: can't use getUpdates method while webhook is active; use deleteWebhook to delete the webhook first` [API, getUpdates Notes; SRC Client.cpp]. Мастер может проверить это через `getWebhookInfo` (у бота без webhook `url` пуст) и предложить `deleteWebhook` [API]. Удалять webhook молча нельзя: токен мог использоваться где-то ещё.
- Два одновременных `getUpdates` на один токен дают 409 `Conflict: terminated by other getUpdates request; make sure that only one bot instance is running` [SRC]. Это ещё один повод держать отдельного бота на каждый Телефон-сервер.
- Id чатов не помещаются в 32 бита: «at most 52 significant bits, so a signed 64-bit integer … is safe» [API, Chat]. В Kotlin и Room храним `Long`.
- Формат id [SRC `get_basic_group_chat_id`/`get_supergroup_chat_id`]: у обычной группы `-<group_id>`, у супергруппы `-1000000000000 - <supergroup_id>` (вид `-100…`). Это деталь реализации, на неё не полагаемся, просто храним число.
- Публичную супергруппу можно адресовать через `@username`: `chat_id` принимает «username of the target … supergroup» [API, sendMessage/getChat]. Для приватных групп этот путь не работает, поэтому основным остаётся getUpdates.
- Альтернатива через `KeyboardButtonRequestChat` (кнопка выбора чата в личке с ботом, ответ приходит служебным сообщением `chat_shared`) [FEAT «Chat and User Selection»] тоже требует getUpdates и личного диалога с ботом. Перед deep link у неё преимуществ нет.

### Миграция группы в супергруппу

- В старой группе появляется служебное `migrate_to_chat_id`, в новой `migrate_from_chat_id` [API, Message].
- Отправка в старый id возвращает **400 `Bad Request: group chat was upgraded to a supergroup chat`** с `parameters.migrate_to_chat_id` [API, ResponseParameters; SRC Client.cpp]. Коннектор должен сохранить новый id и повторить отправку **автоматически**, без участия пользователя. Слушать апдейты для этого не нужно.
- Если группа удалена: 403 `Forbidden: the group chat was deleted` [SRC].

### Темы (topics)

- Темы бывают только в супергруппах: `Chat.is_forum` «True, if the supergroup chat is a forum (has topics enabled)» [API, Chat]. Значит, при включении тем группа станет супергруппой (см. миграцию выше).
- Чтобы писать в конкретную тему, нужен `sendMessage.message_thread_id`: «Unique identifier for the target message thread (topic) of a forum» [API, sendMessage]. Его берём из `message.message_thread_id` того же апдейта `/start@bot <nonce>`, если команду отправили внутри темы (`is_topic_message`) [API, Message].
- Неверная или удалённая тема: 400 `Bad Request: message thread not found` [SRC].
- Для v0.1 достаточно хранить в настройках коннектора необязательный `message_thread_id`.

## 2. Лимиты и 429

- FAQ [«My bot is hitting limits»]: в одном чате не чаще ~1 сообщения в секунду (короткие всплески допускаются); **в группе не более 20 сообщений в минуту**; общий broadcast около 30 в секунду. FEAT предупреждает: «All limits may be subject to change».
- При превышении приходит HTTP 429 с телом `{"ok":false,"error_code":429,"description":"Too Many Requests: retry after N","parameters":{"retry_after":N}}`. Формат виден в `Query::set_retry_after_error` [SRC Query.cpp], поле описано в [API, ResponseParameters]: «the number of seconds left to wait before the request can be repeated».
- Что из этого следует для Robby:
  - очередь исходящих сообщений на каждый чат, с интервалом не меньше 3 секунд (20 в минуту);
  - на 429 ждать ровно `retry_after` (плюс небольшой запас) и повторять; очередь должна переживать рестарт процесса (WorkManager или Room);
  - при всплеске (пачка SMS) склеивать несколько событий в одно сообщение, а не упираться в лимит;
  - `allow_paid_broadcast` (платно, Stars) не нужен.

## 3. Форматирование и экранирование

- `parse_mode` принимает `HTML`, `MarkdownV2` и устаревший `Markdown` [API, Formatting options]. Вместо `parse_mode` можно передать массив `entities` со смещениями в UTF-16 code units [API, sendMessage, MessageEntity].
- **HTML** [API, HTML style]: «All `<`, `>` and `&` symbols that are not a part of a tag or an HTML entity must be replaced with the corresponding HTML entities». Поддерживаются только перечисленные теги и именованные сущности `&lt; &gt; &amp; &quot;`. Внутри `href` атрибута кавычку экранируем `&quot;`.
- **MarkdownV2** [API, MarkdownV2 style]: вне сущностей экранировать `_ * [ ] ( ) ~ \` > # + - = | { } . !` и сам `\`, а внутри `code`/`pre` и ссылок действуют свои правила. Точка или минус в номере телефона без экранирования ломают отправку.
- **Рекомендация:** шаблон сообщения храним как HTML, а каждую подставляемую переменную (номер, имя контакта, текст SMS) пропускаем через `escapeHtml` (`&`→`&amp;`, `<`→`&lt;`, `>`→`&gt;`). Экранировать нужно именно подстановки: пользовательский шаблон может содержать теги.
  - Альтернатива без экранирования: `entities`. Kotlin `String` хранит текст в UTF-16, поэтому смещения считаются напрямую. Это стоит рассмотреть, если редактор шаблонов будет визуальным.
- Длина `text` составляет 1–4096 символов **после разбора сущностей** [API, sendMessage]. Длинное SMS обрезаем **до** экранирования (по исходному тексту), иначе можно разрезать `&amp;` пополам.
- В SMS бывают ссылки. Чтобы Telegram не строил превью, ставим `link_preview_options.is_disabled = true` [API, LinkPreviewOptions]. Это заодно не даёт серверам Telegram ходить по ссылкам из SMS.
- Битая разметка возвращает 400 `Bad Request: can't parse entities: …`. Это наблюдаемое поведение, точный текст приходит из TDLib, в [SRC] его нет. Fallback: при 400 с таким префиксом повторить отправку без `parse_mode`.

## 4. Диагностика ошибок в UI

Общая форма ответа [API, Making requests]: `ok`, `description`, `error_code` («contents are subject to change»), необязательный `parameters`. Классифицируем по HTTP-статусу и `parameters`, а `description` показываем как подробность.

| Ситуация | Ответ | Источник | Что делает Robby |
|---|---|---|---|
| Токен перевыпущен (`/token` в BotFather) или неверный | 401 `Unauthorized` | PROBE; SRC `get_closing_error` | Коннектор → «Токен недействителен», нужен новый токен; ретраи остановить |
| Токен синтаксически битый (нет `:`) | 404 `Not Found` (облачный сервер); у локального сервера 401 `Unauthorized: invalid token specified` | PROBE; SRC ClientManager.cpp | Валидировать формат при вводе |
| Бота удалили или забанили в группе | 403 `Forbidden: bot was kicked from the group chat` / `…supergroup chat` | SRC | «Бот удалён из группы», пройти мастер заново |
| Бот вышел или не состоит в группе | 403 `Forbidden: bot is not a member of the group chat` / `…supergroup chat` | SRC | То же |
| Группа удалена | 403 `Forbidden: the group chat was deleted` | SRC | То же |
| Группа стала супергруппой | 400 `group chat was upgraded to a supergroup chat` + `parameters.migrate_to_chat_id` | API; SRC | Обновить id, повторить, ошибку не показывать |
| Неверный chat_id | 400 `Bad Request: chat not found` | SRC | «Чат не найден», мастер |
| Тема не найдена | 400 `Bad Request: message thread not found` | SRC | Сбросить тему или предупредить |
| Флуд-лимит | 429 + `parameters.retry_after` | API; SRC Query.cpp | Подождать и повторить, в UI не показывать как ошибку |
| Webhook мешает getUpdates (мастер) | 409 `Conflict: can't use getUpdates…` | SRC | Предложить `deleteWebhook` |
| Другой экземпляр опрашивает тот же токен | 409 `Conflict: terminated by other getUpdates request…` | SRC | «Токен используется другим приложением» |
| Сеть, 5xx | нет ответа / 500 | — | Ретрай с экспоненциальной паузой |

Правило: **4xx, кроме 429 и миграции, не ретраим**, а переводим коннектор в состояние «ошибка» с текстом. 429, 5xx и сетевые сбои ретраим.

Для проверки состояния из UI («Проверить коннектор») подходят:
- `getMe`, проверяет токен [API];
- `getChat(chat_id)`, проверяет доступ к чату и показывает текущий `title`/`is_forum` [API];
- `getChatMember(chat_id, <bot id>)`, возвращает статус бота: `member`, `administrator`, `restricted`, `left`, `kicked` [API, ChatMember*]. Документация гарантирует работу метода только для себя или если бот админ: «only guaranteed to work for other users if the bot is an administrator».

Узнать об удалении бота заранее можно через апдейт `my_chat_member` (`new_chat_member.status = left|kicked`), но для этого нужен постоянный polling. Предлагается обходиться без него: ошибку обнаружит первая же отправка.

## 5. Нужен ли long polling

- Для работы коннектора **нет**. Отправка идёт обычными запросами без подписки, а миграция и удаление обнаруживаются по ответам (разделы 1 и 4).
- Long polling нужен **только в мастере настройки** (поиск `chat_id`, `message_thread_id`) и, по желанию, в кнопке «Проверить». Пока экран открыт, это одно HTTP-соединение на 30 секунд за раз, на батарею и фоновые ограничения Android оно не влияет.
- Отсутствие постоянного polling совпадает с решением карты «Приём команд Telegram-ботом: бот только пишет» (Out of scope в #1).
- Приём команд потребует постоянного polling, то есть foreground service и влияние на батарею. Это отдельная тема на будущее.

## 6. Безопасность токена

- «Keep your token secure… it can be used by anyone to control your bot». Новый токен выпускается через `/token` в BotFather [FEAT, BotFather]. Старый после этого перестаёт работать (401).
- Токен входит в путь URL (`/bot<token>/…`) [API, Authorizing your bot]. Логирование HTTP (OkHttp `HttpLoggingInterceptor` и подобное) обязательно маскирует путь, иначе токен попадёт в logcat и в экспорт логов.
- Хранение: Android Keystore. Как переносить токен при синхронизации, решает отдельный пункт карты («Формат файла синхронизации и шифрование секретов»).

## Открытые вопросы (появились в ходе исследования)

1. **Склеивание событий при всплеске.** Объединять ли несколько SMS или пропущенных звонков в одно сообщение, если очередь упирается в 20 в минуту, и какое окно ожидания? Решается вместе с моделью сценария.
2. **Персистентная очередь исходящих.** Сообщения должны переживать перезагрузку телефона и пропадание сети. Нужен ли TTL, после которого старое уведомление не отправляется? Связано с исследованием фоновых ограничений Android.
3. **Синтаксис шаблона сообщения.** HTML-шаблон с подстановками или визуальный редактор с `entities`? Влияет на UI действия «Отправить в Telegram».
4. **Один бот или несколько.** Делить ли бота между несколькими коннекторами или группами в одном приложении? Технически один токен может писать в несколько чатов, но лимит 20 в минуту считается на группу.
5. **Поведение при 401/403.** Показывать ли постоянное уведомление на Телефоне-сервере, если коннектор сломался? Пользователь там бывает редко, а продублировать сигнал в Telegram в этом случае невозможно.
