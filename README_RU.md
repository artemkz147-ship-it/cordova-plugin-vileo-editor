# cordova-plugin-vileo-editor 1.0.0

Нативный Android-мост для проекта Vileo Editor в GDevelop.

API JavaScript:
- `pickVideo(success, error)`
- `getVideoInfo(path, success, error)`
- `exportVideo(options, success, error)`
- `getProgress(success, error)`
- `cancelExport(success, error)`

Экспорт выполняется через Android Jetpack Media3 Transformer 1.11.0. Исходник выбирается системным `ACTION_OPEN_DOCUMENT`, поэтому отдельный доступ ко всей медиатеке приложению не требуется.

В GDevelop расширение `VileoNative` добавляет этот каталог как Cordova dependency. Для облачной сборки, если локальные зависимости не принимаются, этот каталог можно разместить в GitHub и заменить `version` зависимости на URL репозитория.
