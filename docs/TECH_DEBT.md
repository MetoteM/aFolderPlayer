# Технический долг

## TD-DEVICE-077 — проверка 0.7.7 на Honor Magic 7 Pro

- Статус: открыт, владелец Алекс.
- Дата решения: 13 октября 2026.
- Только для 0.7.7 пользователь явно разрешил стабильный выпуск без проверки
  на устройстве: сейчас он не может её выполнить и принял проверку как техдолг.
- Автоматические сборка/тесты/lint, реальные переводы всех языков, подписи APK
  и каталога, контрольные суммы опубликованных файлов пройдены.
- Не проверены на телефоне: обновление 0.7.6 → 0.7.7 и AFP Translate
  0.3.1 → 0.3.2, установка французского пакета, отмена/повтор скачивания,
  выбор языка отдельного текста, память, история, воспроизведение и фоновые
  команды гарнитуры/часов.
- Закрытие: выполнить эти сценарии, записать результаты, оформить найденные
  недочёты отдельными задачами.
- Это разовое исключение. Для следующих выпусков проверку телефона не
  пропускать автоматически; новое исключение требует отдельного решения
  пользователя. Исторически недостатки исправлялись после пользовательских
  отзывов; это не означает принятие такого порядка для будущих релизов.

## TD-DEVICE-078 — мобильная проверка 0.7.8

- Статус: открыт.
- Решение от 13 октября 2026: пользователь явно поручил выпустить 0.7.8
  без своей проверки на устройстве. Это отдельное исключение для версии.
- Проверить после выпуска: обновление плеера 0.7.7 → 0.7.8 и переводчика
  0.3.2 → 0.3.3, установку испанского через каталог, совместимость прежних
  языков, отмену/повтор скачивания, историю, память, фоновое воспроизведение
  и управление гарнитурой/часами.
- Автоматические проверки и сверка публичного комплекта остаются
  обязательными. Исключение не переносится на последующие версии.

## TD-DEVICE-079 — проверка устройства после публикации 0.7.9

Пользователь 13 октября 2026 явно разрешил публикацию, затем проверку версии 0.7.9. Автоматические Android checks пройдены для 29157b850a7e66a40dd72d35834eff663c60149d. После выпуска проверить реальное добавление MP3/FLAC через Android-провайдер файлов, обновление связанных плейлистов и очереди, декодирование/определение текущего файла, оба режима определителя, обновление с 0.7.8 без потери данных, историю/отмену удаления/позиции чтения и компоновку на устройстве. Решение относится только к 0.7.9 и не отменяет проверку подписи и публичных вложений.

## TD-DEVICE-080 — физическое устройство и расход батареи

Пользователь 14 октября поручил выполнить релиз 0.8.0. Автоматический CI исходников пройден; перед публикацией выполняется smoke подписанного APK на Android-эмуляторе. Проверка физического ARM64-устройства остаётся открытой: воспроизведение MP3/FLAC через Bluetooth/USB, оба режима определителя, все языки, фоновое управление и сравнение батареи при одинаковых условиях. Эмулятор проверяет установку, совместимость подписи, сохранность плейлиста и поворот; не измеряет расход реального телефона.

## TD-ARCH-095 — lifecycle and library storage after 0.9.5

The review follow-up moves library scanning to an application-context worker retained
across configuration changes. Final screen destruction interrupts it and detaches
its observer. Playback startup reads queue metadata on a worker and applies the
result on the player looper; explicit queue commands invalidate late restoration.
A pending restore must never write an empty queue or overwrite saved positions.

LibraryTrackStore shares an immutable decoded snapshot across Library instances.
Changes to the original preferences invalidate it; exclusions, hidden tracks and
manual album years remain outside the raw cache. The 0.9.5 storage format remains
compatible. This removes repeated JSON decoding, not the cost of cold loading or
full-list filtering. MainActivity still reads the library synchronously at startup.

Remaining work before calling the architecture cleanup complete:

- Give remaining Activity workers explicit lifecycle ownership and cancellation;
  extract library browsing and playback connection responsibilities incrementally.
- Move cold UI loading off the main thread, with loading/error states and tests
  for selection and navigation while loading.
- Measure large libraries on a device; migrate persistent metadata to a repository
  with indexed storage if justified. Test migration failure, restart and preservation
  of roots, playlists, exclusions, manual tags and playback references.
- Perform physical-device checks for background playback, headset commands, service
  restart and rotation during scanning. Unit tests do not replace these checks.

APK identity checks use current signer sets on API 28+ and the legacy API on 26–27.
All current signers must match exactly. Historical ancestors alone are not trusted.
Key rotation is intentionally not enabled by this refactor: it needs an explicit
upgrade policy, Android-version compatibility checks and a recovery exercise using
verified backups. Preserve the existing release key and signing identity.
