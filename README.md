# Stereoo LiveKit • Gemini Live (TEST)

Независимая тестовая сборка для Motorola moto g15. Цель: подключиться к уже развёрнутому LiveKit Agent с голосовой realtime-моделью Gemini Live, **без Android TTS**, проверить нормальный разговор на русском и перебивание.

- Пакет: `ru.stereoo.livekitpilot`. Устанавливается отдельно от основного Stereoo AI Glasses.
- SDK: `io.livekit:livekit-android:2.30.0`.
- Агент по умолчанию: `assistant-1c7e` (его имя, не Agent ID).
- Не нужно указывать LiveKit WebSocket URL вручную: `TokenSource.fromDevelopmentTokenServer` выдаёт `serverUrl` и `participantToken` вместе.
- Каждый запуск: новая комната `stereoo-<uuid>`; токен включает explicit agent dispatch через `TokenRequestOptions(agentName=...)`.
- Микрофон: `setMicrophoneEnabled(true)`. Аудио агента SDK воспроизводит автоматически.
- Никаких `LIVEKIT_API_KEY`, `API_SECRET`, Google API Key или платёжных ключей в APK нет.

## Испытание

1. LiveKit Cloud → проект Glasses → Settings → Project settings → Development token server → включить, скопировать **Token server ID**.
2. Открыть test APK, ввести **Token server ID** только локально на телефоне. Не путать с Agent ID.
3. Проверь имя агента в Overview, по умолчанию `assistant-1c7e`.
4. Нажать «Начать разговор», разрешить микрофон. Проверить двухстороннюю речь.
5. Перебить ответ; закончить разговор кнопкой «Завершить».
6. Проверить Usage в LiveKit и затем выключить development token server в консоли.

Тариф LiveKit и Realtime-модели может расходовать бесплатные кредиты или приводить к платным расходам.

**Обязательно до производства**: безопасный authenticated token endpoint на сервере, Foreground Service и Bluetooth-маршрутизация к BV200 с выключенным экраном, нижняя кнопка/фотографии, голосовые команды, долгосрочная память. Текущий тест не претендует на их поддержку.

Docs:
- https://docs.livekit.io/frontends/build/authentication/development-token-server/
- https://docs.livekit.io/frontends/build/authentication/
- https://github.com/livekit/client-sdk-android
