# Nexus SSH

Полноценный SSH/SFTP-клиент для Android на Kotlin и Jetpack Compose (Material 3).
Написан как альтернатива Termius: терминал с настоящей эмуляцией xterm, менеджер хостов,
ключи в keystore, порт-форвардинг, SFTP, сниппеты, история и агент ключей.

**Всё локально.** Нет аккаунта, нет синхронизации через чужой сервер, нет телеметрии.

[![Android CI](https://github.com/n1kro-yeah/nexus-ssh-android/actions/workflows/android.yml/badge.svg)](https://github.com/n1kro-yeah/nexus-ssh-android/actions/workflows/android.yml)

---

## Возможности

### Соединения
- SSH-2 через SSHJ: `ed25519`, `ecdsa-sha2-nistp256/384/521`, `rsa` 2048–4096
- Обмен ключами: `curve25519-sha256`, `ecdh-sha2-*`, `diffie-hellman-group14/16/18`
- Шифры: `chacha20-poly1305`, `aes*-gcm`, `aes*-ctr`
- Аутентификация: пароль, публичный ключ, `keyboard-interactive` (в том числе 2FA/OTP),
  агент ключей
- Jump-хосты (`ProxyJump`) с произвольной длиной цепочки
- Keep-alive, авто-переподключение с ограничением попыток, таймауты соединения
- Проверка ключа сервера при первом подключении (TOFU) с сохранением в базу известных хостов;
  изменившийся ключ **останавливает** соединение и требует явного подтверждения
- Отпечатки SHA256/MD5 и `randomart` для визуальной сверки

### Терминал
- Собственный эмулятор: CSI/OSC/DCS-парсер по схеме vt100.net, альтернативный экран,
  прокрутка региона, `bracketed paste`, отчёты о мыши (SGR), 256 цветов и true color
- Буфер прокрутки до 200 000 строк, поиск с регулярными выражениями, выделение и копирование
- Дополнительный ряд клавиш (Ctrl, Alt, Esc, Tab, стрелки, F1–F12), «липкие» модификаторы
- 11 цветовых схем (Nexus Dark, Dracula, Solarized, Nord, Gruvbox, One Dark, Monokai,
  Catppuccin и другие), настройка шрифта, межстрочного расстояния, курсора
- Несколько сессий одновременно с переключением, сессии живут в foreground-сервисе

### Файлы
- SFTP: просмотр, скачивание, загрузка, переименование, удаление, `chmod`, рекурсивные операции
- Передача в foreground-сервисе с прогрессом в уведомлении и записью в базу,
  чтобы список передач был честным даже после перезапуска процесса
- Информация о свободном месте (`statVFS`), скрытые файлы, символические ссылки

### Туннели
- Локальный (`-L`), удалённый (`-R`) и динамический (`-D`, SOCKS5) форвардинг
- Собственный SOCKS5-сервер поверх SSH-канала
- Правила с автозапуском при подключении хоста

### Ключи и секреты
- Генерация Ed25519, ECDSA P-256/384/521, RSA 2048/3072/4096
- Импорт OpenSSH и PKCS#8/PKCS#1 (в том числе зашифрованных), экспорт в переносимом
  зашифрованном виде (`nxp1:`, PBKDF2 + AES-GCM)
- Приватные ключи и пароли шифруются ключом из Android Keystore: он не покидает устройство
- Агент ключей с подтверждением каждой подписи, форвардинг агента
- Блокировка приложения биометрией или PIN устройства, авто-блокировка,
  запрет скриншотов, автоочистка буфера обмена

### Организация
- Группы с наследуемыми настройками, идентичности (переиспользуемые логин/пароль/ключ)
- Теги, заметки, цвета, избранное, поиск
- Сниппеты с переменными (`${name}`) и запуском при подключении
- История подключений с длительностью, объёмом трафика и ошибками
- Резервная копия в зашифрованный архив и восстановление из него
- Ярлыки на рабочем столе, deep links: `ssh://`, `sftp://`, `telnet://`, `nexusssh://`

---

## Архитектура

```
app/src/main/java/com/nikro/nexusssh/
  core/crypto/      CryptoVault (Keystore), кодек ключей, генератор, импортёр
  data/local/       Room: сущности, DAO, мапперы, база
  data/prefs/       DataStore: AppSettings и сеттеры
  data/repository/  Хосты, ключи, известные хосты, сниппеты, туннели, история
  data/backup/      Экспорт и импорт зашифрованного архива
  domain/model/     Чистые модели: Host, Identity, SshKey, PortForwardRule, ...
  ssh/              SshConnection, SshSessionManager, auth, hostkey, sftp, forwarding, agent
  terminal/         Эмулятор, буфер, палитры, темы, ввод, поиск, выделение, сессия
  service/          Сервисы сессий, туннелей, передач; каналы уведомлений
  di/               Модули Hilt
  ui/               Compose: hosts, terminal, sftp, keychain, forwarding, snippets,
                    history, knownhosts, identities, settings, onboarding, lock
```

Слои: UI (Compose + ViewModel) → репозитории → Room/DataStore и SSH-движок.
ViewModel не знает про SSHJ, UI не знает про Room. Состояние — `StateFlow`,
длительные операции — корутины на `Dispatchers.IO`.

---

## Сборка

Требуется JDK 17 и Android SDK 35.

```bash
# один раз: сгенерировать gradle wrapper (нужен интернет)
gradle wrapper --gradle-version 8.11.1

./gradlew assembleDebug
./gradlew installDebug
```

Подпись release-сборки берётся из `~/.gradle/gradle.properties`:

```properties
NEXUS_STORE_FILE=/путь/к/keystore.jks
NEXUS_STORE_PASSWORD=...
NEXUS_KEY_ALIAS=nexus
NEXUS_KEY_PASSWORD=...
```

### CI

GitHub Actions (`.github/workflows/android.yml`) собирает debug-APK на каждый push,
кладёт его в артефакты сборки, а при пуше тега `v*` публикует APK в Releases.
Для подписанной release-сборки добавьте секреты репозитория: `KEYSTORE_BASE64`
(keystore в base64), `NEXUS_STORE_PASSWORD`, `NEXUS_KEY_ALIAS`, `NEXUS_KEY_PASSWORD`.

> В песочнице, где писался код, нет ни Gradle, ни Android SDK, ни сети,
> поэтому проект **не компилировался** локально. Первая сборка в CI почти наверняка
> потребует мелких правок импортов и версий зависимостей.

---

## Чего нет

- **Mosh** — нужен UDP-протокол роуминга и серверный бинарник, поэтому настройка
  зарезервирована, но не реализована
- **X11-форвардинг** открывает канал, но требует отдельного X-сервера на устройстве
- **Локальный shell** без PTY: Android не даёт `forkpty` без NDK-обвязки
- Форвардинг агента использует reflection к внутреннему API SSHJ
- Нет облачной синхронизации — это осознанное решение, а не пропуск

---

## Лицензии зависимостей

SSHJ (Apache 2.0), Bouncy Castle (MIT-style), `net.i2p.crypto:eddsa` (CC0),
AndroidX/Compose/Room (Apache 2.0), Hilt (Apache 2.0).
