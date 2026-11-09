# ChatGPT plan direct connection (preview)

Select **ChatGPT 订阅直连** in Model Service, open account management, and use **Continue with ChatGPT** in the system browser. Return to AgentPaw after authorization, fetch the account's model catalog, choose an available model, and save the settings. No API key or desktop bridge is required. The phone must reach OpenAI authentication and API endpoints.

The implementation follows [OpenAI's open-source/local-app flow](https://developers.openai.com/siwc/token-sharing-open-source). This preview manages one saved account registration; signing in again reuses that registration. It does not offer an account picker.

## Protocol and session handling

- Dynamic registration uses PKCE S256, state and nonce. The listener binds to a random port on 127.0.0.1 before opening the system browser.
- ID-token verification checks the JWKS RS256 signature, issuer, audience, expiry, nonce and returning-account subject.
- A ViewModel owns login across configuration changes. Returning from account management cancels login. New attempts and sign-out invalidate previous generations, close their sockets, cancel their HTTP calls and coroutine owners, and reject stale writes.
- Access, refresh and retained ID tokens stay in an Android Keystore AES-GCM encrypted AtomicFile in noBackupFilesDir. The original encrypted-file format is retained.
- Refreshes are serialized. Only validated token fields are updated; refresh responses cannot replace the saved account/client/host mapping.
- Sign-out stops account requests, discovers and validates the same-origin revocation endpoint, and attempts to revoke the renewable session. Transient failures receive one bounded retry. Local tokens are cleared even when cancellation or network failure prevents confirmation; the UI then directs users to disconnect the app in ChatGPT Settings.
- Sign-out retains only the host ID and verified account/client metadata. Tokens and ID-token hints are removed; returning authorization reuses the saved client ID and host.
- Responses requests use store=false and stream=true. System instructions become developer messages. Phone tools are grouped under the agentpaw namespace, including namespace metadata when replaying tool calls; call_id is preserved for tool results. Foreign namespaces are rejected.
- Completion, model-list and refresh HTTP operations are cancellable while waiting for headers and while reading the response body. Request timeouts follow configuration. Automatic retries and redirects are disabled for authenticated model requests.
- Settings explain unsupported sampling/output-token parameters. The account page uses the existing Material/Miuix themes, system insets, scrolling, large-text layouts and English/Chinese resources.
- Existing OpenAI-compatible providers continue through the original client and network policy.

See [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations), [namespaces](https://developers.openai.com/api/docs/guides/function-calling#defining-namespaces), and [accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions).

## Review fixes and automated verification — 2026-11-09

| Finding | Resolution |
| --- | --- |
| P1: system roles rejected by the plan route | Convert to developer messages; assert the actual HTTP request body. |
| P1: unsupported flat tool definitions | Namespace definitions and replayed calls; preserve local names/call IDs; reject foreign namespaces. |
| P1: late login overwrites account or restores signed-out credentials | One attempt owner, generation guards, socket/call cancellation and ViewModel lifecycle. |
| P2: fixed timeouts and uncancellable model listing/refresh | Configured timeouts and cancellation that owns the entire response lifetime. |
| P2: sign-out loses client registration | Preserve verified registration metadata; revoke renewable sessions and report unconfirmed revocation. |

Verification uses this PR independently of maintenance PR #71, with the repository's existing build configuration (JDK 21, AGP 8.13.2, Kotlin 2.2.21). No application ID or build-tool upgrade is included.

- 425 unit tests: 423 passed, 2 existing conditional skips. App: 132; Core: 293.
- The ChatGPT package contains 22 passing tests, including 19 added regressions: controlled loopback HTTP protocol/OAuth tests and Material/Miuix UI checks.
- Coverage includes out-of-order callbacks, sign-out during login, nonce rejection, listener cancellation, refresh serialization and rotation, model-list body cancellation, refresh cancellation, revocation success/retry/cancellation, incomplete streams, namespace validation, configured timeouts, duplicate-login prevention and 200% font scrolling.
- App/Core Lint: zero errors, 74/24 warnings. No baseline or disabled checks were added.
- Debug, Release and device-test APK builds pass. `git diff --check` passes.
- Native Robolectric screenshots were inspected for both themes in light/dark mode; see [account-page montage](PR70_ACCOUNT_UI_2026-11-09.png).

```powershell
.\gradlew.bat testDebugUnitTest :app:lintDebug :agentpaw-core:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --no-build-cache --no-configuration-cache --console=plain --max-workers=2
```

## Verification boundaries

The contributor previously reported successful browser authorization and a text response over mobile data using a separate test variant. That report does not establish end-to-end validation of this revision.

Current regression tests use controlled local endpoints and synthetic tokens signed by an ephemeral test key. They never use an actual subscription, credentials or API key. Real-account authorization, screenshot recognition, refresh rotation and remote revocation still require device testing. Local screenshots use native software rendering, not a phone's hardware renderer.

The callback listener lives in the app process. Force-stopping the app during authorization requires a fresh attempt; no OAuth authorization URL or pending code is persisted. A local storage write failure is reported rather than silently claiming sign-out succeeded.

No account credentials, production tokens, local SDK paths or signing keys are included.
