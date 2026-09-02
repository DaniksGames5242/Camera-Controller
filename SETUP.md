# Настройка

## 1. Firebase-проект (один раз)

1. https://console.firebase.google.com → Add project → любое имя.
2. Build → Realtime Database → Create database → любой регион → **Start in locked mode** (правила зальём свои).
3. Build → Authentication → Sign-in method → включить **Anonymous**.
4. Project settings (шестерёнка) → General → Your apps → Add app → Web (`</>`) → зарегистрировать, скопировать `firebaseConfig`.
5. Realtime Database → Rules → вставить содержимое [firebase/database.rules.json](firebase/database.rules.json) → Publish.

## 2. Локальный конфиг (общий для всех твоих сборок)

```
cp packages/shared/src/firebase.config.example.json packages/shared/src/firebase.config.json
```

Заполнить значениями из шага 1.4, плюс `roomId` — любая длинная случайная строка
(генерировать: `node -e "console.log(require('crypto').randomUUID())"`). Это не пароль
в строгом смысле, но действует как секретный неугадываемый префикс — не публикуй его
и не выкладывай `firebase.config.json` в публичный репозиторий (файл уже в `.gitignore`).

Один и тот же `firebase.config.json` используется при сборке **и агентов, и клиента** — так
все твои устройства окажутся в одной "комнате".

## 3. Установка зависимостей и сборка

```
npm install
npm run typecheck

# агент (запускать/собирать на Windows или Linux машине, которая раздаёт камеру)
npm run dev:agent          # запуск без сборки инсталлятора, для проверки
npm run build:agent && npm run dist:win -w packages/agent      # portable .exe
npm run build:agent && npm run dist:linux -w packages/agent    # .AppImage

# клиент (просмотр, Windows)
npm run dev:client
npm run build:client && npm run dist:win -w packages/client    # обычный инсталлятор
```

## 4. Установка на устройство без прав администратора

- **Windows-агент**: скачать `*-portable.exe`, запустить. Автозапуск и Windows Defender
  SmartScreen — при первом запуске возможно предупреждение "Windows защитила ваш
  компьютер" → "Подробнее" → "Выполнить в любом случае" (это не требует прав админа,
  просто подтверждение, т.к. exe не подписан сертификатом).
- **Linux-агент**: скачать `*.AppImage`, сделать исполняемым (`chmod +x file.AppImage`
  либо через файловый менеджер: свойства → "Разрешить выполнение как программы"),
  запустить.
- Оба агента при первом запуске сами прописывают себя в автозапуск пользователя
  (без sudo/root).

## 5. Android-агент

1. В том же Firebase-проекте: Project settings → Your apps → Add app → Android,
   applicationId `com.mycamerascontroller.agent` → скачать `google-services.json` →
   положить в `packages/android-agent/app/google-services.json`.
2. `cp packages/android-agent/app/src/main/java/com/mycamerascontroller/agent/RoomConfig.kt.example` →
   переименовать в `RoomConfig.kt` в том же каталоге, вписать тот же `roomId`,
   что и в `firebase.config.json`.
3. Открыть `packages/android-agent` в Android Studio (она сама сгенерирует
   `gradlew`/Gradle wrapper при первом открытии) → Build → собрать debug APK,
   либо через CI (см. ниже).
4. Скачать `.apk` на телефон через браузер → установить (Android спросит
   разрешение "установка из неизвестных источников" — единственное место,
   где по нашей же договорённости разрешения запрашиваются) → при первом
   запуске приложение запросит камеру/микрофон и предложит исключить себя
   из оптимизации батареи (нужно подтвердить вручную — без этого Android
   может убивать фоновый сервис).

## 6. Android-клиент (просмотр)

То же самое, но приложение "смотрящее", а не раздающее:

1. Add app → Android, applicationId `com.mycamerascontroller.client` →
   `google-services.json` → `packages/android-client/app/google-services.json`.
2. `RoomConfig.kt.example` → `RoomConfig.kt` в
   `packages/android-client/app/src/main/java/com/mycamerascontroller/client/`,
   тот же `roomId`.
3. Открыть `packages/android-client` в Android Studio, собрать debug APK
   (или через CI). Разрешений камера/микрофон это приложение не запрашивает —
   оно только принимает видео и звук, ничего своего не снимает.

## 7. Автосборка релизов (GitHub Actions)

Сборка инсталляторов electron-builder тянет собственные бинарники по сети — надёжнее
всего это работает на чистом CI-раннере, а не локально. Настроено в
[.github/workflows/release.yml](.github/workflows/release.yml):

1. В репозитории на GitHub: Settings → Secrets and variables → Actions → New repository
   secret, добавить четыре:
   - `FIREBASE_CONFIG_JSON` — содержимое `packages/shared/src/firebase.config.json` целиком;
   - `ANDROID_AGENT_GOOGLE_SERVICES_JSON` — содержимое `packages/android-agent/app/google-services.json`;
   - `ANDROID_CLIENT_GOOGLE_SERVICES_JSON` — содержимое `packages/android-client/app/google-services.json`;
   - `ROOM_ID` — тот же `roomId`, что и в `firebase.config.json`.

   Это единственное место, где секреты живут вне твоей машины, и они доступны только
   твоим workflow-раннерам.
2. Запуск сборки: `git tag v0.1.0 && git push origin v0.1.0` — соберёт
   Windows-portable и AppImage агента, Android APK агента, Windows-инсталлятор
   клиента, Android APK клиента, и опубликует их
   как файлы в GitHub Release этого тега (ссылки для скачивания в браузере).
   Без тега (`workflow_dispatch` вручную из вкладки Actions) — то же самое, но
   только артефакты сборки, без публикации релиза.
