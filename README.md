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

## Важно
- Если в GitHub репо “пусто”, обычно это значит, что код ещё не отправлен (`git push` не выполнен).
- Если локальная сборка не проходит из-за сети/блокировок, используй GitHub Actions — там jar обычно собирается без проблем.
