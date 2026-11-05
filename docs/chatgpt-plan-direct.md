# ChatGPT plan direct connection (draft)

Select **ChatGPT 订阅直连** in Model Service, open account management, and use **Continue with ChatGPT** in the system browser. Return to AgentPaw after authorization, fetch the account's model catalog, choose an available model, and save the settings. No API key or desktop bridge is required. The phone must be able to reach OpenAI authentication and API endpoints.

This implementation uses the documented open-source/local-app flow:
https://developers.openai.com/siwc/token-sharing-open-source

## Implementation

- Dynamic OAuth client registration with PKCE S256, state and nonce. The callback server binds only to a random port on 127.0.0.1.
- ID-token verification using OpenAI JWKS: RS256 signature, issuer, audience, expiry, nonce and returning-account subject.
- Credentials are encrypted with an Android Keystore AES-GCM key and stored in noBackupFilesDir. Refresh tokens are rotated with access tokens. Local sign-out clears credentials; server-side authorization revocation is managed in ChatGPT.
- Direct, streaming Responses API transport with store=false. It maps conversation text, screenshot images, phone tool calls, tool results and usage into the existing LlmClient interface.
- Existing OpenAI-compatible providers continue through the original client and network policy.

## Validation and review status

This is a draft contribution based on a locally built test variant. That variant used a separate application ID and AGP 9.1.1 / Gradle 9.3.1 for the available SDK 37.0. Those packaging and build-tool changes are deliberately excluded from this PR; the exact upstream configuration needs CI validation.

- Test variant: APK build and signature verification passed; 73 app unit tests passed, including the new ResponsesCodecTest cases; lint completed with 0 errors and 97 warnings.
- User-confirmed on-device test: browser authorization and a text response over mobile data with Wi-Fi disabled. Direct-mode screenshot recognition and refresh-token renewal have not yet been confirmed on-device.
- Extended core tests on Windows: 282/283 passed. The existing InterpreterTest `file commands cannot follow symlinks outside sandbox` failed, and the same failure reproduced with the core feature change removed. This PR does not change shell isolation or disable the test.
- Before promoting from draft: exercise token refresh, cancellation, interrupted/failed SSE streams, and Android process death while the browser is open. The callback listener currently lives in the app process; force-stopping the app during login requires a fresh attempt.
- UI strings currently target the Chinese test workflow and need localization/style review. The default model should be reviewed against project conventions; the account catalog remains the source for selectable models.

No account credentials, access/refresh tokens, local SDK paths, signing keys, or desktop bridge keys are included.
