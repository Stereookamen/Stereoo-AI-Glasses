# Stereoo AI Glasses — test Sign in with ChatGPT (branch only)

This experimental branch adds **Continue with ChatGPT** to the existing Android prototype.
It connects directly from the Android device to OpenAI using the documented
open-source OAuth dynamic-registration flow and the user's ChatGPT plan permissions.
No paid API key and no Stereoo server is needed.

**IMPORTANT**
- This is a test project based on the early Android prototype, **not** the deployed
  Stereoo AI v21.1 (73) app and not a drop-in production upgrade.
- Installing this branch does not replace the existing application: the test
  Android application ID is `ru.stereoo.aiglasses.siwcdemo`.
- UI supports manual text, gallery image and Android STT/TTS; integration with
  BV200 Bluetooth buttons and glasses camera is not part of this prototype.
- The app never logs tokens; access and refresh tokens are kept in **memory only**.
  Relaunching the app requires signing in again. Client ID and opaque host ID
  are persisted (not secrets). Signing out removes client registration locally.
- OAuth uses the documented system browser + `127.0.0.1` loopback redirect,
  PKCE S256, state, nonce, RS256 ID-token signature verified via official JWKS.
- Access granted is validated for `chatgpt.tokens.use.direct` and
  `resource.invoke`; requests use `store: false`, `stream: true`.
- Eligible image analysis uses image data URLs. The app rejects files >3 MB.
- The app lists eligible models from the authorized account, so no model slug
  is hard-coded. Session text/image history is resubmitted for follow-up questions.
- This flow is only documented for open-source local applications; the demo code
  alone does not guarantee eligibility of any particular account, device or location.
- This test still requires that the device can connect directly to OpenAI endpoints.
  Sign in with ChatGPT does **not** remove network or geographic restrictions.
- For security, never paste credentials, JWTs, auth codes or callback links into
  GitHub or chat when reporting a login problem.

## Test from GitHub
Open **Actions → Build Android APK → latest run for experiment/siwc-android-2026-11**.
After a successful build, download the `Stereoo-AI-Glasses-debug` artifact
and install its APK. Then tap **Тест: войти через ChatGPT**, authorize in the
system browser, return to the app, and test model listing, text and a small image.

## Reference
https://developers.openai.com/siwc/token-sharing-open-source/sign-in
https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
