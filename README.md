<div align="center">

# Nexus SSH

**Нативный SSH / SFTP-клиент для Android**  
Терминал, хосты, ключи, SFTP и туннели — локально на устройстве.

<a href="https://github.com/n1kro-yeah/nexus-ssh-android/actions/workflows/android.yml"><img src="https://img.shields.io/github/actions/workflow/status/n1kro-yeah/nexus-ssh-android/android.yml?branch=main&label=Android%20CI&logo=githubactions&logoColor=white" alt="Android CI" /></a>
<a href="https://github.com/n1kro-yeah/nexus-ssh-android/releases"><img src="https://img.shields.io/github/v/release/n1kro-yeah/nexus-ssh-android?display_name=tag&sort=semver&logo=github" alt="Latest release" /></a>
<a href="https://github.com/n1kro-yeah/nexus-ssh-android/releases"><img src="https://img.shields.io/badge/Releases-APK-3DDC84?logo=android&logoColor=white" alt="APK releases" /></a>
<a href="https://github.com/n1kro-yeah/nexus-ssh-android/actions/workflows/android.yml"><img src="https://img.shields.io/badge/Artifacts-nexus--ssh--apk-2088FF?logo=github" alt="Build artifacts" /></a>

<a href="#package"><img src="https://img.shields.io/badge/package-com.nikro.nexusssh-3D6DF2?logo=android&logoColor=white" alt="Application package" /></a>
<a href="#technology-stack"><img src="https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white" alt="Minimum SDK 26" /></a>
<a href="#technology-stack"><img src="https://img.shields.io/badge/targetSdk-35-3DDC84?logo=android&logoColor=white" alt="Target SDK 35" /></a>
<a href="#technology-stack"><img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.0.21" /></a>
<a href="#technology-stack"><img src="https://img.shields.io/badge/Jetpack%20Compose-2024.12.01-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" /></a>
<a href="#technology-stack"><img src="https://img.shields.io/badge/Material%203-Compose-757575?logo=materialdesign&logoColor=white" alt="Material 3" /></a>

<a href="#technology-stack"><img src="https://img.shields.io/badge/SSH-SSHJ%200.38.0-4B8BBE?logo=openssh&logoColor=white" alt="SSHJ" /></a>
<a href="#technology-stack"><img src="https://img.shields.io/badge/storage-Room%20%2B%20DataStore-00897B?logo=sqlite&logoColor=white" alt="Room and DataStore" /></a>
<a href="#technology-stack"><img src="https://img.shields.io/badge/DI-Hilt-34A853?logo=dagger&logoColor=white" alt="Hilt" /></a>
<a href="#security"><img src="https://img.shields.io/badge/secrets-Android%20Keystore-F57C00?logo=android&logoColor=white" alt="Android Keystore" /></a>

