### VK-Turn-Proxy Android v1.1.0 (Список изменений)

**Основные изменения:**
* **SSH Terminal**: Полноценный интерактивный Shell (PTY) с поддержкой ввода и MOTD.
* **Remote Management**: Функции установки и управления удаленным сервером (бинарники `server-linux-*`).
* **Process Management**: Остановка процессов по PID-файлам и маске имени.
* **Architecture**: Автоопределение архитектуры (amd64/arm64) через `uname -m`.
* **Networking**: Настройка портов `Listen` и `Connect` через интерфейс.
* **UI Controls**: Кнопка CTRL+C (ASCII 3), функции COPY и CLEAR для логов.
* **Settings**: Добавлено отдельное окно настроек `SettingsActivity` для SSH.

**Технические правки:**
* **Version**: Обновление до v1.1.0 (code 2).
* **SDK**: Понижение `minSdk` до 23 (Android 6.0+).
* **Dependencies**: Интеграция `jsch` (v0.2.17) и `kotlinx-coroutines`.
* **Threading**: SSH-запросы и проверки статуса переведены на корутины.
* **Native**: `extractNativeLibs="true"`, `jniLibs.useLegacyPackaging = true`.
* **Resources**: Фикс иконок для старых API (`mipmap-anydpi-v26`).
* **Scripts**: Добавлена фильтрация `grep -v grep` в проверках.
