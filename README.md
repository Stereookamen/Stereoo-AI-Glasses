# Stereoo Qwen Omni Realtime | тестовый APK для Android

Тест **настоящего потокового speech-to-speech** на основе китайской модели
`qwen3.8-omni-flash-realtime` в Alibaba Cloud Model Studio (Singapore).
Это **не** Android TTS/Vosk и не имитация Realtime.

## Возможности теста
- Непрерывный микрофон: PCM 16 kHz через WebSocket `input_audio_buffer.append`.
- Автоматическое окончание реплики и перебивание через `semantic_vad_ec`.
- Естественная речь Qwen: поток `response.audio.delta`, PCM 24 kHz.
- Фотография JPEG в **ту же сессию** через `input_image_buffer.append`.
- Текстовая история/транскрипции при наличии соответствующих событий.

## Подготовка, важное о деньгах
1. Создать собственный аккаунт Alibaba Cloud и активировать Model Studio
   в регионе **Singapore** (для новых пользователей может быть пробная квота).
2. Проверить, что на странице [Free Quota](https://www.alibabacloud.com/help/en/model-studio/new-free-quota)
   для `qwen3.8-omni-flash-realtime` действительно выдан лимит.
3. Включить **Free Quota Only** до вызова API, иначе после окончания пробного лимита
   система **автоматически списывает деньги**.
4. Создать региональный API key; узнать **Workspace ID** по кнопке в правом
   верхнем углу [Model Studio](https://modelstudio.console.alibabacloud.com/).
5. Установить debug APK, ввести данные **локально на телефоне**. Не присылать
   API key в чат и не добавлять его в GitHub.

Официальные документы:
- [Qwen Omni Realtime](https://www.alibabacloud.com/help/en/model-studio/realtime)
- [Как получить ключ](https://www.alibabacloud.com/help/en/model-studio/get-api-key)
- [Как получить Workspace ID](https://www.alibabacloud.com/help/en/model-studio/obtain-the-app-id-and-workspace-id)
- [Цены](https://www.alibabacloud.com/help/en/model-studio/model-pricing)

## Тест на Motorola
1. Открыть приложение, ввести Workspace ID и API key *из Singapore*.
2. Разрешить микрофон и нажать «Начать настоящий Realtime».
3. Говорить по-русски; попробовать перебить Qwen.
4. Пока сессия активна, нажать «Фото в текущий разговор» и выбрать снимок.
   Затем вслух сказать: «Что это на фотографии?» (VAD привяжет снимок к реплике).

## Ограничения
- Не подключены кнопки и камера BV200, HeyCyan/CyanBridge.
- Нет стабильной работы на заблокированном экране. Activity надо держать открытой.
- Bluetooth HFP/режим маршрутизации звука пока не проверен на Motorola.
- Ключ API не сохраняется, но в тесте передаётся напрямую с устройства. Для
  распространения нужен серверный прокси/временный ключ.
- Доступность Alibaba Cloud для пользователя, техническая скорость и качество
  Realtime на очках **не проверены**. Успешная сборка APK этого не гарантирует.
- 1 млн токенов (если выданы) является пробным, не ежемесячным бесплатным лимитом,
  срок действия до 90 дней от активации/релиза/подтверждения (зависит от правил).
- Реплики в микрофон могут передаваться провайдеру. Не фотографировать закрытые
  производственные документы или персональные данные без разрешения.
