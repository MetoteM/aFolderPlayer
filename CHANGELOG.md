## 0.7.0

- Перенесены сравнение полного текста, выбор блока или предложений, копирование и история переводов из AFP Translate Lab в плеер.
- Сохранённый выбранный результат отображается на обычном экране перевода песни. Прежние переводы остаются доступны.
- Сравнение отправляет все уникальные части одним запросом; повторы сохраняются при сборке результата.
- Отмена и неполный ответ блокируют сохранение. Исправлена гонка отмены при получении ответа движка.
- AFP Translate 0.2.0 поддерживает пакеты v1 и v2 отдельно. В v2 перенесён проверенный исправленный токенизатор.
- Добавлены регрессионные проверки границ предложений, повторов, истории, отмены и установки пакета v2.

## 0.6.0

- Added explicit update checks in About using stable GitHub releases.
- APK downloads verify size, SHA-256, package, signing identity and increasing version code before opening Android installation.
- About now displays the installed version. No background checks.

## 0.5.2

- Fixed empty icon hints.
- Coalesced equalizer preference updates and preserved filter state when settings are unchanged.
- Added PCM regression checks for neutral output, reset and repeated settings.

# Изменения

## 0.5.1 — в разработке

- Источник расширений GitHub Releases по умолчанию.
- Ограниченные HTTPS-перенаправления GitHub при скачивании.
- Сборка неподписанных APK без закрытого ключа.
- Автоматические сборка, тесты и lint в GitHub Actions.

## 0.5.0

- Движок перевода вынесен в отдельное приложение.
- Необязательные языковые пакеты, проверка подписанного каталога и файлов.
