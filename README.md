# Messenger — Android (этап 1)

Нативный Android-клиент (Kotlin + Jetpack Compose) к твоему FastAPI-бэкенду
(`main.py`, тот же, что использует веб-версия). Дизайн — тот же визуальный язык:
цвета `#dddddd/#e6e6e6/#1d1d1c`, чёрные пузыри исходящих сообщений, лаконичные
uppercase-заголовки.

## Что уже работает

- Авторизация по email + одноразовый код (`/api/send-code`, `/api/verify-code`)
- Список чатов (`/api/chats`) — публичные + личные, с поиском по названию
- Личные сообщения (`/api/dm`)
- Создание групповых чатов (`/api/chats`), просмотр/добавление/удаление участников,
  выход из группы (`/api/chats/{id}/participants`)
- Экран переписки: история (`/api/messages/{chat_id}`) + realtime через WebSocket (`/ws/{chat_id}`)
- Прикрепление и отправка файлов/изображений (`/api/upload`), картинки показываются
  прямо в чате (Coil), остальные файлы — как ссылка с иконкой
- Выход из аккаунта, хранение токена сессии между запусками (DataStore)

## Что пока не перенесено (следующий этап)

- Удаление чата целиком (`DELETE /api/chats/{id}`) — на бэкенде уже есть, в UI пока нет
- Просмотр изображений на весь экран по тапу
- Пагинация истории сообщений (`before_id`) при скролле вверх

## Как открыть проект

1. Открой Android Studio → **Open** → выбери папку `AndroidMessenger`.
2. Studio предложит создать Gradle Wrapper (`gradlew`) — согласись, это стандартный шаг.
3. Дождись Gradle sync (первый раз качает зависимости, нужен интернет).

## Как подключить к бэкенду

В `app/build.gradle.kts` есть:

```kotlin
buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8000\"")
```

`10.0.2.2` — это адрес хоста (твоего компьютера) **из Android-эмулятора**.
Так что если запускаешь `uvicorn main:app --reload` локально и открываешь
приложение в эмуляторе — менять ничего не нужно.

Варианты:
- **Эмулятор + локальный сервер** — оставь как есть.
- **Реальный телефон + локальный сервер** — замени на `http://<IP-компьютера-в-локальной-сети>:8000`
  (напр. `http://192.168.1.50:8000`), телефон и компьютер должны быть в одной Wi-Fi сети.
- **Задеплоенный сервер** — впиши его https-адрес, например `https://myserver.up.railway.app`.

После изменения — **Sync Project with Gradle Files**.

## Запуск

Выбери эмулятор или подключи телефон (с включённой отладкой по USB) и нажми ▶ Run.
На первом экране введи email → код придёт **в консоль сервера** (uvicorn), если SMTP
не настроен в `.env` — точно как в веб-версии.

## Про шрифт Michroma

Веб-версия использует декоративный шрифт Michroma для заголовков/кнопок.
Сейчас в приложении вместо него используется системный шрифт с тем же uppercase +
letter-spacing — чтобы ничего не ломалось без файла шрифта. Чтобы добавить настоящий:

1. Скачай `Michroma-Regular.ttf` с https://fonts.google.com/specimen/Michroma
2. Положи в `app/src/main/res/font/michroma.ttf`
3. В `ui/theme/Type.kt` раскомментируй строку с `FontFamily(Font(R.font.michroma))`

## Структура проекта

```
app/src/main/java/com/example/messenger/
  data/
    model/Models.kt        — DTO, повторяют JSON бэкенда 1:1
    api/ApiService.kt      — Retrofit-интерфейс (все REST-эндпоинты)
    api/RetrofitClient.kt  — HTTP-клиент
    api/WebSocketClient.kt — обёртка над OkHttp WebSocket
    local/SessionManager.kt— хранение токена/email (DataStore)
  ui/
    theme/                 — цвета и типографика 1:1 с index.html
    auth/                  — экран входа (email → код)
    chatlist/               — список чатов
    chat/                  — переписка + WebSocket
    navigation/NavGraph.kt — Auth → ChatList → Chat
  MainActivity.kt
  MessengerApp.kt          — Application, простой ручной DI
```
