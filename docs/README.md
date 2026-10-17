# Документация проекта

Пользовательское описание и установка: [README](../README.md). История возможностей: [CHANGELOG](../CHANGELOG.md).

## Текущие документы

- [Архитектура AFP Online](ONLINE_ARCHITECTURE.md)
- [Слияние локальной и онлайн-веток](INTEGRATION_0.8.4.md)
- [Правила описаний релизов](RELEASE_NOTES_POLICY.md)
- [Хранение файлов сборок](STORAGE_POLICY.md)
- [Версии](../VERSIONING.md)

## Сборка

JDK 17, Android SDK 36, Gradle Wrapper 8.11.1.

```sh
./gradlew :app:assembleRelease :translator:assembleRelease :online:assembleRelease :app:testReleaseUnitTest :translator:testReleaseUnitTest :online:testReleaseUnitTest :app:lintRelease :translator:lintRelease :online:lintRelease
```

Без локального ключа создаются неподписанные APK. Для обновления установленного приложения нужен прежний ключ. Закрытый ключ не хранится в Git и не передаётся в Actions.

## Исторические материалы

Документы PLAN, ROADMAP, REFACTOR, STAGING, RELEASE и отчёты YANDEX_PREVIEW описывают состояние на дату соответствующей итерации. Они сохранены для истории и не определяют текущие возможности приложения. Исследования Звука остаются справочными; работа над подключением приостановлена.

Механика публикации предыдущих версий: [GITHUB_RELEASES](GITHUB_RELEASES.md). Пользовательские описания выпусков находятся в GitHub Releases; технические проверки — в документах и Actions.
