<p align="center"><img src="docs/logo.png" alt="VictorSpoof logo" width="220"></p>

# VictorSpoof

A legal **novelty / prank caller-ID** Android app for harmless pranks between friends. **Entertainment only.**
Fleet class: `EXTERNAL_NODE`. Package: `com.victorious.spoof`.

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

## Secrets (never committed)

| Secret | Used by |
|---|---|
| `TWILIO_ACCOUNT_SID`, `TWILIO_API_KEY_SID`, `TWILIO_API_KEY_SECRET`, `TWILIO_TWIML_APP_SID` | backend env only. `/v1/twilio/token` returns 503 without them. |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | backend (falls back to an in-memory store for dev) |
| `VS_RELEASE_KEYSTORE_B64`, `VS_RELEASE_STORE_PASSWORD`, `VS_RELEASE_KEY_ALIAS`, `VS_RELEASE_KEY_PASSWORD` | CI release signing only |

The app receives only short-lived Twilio access tokens. Twilio credentials and the number pool are never placed in the app or in shared fleet config.

## Build

```bash
./gradlew :backend:test                                       # backend guardrail + API tests
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
