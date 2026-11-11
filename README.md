# Stereoo LiveKit v2 • BV200 background voice pilot

**Тест для Motorola moto g15 (Android 15), использует уже работающий в LiveKit Cloud голосовой агент Gemini Live.**

Подтверждено пользователем для v1: WebRTC подключение и русский разговор работают.
Добавлено в v2: фоновая служба микрофона, системное уведомление с остановкой сессии, кнопка выбора Bluetooth-гарнитуры через LiveKit AudioSwitch, отображение финальных расшифровок вместо сотен отладочных событий, 10-минутный лимит тестовой сессии.

## Использование

1. Подключи BV200 к Motorola по Bluetooth. В профиле устройства включи «Звонки» (HFP). Разреши приложению «Микрофон» и «Устройства поблизости» (Bluetooth).
2. В LiveKit Cloud включи **Development token server** (только на период испытаний). Скопируй **Token Server ID**, не API Key или Secret.
3. Установи v2 поверх нашего первого LiveKit APK: пакет прежний `ru.stereoo.livekitpilot`, versionCode=2. Основное Stereoo AI Glasses не меняем.
4. Открой, введи Token Server ID, убедись что Agent Name `assistant-1c7e`, нажми «Начать разговор».
5. При необходимости нажми «Вывести голос в BV200 / Bluetooth». Когда Bluetooth профиль «Звонки» не активен, приложение покажет ошибку маршрута.
6. Поговори, заблокируй экран, проверь звук и микрофон через очки. **Должно оставаться уведомление «Stereoo AI • голосовая связь»**. Можешь остановить разговор прямо из уведомления, не разблокируя телефон.
7. После 10 минут приложение само отключит сессию, чтобы не расходовать кредиты бесконтрольно. Проверяй LiveKit Usage. По окончании испытаний выключи Development token server.

Важно: **фактическая работа в фоне и BT микрофона ещё требует испытания на Motorola BV200**. У LiveKit есть встроенное управление выбором аудиоустройства и BT при наличии HFP, однако некоторые Android прошивки меняют маршрут.

Android 14/15: `android:foregroundServiceType="microphone"` и `FOREGROUND_SERVICE_MICROPHONE`. Запуск только из видимой Activity после выдачи RECORD_AUDIO; из фона или BOOT_COMPLETED начать микрофонную FGS нельзя.

Пока нет: обработчика кнопок BV200, фото/зрительных запросов, голосовых стоп-команд и долговременной памяти. Для релиза также необходим сервер с авторизацией и защищённой выдачей токенов, вместо Development Token Server.

Исходники: `app/src/main/java/ru/stereoo/aiglasses/VoiceSessionService.kt` и `MainActivity.kt`.

Документация:
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://docs.livekit.io/reference/client-sdk-android/livekit-android-sdk/io.livekit.android.audio/-audio-switch-handler/index.html
- https://docs.livekit.io/frontends/build/authentication/development-token-server/
