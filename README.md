# Stereoo Yandex Realtime | проверочная сборка

Отдельная Android 15 тестовая программа для Motorola moto g15 с **Yandex AI Studio Speech Realtime Max TTS Live**. Сборка устанавливается рядом с прежними APK; пакет `ru.stereoo.aiglasses.yandexrealtimepilot`.

## Что реализовано

- Непрерывный двусторонний Realtime-диалог по WebSocket.
- Входящее аудио: Android AudioRecord, PCM mono 16-bit 44 100 Hz.
- Выходящее аудио: поток `response.output_audio.delta`, AudioTrack PCM 44 100 Hz.
- Автоматическое определение конца фразы, перебивание по `input_audio_buffer.speech_started`.
- Выбор совместимого LiveTTS голоса (`denis`, `sofia`, `vera`).
- Вывод событий сессии и диагностика ошибок подключения.
- **Нет Android TTS:** вся речь генерируется Yandex LiveTTS в Realtime API.

## Как запустить

1. Создать каталог Yandex Cloud, активировать биллинг, проверить стоимость.
2. Создать сервисный аккаунт с ролью `ai.models.user` для нужного каталога.
3. Создать API-ключ с областью действия `yc.ai.foundationModels.execute`.
4. Установить APK, локально ввести **Folder ID** и **API key**. Ключ не записывается на диск, в репозиторий и логи. Скрывать его и Folder ID на скриншотах.
5. Подтвердить, что понимаете оплату по фактическому использованию.
6. Выбрать голос и начать короткий разговор, попробовать перебить ответ.

**API endpoint:** `wss://ai.api.cloud.yandex.net/v1/realtime/openai?model=gpt://<folder-id>/speech-realtime-max-tts-live/latest`
**Authorization:** `Api-Key <secret>`.

## Чего пока нет
- Работа с заблокированным экраном / Foreground Service.
- Bluetooth-маршрутизация и кнопки BV200.
- Прямое анализирование фото в голосовом API: планируется отдельная vision-модель через tools / функцию по голосовому запросу.
- Долговременная память между сессиями (обязательное требование для полной версии).
- Расходы по факту, лимиты и безопасное хранение постоянного ключа в Android Keystore.

## Важные ограничения

- **Сборка не означает, что API уже протестирован на устройстве**. Потребуется реальный ключ пользователя и активный биллинг.
- Pay-as-you-go: расходы состоят из времени входящего/исходящего звука, токенов модели и функций. Подписка в чате Яндекса не покрывает автоматически API.
- Во избежание акустической обратной связи тестировать лучше через наушники, не встроенный динамик.
- Если пользователь меняет входные данные API-key, он должен сперва остановить активный разговор.

Документация:
- https://aistudio.yandex.ru/ru/docs/ai-studio/operations/agents/create-voice-agent
- https://aistudio.yandex.ru/ru/docs/ai-studio/concepts/agents/realtime
- https://aistudio.yandex.ru/ru/docs/ai-studio/pricing
