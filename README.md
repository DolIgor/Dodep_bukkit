# Dodep Bukkit Plugin (DonationAlerts -> выдача предметов)

Плагин для Paper/Bukkit 1.20.1, который принимает webhook с донатом и выдает предметы игроку по разрядам суммы.

## Что уже есть в проекте
- Maven-проект (`pom.xml`) c Java 17.
- Плагин-класс и webhook-обработчик.
- Конфиг `src/main/resources/config.yml`.

## Как получить готовый `.jar`

### Вариант 1: локально на ПК
1. Установи **JDK 17** и **Maven**.
2. В корне проекта выполни:
   ```bash
   mvn clean package
   ```
3. Готовый файл будет в:
   - `target/dodep-bukkit-1.0.0.jar` (или `-shaded.jar`, зависит от Maven конфигурации).

### Вариант 2: через GitHub Actions (проще)
1. Создай пустой репозиторий на GitHub.
2. Привяжи локальный проект к GitHub и отправь код:
   ```bash
   git remote add origin https://github.com/<ТВОЙ_ЛОГИН>/<ИМЯ_РЕПО>.git
   git branch -M main
   git push -u origin main
   ```
3. На GitHub открой вкладку **Actions** -> workflow **Build Plugin Jar**.
4. После успешной сборки скачай артефакт `dodep-bukkit-jar`.

## Установка на сервер
1. Скопируй `.jar` в папку `plugins/` на сервере Paper 1.20.1.
2. Перезапусти сервер.
3. Отредактируй `plugins/DodepPlugin/config.yml` (webhook host/port/path/token и ID предметов).
4. Перезапусти сервер повторно.

## Настройка webhook/token (простыми словами)
- `webhook.host`, `webhook.port`, `webhook.path` — это адрес, где плагин ждёт входящий POST.
- `webhook.token` — секрет для проверки заголовка `X-DA-Token`.

Если не хочешь руками искать токен, можно вставить OBS ссылку в:

```yml
donationalerts:
  widget-url: "https://www.donationalerts.com/widget/alerts?group_id=1&token=..."
```

Тогда плагин автоматически возьмёт `token=...` из этой ссылки как webhook token
(только если `webhook.token` оставлен пустым).

Дополнительно: плагин принимает токен либо из заголовка `X-DA-Token`, либо как query-параметр `?token=...`.

## Быстрый дебаг (если "ничего не происходит")
1. Проверь, что HTTP-сервер жив:
   - `http://<IP_сервера>:<port>/health` должен вернуть `OK`.
2. Сделай тест без DonationAlerts напрямую (через GET):
   - `http://<IP_сервера>:<port><path>?token=<token>&player=Player223&amount=1234`
3. Если игрок онлайн, плагин должен:
   - написать сообщение в чат,
   - выдать предметы,
   - записать строки в `plugins/DodepPlugin/donations.log`.

4. Тест в игре (самый важный):
   - выполни `/dodeptest <твой_ник> 1234`
   - если это сработало (чат + выдача), значит плагин исправен, проблема только во внешнем webhook.
5. Статус плагина:
   - выполни `/dodepstatus`
   - должно показать `loaded=true`, endpoint и `tokenConfigured=true/false`.

6. Принудительная перезагрузка конфига плагина:
   - выполни `/dodepreload`
   - затем снова `/dodepstatus`
   - в статусе смотри `configuredPort`, `activePort`, `configFile`, `configExists`, `lastModified`.

Если `/dodeptest` и `/dodepstatus` не существуют — плагин не загрузился (или загружен не тот jar).

- Если видишь `Address already in use`, значит порт занят другим процессом.
  - Либо смени `webhook.port`,
  - либо оставь `webhook.max-port-retries` > 0 и плагин сам подберет следующий свободный порт.
  - Текущий реальный порт смотри через `/dodepstatus`.

Примечание: ссылка `https://www.donationalerts.com/widget/alerts?...` сама по себе не отправляет webhook в плагин.
Она используется для OBS-виджета. Для плагина нужен входящий запрос на адрес из `webhook.host/port/path`.

## Важно
- Если в GitHub репо “пусто”, обычно это значит, что код ещё не отправлен (`git push` не выполнен).
- Если локальная сборка не проходит из-за сети/блокировок, используй GitHub Actions — там jar обычно собирается без проблем.


## Режим polling (без входящего webhook)
Если хостинг не позволяет удобно принимать внешние webhook запросы, можно использовать polling:

1. В `plugins/DodepPlugin/config.yml` поставить:
   ```yml
   mode: polling
   polling:
     interval-seconds: 20
     api-url: "<URL метода DA API со списком донатов>"
     token: "<API токен>"
     token-header: Authorization
     token-prefix: "Bearer "
     list-path: data
     fields:
       id: id
       player: username
       amount: amount
   ```
2. Выполнить `/dodepreload`.
3. Проверить `/dodepstatus` — там должен быть `mode=polling` и `pollingStatus=...`.

Плагин запоминает `polling.last-seen-id` и не обрабатывает старые события повторно.