[Возможности](#features) · [Стек](#technology-stack) · [Быстрый старт](#quick-start) · [CI и релизы](#ci-and-releases) · [Карта проекта](#project-map) · [Безопасность](#security)

</div>

---

<a id="overview"></a>
## О проекте

**Nexus SSH** — Android-приложение на Kotlin для работы с SSH-хостами: интерактивный xterm-совместимый терминал, SFTP-файловый менеджер, ключи, ProxyJump, порт-форвардинг, сниппеты и история подключений.

Проект проектируется как локальная альтернатива облачным SSH-клиентам:

- без учётной записи, телеметрии и обязательной облачной синхронизации;
- приватные ключи и пароли защищаются Android Keystore;
- данные хранятся на устройстве в Room и DataStore;
- UI построен на Jetpack Compose и Material 3.

> **Статус:** проект находится в активной разработке. Финальный APK появляется в артефактах CI после успешной сборки; GitHub Release публикуется при теге формата `v*`.

<a id="contents"></a>
## Содержание

- [Возможности](#features)
  - [Подключения и аутентификация](#connections)
  - [Терминал](#terminal)
  - [SFTP и передачи](#sftp)
  - [Туннели](#forwarding)
  - [Ключи и секреты](#keys)
  - [Организация данных](#organisation)
- [Технологический стек](#technology-stack)
- [Пакет приложения](#package)
- [Быстрый старт](#quick-start)
- [CI, APK и Releases](#ci-and-releases)
- [Карта проекта](#project-map)
- [Архитектура](#architecture)
- [Безопасность](#security)
- [Ограничения](#limitations)
- [Вклад в проект](#contributing)
- [Лицензия](#license)

<a id="features"></a>
## Возможности

<a id="connections"></a>
### Подключения и аутентификация

- SSH-2 через SSHJ, поддержка Ed25519, ECDSA и RSA-ключей.
- Пароль, публичный ключ и `keyboard-interactive` / 2FA.
- ProxyJump-цепочки, keep-alive, таймауты и ограниченное автопереподключение.
- TOFU-проверка host key: неизвестный ключ требует подтверждения, изменившийся — блокирует подключение до явного решения.
- SHA-256 / MD5 fingerprints и randomart для сверки серверного ключа.

<a id="terminal"></a>
### Терминал

- Собственный VT100 / VT220 / xterm-эмулятор: CSI, OSC, DCS, alternate screen, true color и xterm-256.
- Bracketed paste, mouse reporting (SGR), application cursor/keypad и выбор формы курсора.
- Scrollback, поиск, выделение, копирование и распознавание ссылок.
- Несколько вкладок сессий, настройки шрифта, темы и дополнительный ряд клавиш.

<a id="sftp"></a>
### SFTP и передачи

- Просмотр каталогов, загрузка, скачивание, переименование, удаление, создание директорий и `chmod`.
- Рекурсивные загрузки и скачивания, симлинки, скрытые файлы и очередь передач.
- Foreground-service и уведомление с прогрессом — передачи не должны теряться при сворачивании приложения.

<a id="forwarding"></a>
### Туннели

- Локальный (`-L`), удалённый (`-R`) и динамический (`-D`, SOCKS5) форвардинг.
- Встроенный SOCKS5-сервер поверх SSH-канала.
- Сохранённые правила и автозапуск при подключении к хосту.

<a id="keys"></a>
### Ключи и секреты

- Генерация Ed25519, ECDSA P-256/384/521, RSA 2048/3072/4096.
- Импорт OpenSSH, PKCS#8 и PuTTY; экспорт публичного ключа и переносимого защищённого архива.
- Ключи, пароли и passphrase шифруются ключом из Android Keystore.
- Агент ключей и запрос подтверждения использования ключа.

<a id="organisation"></a>
### Организация данных

- Хосты, группы, идентичности, теги, заметки, цвета и избранное.
- Сниппеты с переменными вида `${name:default}` и командами при подключении.
- История соединений, известные хосты, переносимый backup/restore и deep links (`ssh://`, `sftp://`, `telnet://`, `nexusssh://`).

<a id="technology-stack"></a>
## Технологический стек

| Область | Технологии |
| --- | --- |
| Язык и сборка | Kotlin 2.0.21 · Gradle 8.11.1 · AGP 8.7.3 · JVM 17 |
| Android | `minSdk 26` · `targetSdk 35` · AndroidX |
| UI | Jetpack Compose · Material 3 · Navigation · Lifecycle |
| Инъекции зависимостей | Hilt 2.52 |
| Хранение | Room 2.6.1 · DataStore · Kotlin Serialization |
| SSH/SFTP | SSHJ 0.38.0 · Bouncy Castle · EdDSA |
| Фоновые операции | Coroutines · foreground services · WorkManager |
| Качество и поставка | GitHub Actions · APK artifacts · GitHub Releases |

<a id="package"></a>
## Пакет приложения

| Параметр | Значение |
| --- | --- |
| Название | **Nexus SSH** |
| Application ID | `com.nikro.nexusssh` |
| Namespace | `com.nikro.nexusssh` |
| Debug application ID | `com.nikro.nexusssh.debug` |
| Минимальная версия Android | API 26 (Android 8.0) |
| Целевая версия Android | API 35 |
| Текущая версия | `1.0.0` |

<a id="quick-start"></a>
## Быстрый старт

### Требования

- JDK 17;
- Android SDK Platform 35;
- установленный Gradle **или** готовый Gradle wrapper.

### Сборка debug APK

```bash
# Клонировать проект
git clone https://github.com/n1kro-yeah/nexus-ssh-android.git
cd nexus-ssh-android

# Если gradle-wrapper.jar отсутствует, создать wrapper через локальный Gradle
gradle wrapper --gradle-version 8.11.1

# Собрать и установить debug-версию
./gradlew assembleDebug
./gradlew installDebug
```

Готовый файл будет расположен в `app/build/outputs/apk/debug/`.

### Подписанная release-сборка

Создайте keystore и передайте значения через `~/.gradle/gradle.properties`:

```properties
NEXUS_STORE_FILE=/absolute/path/release.jks
NEXUS_STORE_PASSWORD=change-me
NEXUS_KEY_ALIAS=nexus
NEXUS_KEY_PASSWORD=change-me
```

```bash
./gradlew assembleRelease
```

<a id="ci-and-releases"></a>
## CI, APK и Releases

Workflow [Android CI](https://github.com/n1kro-yeah/nexus-ssh-android/actions/workflows/android.yml) запускается на каждом push, pull request и вручную.

| Событие | Что происходит | Где взять APK |
| --- | --- | --- |
| Push / PR | `assembleDebug` | В артефакте **`nexus-ssh-apk`** у запуска Actions |
| Ручной запуск | Debug APK и проверка конфигурации | В разделе [Actions](https://github.com/n1kro-yeah/nexus-ssh-android/actions/workflows/android.yml) |
| Тег `v*` | APK собирается и публикуется через GitHub Release | В разделе [Releases](https://github.com/n1kro-yeah/nexus-ssh-android/releases) |

Для **подписанного** release APK добавьте repository secrets:

| Secret | Назначение |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-представление release keystore |
| `NEXUS_STORE_PASSWORD` | Пароль keystore |
| `NEXUS_KEY_ALIAS` | Alias ключа |
| `NEXUS_KEY_PASSWORD` | Пароль ключа |

Чтобы выпустить версию после успешной зелёной сборки:

```bash
git tag v1.0.0
git push origin v1.0.0
```

<a id="project-map"></a>
## Карта проекта

```text
nexus-ssh-android/
├── .github/workflows/android.yml       # CI, APK artifacts и GitHub Releases
├── app/
│   ├── build.gradle.kts                # Android-конфигурация, зависимости, signing
│   └── src/main/
│       ├── AndroidManifest.xml          # permissions, deep links, services
│       ├── res/                         # EN/RU строки, темы и ресурсы
│       └── java/com/nikro/nexusssh/
│           ├── core/crypto/             # Android Keystore, ключи, импорт/экспорт
│           ├── core/log/                # безопасный журнал приложения
│           ├── data/backup/             # portable backup/restore
│           ├── data/importer/           # OpenSSH config parser
│           ├── data/local/              # Room entities, DAO и database
│           ├── data/prefs/              # DataStore / AppSettings
│           ├── data/repository/         # доступ к хостам, ключам, истории, сниппетам
│           ├── di/                      # Hilt modules
│           ├── domain/model/            # неизменяемые доменные модели
│           ├── service/                 # foreground services и notifications
│           ├── ssh/                     # SSH transport, auth, host keys, SFTP, tunnels
│           ├── terminal/                # emulator, buffer, palette, input, search
│           └── ui/                      # Compose screens, navigation, themes, ViewModel
├── gradle/libs.versions.toml            # version catalog
├── build.gradle.kts                     # корневая сборка
└── settings.gradle.kts                  # модули проекта
```

<a id="architecture"></a>
## Архитектура

```text
Compose UI + ViewModel
        │ StateFlow / Coroutines
        ▼
Repositories ─── SettingsRepository
        │                 │
        ├── Room ─────────┘
        ├── DataStore
        └── SSH domain: SshSessionManager → SshConnection → SSHJ
                                              │
                                              ├── SFTP
                                              ├── port forwarding / SOCKS
                                              └── terminal session → emulator → canvas UI
```

- UI не обращается к Room или SSHJ напрямую.
- ViewModel управляют состоянием, а длительные I/O-операции выполняются через корутины.
- Репозитории изолируют схему базы и источники данных от экранов.
- Terminal emulator и SSH session отделены от Compose, поэтому могут тестироваться независимо.

<a id="security"></a>
## Безопасность

- Учетные данные не должны попадать в лог: `AppLogger` редактирует секретные значения.
- Приватные ключи, пароли и passphrase должны храниться только в зашифрованном виде.
- Device-bound ключи Android Keystore не экспортируются; для переноса применяется отдельный парольный контейнер.
- Проверяйте fingerprint сервера при первом подключении и не принимайте неожиданную замену host key.
- Никогда не публикуйте `*.jks`, `local.properties`, реальные пароли и ключи в Git.

<a id="limitations"></a>
## Ограничения

- Mosh не реализован: ему нужны UDP, роуминг и серверный компонент.
- X11 forwarding требует отдельного X-сервера на Android.
- Локальный shell без PTY требует NDK-обвязки.
- Agent forwarding опирается на внутренний API SSHJ и должен проходить отдельную проверку на каждой версии SSHJ.
- До первого успешного CI-релиза не следует считать приложение готовым к production-использованию.

<a id="contributing"></a>
## Вклад в проект

1. Создайте ветку от `main`.
2. Сохраняйте слои: UI → ViewModel → repository → data/SSH.
3. Не добавляйте ключи, keystore, личные хосты или дампы терминала в репозиторий.
4. Перед pull request запустите `./gradlew assembleDebug` и приложите результат CI.
5. Для изменений UI добавляйте скриншоты, когда появится стабильная сборка.

<a id="license"></a>
## Лицензия

Лицензия самого приложения пока не выбрана. Перед публичным распространением добавьте файл `LICENSE` и явно определите условия использования. Зависимости используются по собственным лицензиям (включая Apache-2.0 для SSHJ, AndroidX/Compose/Room и Hilt).