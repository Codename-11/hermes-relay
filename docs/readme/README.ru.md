# Hermes-Relay

**Работает на вашем компьютере. Доступен с ваших устройств.**

[English](../../README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [日本語](README.ja.md) · [Português (Brasil)](README.pt-BR.md) · **Русский** · [简体中文](README.zh-CN.md)

> Английская версия — полное и каноническое описание проекта. Этот перевод,
> выполненный с помощью ИИ, — краткое поддерживаемое введение.

Hermes-Relay переносит ваш [Hermes Agent](https://github.com/NousResearch/hermes-agent)
на Android и подключённые компьютеры. Сам Hermes продолжает работать на вашем
компьютере, а Hermes-Relay предоставляет нативные интерфейсы и дополнительные расширения.

## Основные возможности

- **Android:** потоковый чат, сессии, входящие файлы, Manage, голосовой режим и Petdex.
- **Стандартное подключение:** чат, Manage, сессии, голос и файлы подключаются напрямую к неизменённому Hermes Dashboard/Gateway.
- **Необязательное расширение Relay:** Terminal/TUI, уведомления, инструменты рабочего стола, расширенный голосовой режим, сессии Relay и работа с медиа.
- **Sideload-версия:** добавляет Device Control с подтверждением для чтения экрана, нажатий и навигации.
- **CLI / UI:** подключает компьютеры напрямую к Relay и предоставляет требующие согласия инструменты для файлов, терминала, поиска и снимков экрана.

## Быстрый старт на Android

1. Установите приложение из [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) или загрузите подписанный sideload APK из последнего выпуска [`android-v*`](https://github.com/Codename-11/hermes-relay/releases).
2. Запустите стандартную поверхность на компьютере с Hermes:

   ```bash
   hermes dashboard
   ```

3. На Android откройте **Connect**, найдите Hermes в локальной сети или введите адрес Dashboard и войдите в систему. Для стандартного пути не нужны Relay или отдельный API-ключ.
4. Установите Relay, только если нужны дополнительные возможности:

   ```bash
   hermes plugins install Codename-11/hermes-relay/plugin --enable
   hermes relay doctor
   hermes relay start --no-ssl
   ```

   Используйте `--no-ssl` только в доверенной локальной сети или VPN. Затем выполните сопряжение через **Relay → Pair new device** в Dashboard.

## Подробнее

Русская пользовательская документация пока не локализована. Быстро меняющиеся
сведения остаются каноническими на английском:

[Быстрый старт на английском](https://hermes-relay.dev/docs/guide/quick-start) ·
[Установка](https://hermes-relay.dev/docs/guide/getting-started) ·
[Устранение неполадок](https://hermes-relay.dev/docs/guide/troubleshooting) ·
[Полная документация](https://hermes-relay.dev/docs/)

[Лицензия MIT](../../LICENSE)
