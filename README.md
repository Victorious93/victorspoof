<div align="center">

<img src="assets/logo.png" width="200" alt="VictorSpoof logo"/>

# VictorSpoof

**A legal novelty caller-ID app with strict compliance guardrails.**

<p>
  <a href="https://github.com/Victorious93/victorspoof/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/Victorious93/victorspoof/ci.yml?branch=main&style=flat-square&labelColor=0B0F1A&logo=githubactions&logoColor=00E5FF&label=CI" alt="CI"/></a>
  <a href="https://github.com/Victorious93/victorspoof/releases"><img src="https://img.shields.io/github/v/release/Victorious93/victorspoof?include_prereleases&style=flat-square&labelColor=0B0F1A&color=00E5FF&label=release" alt="Release"/></a>
  <img src="https://img.shields.io/badge/platform-Android-00E5FF?style=flat-square&labelColor=0B0F1A" alt="Platform"/>
  <a href="https://victorious-framework.pages.dev"><img src="https://img.shields.io/badge/Victorious-Framework-FF2BD6?style=flat-square&labelColor=0B0F1A" alt="Victorious Framework"/></a>
</p>

<p><a href="https://github.com/Victorious93/victorspoof/releases">Releases</a> · <a href="https://victorious-framework.pages.dev">Victorious Framework</a></p>

</div>

---

A legal **novelty / prank caller-ID** Android app for harmless pranks between friends, with strict compliance guardrails. **Entertainment only.** Fleet class: `EXTERNAL_NODE`. Package id: `com.victorious.spoof`, version 0.2.0.

> **Status: early development (v0.2).** CI builds and tests the app and backend. Real calling needs your own Twilio account and a deployed backend; nothing here is claimed as tested against live carriers or on a device.

## Install

Download the APK from [Releases](https://github.com/Victorious93/victorspoof/releases) (latest: `v0.1.1-debug`, a debug pre-release older than the 0.2.0 source). CI also uploads each build as the `victorspoof-debug-apk` artifact. minSdk 26. Calling requires the backend (`backend/`) with the secrets listed below.

## Permissions

`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `READ_CONTACTS`, `MANAGE_OWN_CALLS`, `CALL_PHONE`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `PROCESS_OUTGOING_CALLS`, `SEND_SMS`, `READ_SMS`, `READ_PHONE_NUMBERS`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` (microphone), `INTERNET` and `ACCESS_NETWORK_STATE`. No root required.

- Full how-to manual: [docs/MANUAL.md](docs/MANUAL.md) (also in-app under **Help**)


## Legal scope

The Truth in Caller ID Act of 2009 (47 U.S.C. § 227(e), FCC rules at 47 CFR § 64.1604) makes it unlawful to transmit misleading or inaccurate caller ID **with intent to defraud, cause harm, or wrongfully obtain anything of value**. VictorSpoof is designed so it can't be used that way:

- **No arbitrary spoofing and no free-text caller ID.** The client only picks a *mode*. The backend chooses either a VictorSpoof-owned pool number (fun CNAM where supported) or the user's own verified number.
- **Blocked targets:** 911/112/999 and other emergency numbers, 988, all N11 codes, short codes, and seeded government, police, hospital and major-bank numbers (extendable through the `blocked_numbers` table).
- **Display-name filter:** rejects police/sheriff/IRS/FBI/ICE/bank/hospital/etc. names, including leetspeak variants.
- **Rate limits:** 10 calls/day, 3/day per target, 120 s max per call. No auto-dial and no SMS.
- **Compliance flow:** a scrollable first-launch disclaimer, an 18+ gate, and AUP acceptance logged server-side before any call.
- **Recording** is opt-in, with an all-party announcement.
- Every call ends with a "You've been VictorSpoofed!" reveal.

This is not legal advice. Operators must also follow STIR/SHAKEN attestation rules for owned numbers, carrier CNAM policies, state recording-consent laws, and the laws of every country they serve.

## Layout

| Path | What |
|---|---|
| `app/` | Kotlin + Jetpack Compose, Hilt, Room. Twilio Voice Android SDK (Telnyx is the planned backup). Fleet bridge in `bridge/`. |
| `backend/` | Kotlin/Ktor API + Postgres (`db/schema.sql`: accounts, number_pool, aup_log, rate_limits, blocked_numbers). Guardrails in `guardrails/`. |
| `keystore/victorspoof-debug.jks` | **Debug-only** keystore (see `keystore/README.md`). |
| `docs/` | `MANUAL.md`, `logo.png` |
| `tools/make_icons.py` | Derives `docs/logo.png` and the launcher icons from the fleet logo |

## Features (v0.2)

- **Real Twilio calling.** The backend mints 10-minute Voice access tokens (`POST /v1/twilio/token`, max 10 per hour per account). `POST /v1/calls/authorize` runs every guardrail and returns a single-use **call ticket**. The TwiML webhook (`POST /v1/twilio/voice`, Twilio-signature validated) consumes the ticket and sets `callerId` + `timeLimit=120` **server-side**. The app sends only the ticket. The app uses the Twilio Voice Android SDK with a self-managed `ConnectionService`, an ongoing-call notification, and a 120 s client cap.
- **Own-number verification:** Twilio Verify OTP (`/v1/verify/start`, `/v1/verify/check`) with send and attempt limits. Only a verified number shows up as the `OWN_VERIFIED` caller-ID option.
- **Voice changer:** real-time DSP (Chipmunk, Deep, Robot, Echo, Alien) on the outgoing audio through a custom Twilio `AudioDevice`. The preset is kept in DataStore.
- **Live soundboard:** clips are synthesized on-device (no third-party audio) and mixed into the outgoing audio, including the "You've been VictorSpoofed!" sting (fanfare plus on-device TTS line) that plays at every hang-up and before the 2-minute cap.
- **Auth:** email + password (bcrypt, cost 12), 15-minute HS256 access JWT, and a rotating single-use 30-day refresh token (stored hashed on the server, Keystore-encrypted on the phone). The `X-Account-Id` placeholder is gone. AUP acceptance is still logged server-side (`aup_log`).
- **Help** renders `docs/MANUAL.md` with CommonMark + GFM tables. **Backup export** is AES-256-GCM, keyed by a PBKDF2 passphrase or an Android Keystore key. Import decrypts it, then runs the existing allowlist/refusal-scan dry-run and confirm flow.

## Secrets (never committed)

| Secret | Used by |
|---|---|
| `TWILIO_ACCOUNT_SID`, `TWILIO_API_KEY`, `TWILIO_API_SECRET`, `TWILIO_TWIML_APP_SID` | Voice tokens + Verify REST auth. Without them `/v1/twilio/token` returns 503 `twilio_not_configured` with the list of missing names. (The old `TWILIO_API_KEY_SID`/`TWILIO_API_KEY_SECRET` names are still accepted.) |
| `TWILIO_VERIFY_SID` | Own-number OTP. Without it `/v1/verify/*` returns 503 |
| `TWILIO_AUTH_TOKEN`, `PUBLIC_BASE_URL` | Validating `X-Twilio-Signature` on the voice webhook. Without them the webhook speaks "not configured" and hangs up |
| `VS_JWT_SECRET` (≥ 32 chars) | Access-token signing. Without it a random per-process key is used |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | backend (falls back to an in-memory store for dev) |
| `VS_RELEASE_KEYSTORE_B64`, `VS_RELEASE_STORE_PASSWORD`, `VS_RELEASE_KEY_ALIAS`, `VS_RELEASE_KEY_PASSWORD` | CI release signing only |
| `VS_BACKEND_URL` | build-time backend base URL baked into the APK (not a secret) |

The app receives only short-lived Twilio access tokens and never sees the real pool number (the authorize response masks it). Twilio credentials and the number pool are never placed in the app, in config export, or in shared fleet config. See the manual, section 15, for the TwiML App setup.

### API

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /v1/auth/signup` / `login` / `refresh` | none | signup requires `ageAttested18` + `disclaimerAccepted`. Login is limited to 5 failures per email per 15 minutes |
| `POST /v1/auth/logout`, `GET /v1/me` | Bearer | logout revokes all refresh tokens |
| `POST /v1/aup/accept` | Bearer | logged in `aup_log` with IP + user agent |
| `POST /v1/verify/start`, `/v1/verify/check` | Bearer | 3 sends per hour / 5 per day per account, 3 per day per number, 5 checks per hour |
| `POST /v1/calls/authorize` | Bearer | mode only (`POOL` / `OWN_VERIFIED`), unknown fields rejected, returns `ticket` |
| `POST /v1/twilio/token` | Bearer | 10 per hour per account |
| `POST /v1/twilio/voice`, `/v1/twilio/whisper` | Twilio signature | TwiML. The whisper plays the recording announcement to the called party |

## Build

```bash
./gradlew :backend:test                                       # guardrails, auth, OTP, webhook, token limits
VS_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/vstest VS_TEST_DATABASE_USER=vs \
  ./gradlew :backend:test --tests '*PostgresStoreTest*'       # optional: real-Postgres store test
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
CI (`.github/workflows/ci.yml`) runs the same steps and uploads `victorspoof-debug-apk`.

## Fleet bridge

Bound Messenger service `com.victorious.spoof.action.BRIDGE` (protocol 1.1, frozen envelope v1). Callers are authenticated by package + signing cert from `Message.sendingUid`. External control is off by default. No remote op can place a call. See the manual, sections 10–11.

| Op | Kind | remoteAllowed | requiresConfirm |
|---|---|---|---|
| node.status.get | read | true | false |
| spoof.status.get | read | true | false |
| spoof.pool.list | read | true | false |
| spoof.usage.get | read | true | false |
| spoof.ui.open | action | true | false |
| spoof.call.start | action | false | true |
| spoof.call.end | action | false | true |
| spoof.recording.delete | destructive | false | true |
| config.export | action | false | false |
| config.import | action | false | true |

The receiver classifies every op itself, so envelope hints (`destructive`, `trigger.*`) never change these flags. `config.export` exports only allowlisted UI prefs and runs a refusal scan for Twilio credentials, `authToken`, `numberPool`, phone numbers, account ids and tokens. `config.import` supports `dryRun` (diff only, no writes), needs on-device confirmation, and always forces external control OFF.

---

## Victorious fleet

VictorSpoof is part of the [Victorious Framework](https://victorious-framework.pages.dev), a family of Android and PC tools by Don Victor. **Every app works standalone; fleet integrations are optional** and never required.

Related: [victorsms](https://github.com/Victorious93/victorsms) (SMS app), [droidcommand-AI](https://github.com/Victorious93/droidcommand-AI) (fleet controller), [victorious-fleet](https://github.com/Victorious93/victorious-fleet) (index of all fleet apps). The full roster lives in [victorious-fleet](https://github.com/Victorious93/victorious-fleet), and live status is in [fleet-manifest](https://github.com/Victorious93/fleet-manifest).

## Contributing

Issues and pull requests are welcome. Keep changes focused, match the existing code style, never commit secrets or release keys, and keep any fleet integration optional so the app still works on its own. Open a PR against the default branch and wait for CI to pass.

## License

This repository does not include a LICENSE file yet, so no license is granted beyond what the author states elsewhere. Please open an issue before reusing the code.
