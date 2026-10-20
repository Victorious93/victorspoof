# VictorSpoof Manual

VictorSpoof is a novelty prank-call app for friends. It is for entertainment only. This manual explains every feature in plain language.

## 1. What VictorSpoof is (and is not)

- **It is:** a fun way to call a friend with a goofy caller name, a silly voice, sound effects, and a "You've been VictorSpoofed!" reveal at the end.
- **It is not:** a tool to fake any number you like. You can never type a caller-ID number or name. Our server picks the caller ID from either a VictorSpoof-owned number or your own verified number.
- No SMS spoofing. No auto-dialing. The app never becomes your phone's default dialer and never reads your call log.

## 2. Setup (first launch)

### 2.1 Read the disclaimer
The first screen is a scrollable disclaimer. Scroll to the bottom, then tap **I understand and agree**.

### 2.2 Confirm you are 18+
Tick the box confirming you are 18 or older and tap **Continue**. VictorSpoof is adults-only.

### 2.3 Accept the Acceptable Use Policy (AUP)
Read the AUP, tick **I accept the AUP**, and tap **Accept**. Your acceptance is logged on the VictorSpoof server with a timestamp. Calls stay locked until the server confirms it. If you see "Could not reach the VictorSpoof server", check your internet connection and tap Accept again.

## 3. Making a prank call

1. On the home screen, enter your friend's number or tap **Pick from contacts**.
2. Choose the caller ID type:
   - **A VictorSpoof fun number**: a number VictorSpoof owns. Where carriers support it, a fun name such as "Pizza Wizard" shows up.
   - **My own verified number**: your real number, after you verify it.
3. Optionally turn on **Record**. See section 7.
4. Tap **Call**. A confirmation dialog appears. Tap **Yes, call** to place the call, or **Cancel**.
5. The call lasts at most about 2 minutes. Tap **Hang up + reveal** to end early.
6. At the end, your friend hears the "You've been VictorSpoofed!" reveal sting.

Every call needs you to tap Call **and** confirm the dialog. Nothing (including fleet apps) can start a call for you.

## 4. Voice changer
Open **Voice** from the home screen and pick a preset (Normal, Chipmunk, Deep, Robot, Echo). The preset is used on your next call. (Preview audio is currently a stub.)

## 5. Soundboard
Open **Sounds** and tap a button (air horn, rimshot, sad trombone and more) to play it into the call. (Audio injection is currently a stub.)

## 6. Contacts picker
**Pick from contacts** opens your phone's contact chooser. VictorSpoof asks only for the Contacts permission (READ_CONTACTS) and only reads the number you pick.

## 7. Recording (opt-in)
Recording is off by default. If you turn it on, **everyone on the call hears an announcement** that the call is being recorded. Many places require every person on a call to consent, and the announcement makes sure they know.

## 8. Limits and blocked numbers
- About **10 calls per day**, at most **3 per day to the same number**, about **2 minutes per call**.
- Blocked forever: **911** and other emergency numbers, **988** (crisis line), all **N11** numbers (211, 311, 411, 511, 611, 711, 811), short codes, and known government, police, hospital and major-bank numbers.
- Caller names that impersonate police, the IRS, the FBI, banks, hospitals and the like are filtered out by the server.

## 9. Permissions

| Permission | Why |
|---|---|
| Internet / network state | Talk to the VictorSpoof server and the voice provider |
| Microphone (RECORD_AUDIO) | So your voice is heard on the call |
| Modify audio settings | Speaker/earpiece routing during calls |
| Contacts (READ_CONTACTS) | Only for the contact picker |

VictorSpoof explicitly does **not** request: call phone, call log, SMS, or phone-number access.

## 10. Fleet integration (Victorious fleet, EXTERNAL_NODE)

VictorSpoof can join the Victorious app fleet as an **EXTERNAL_NODE**.

- **External control is OFF by default.** Turn it on under **Settings → Allow fleet external control**.
- **Pairing:** under Settings, add the fleet app's package name and signing-certificate SHA-256. Only paired apps are answered. Identity is read from the Android system (the caller's UID), never from anything the message claims.
- **Node id:** shown in Settings, in the format `victorspoof-` followed by 32 hex characters.
- **What fleet apps can do:**

| Op | Kind | Remote allowed | Needs confirmation |
|---|---|---|---|
| spoof.status.get | read | yes | no |
| spoof.pool.list | read | yes | no |
| spoof.usage.get | read | yes | no |
| spoof.ui.open | action | yes | no |
| spoof.call.start | action | **no** | yes |
| spoof.call.end | action | **no** | yes |
| spoof.recording.delete | destructive | **no** | yes |
| config.export | action | **no** | no |
| config.import | action | **no** | yes |

**No fleet app can ever place, end or control a call, or delete recordings.** Those happen only from your own taps inside VictorSpoof. VictorSpoof decides each op's type itself, so a message that claims to be "not destructive" changes nothing.

Technical details for developers: bound Messenger service, action `com.victorious.spoof.action.BRIDGE`, protocol 1.1, `what=1` request / `what=2` response, newline-delimited JSON in the `payload` string. It uses frozen envelope v1 (`op, requestId, targetNodeRef, actionRef, params, nonce, timestampMs, ttlMs, destructive, trigger`, plus `macroId/stepIndex/onError` and `steps/dryRun` for macros). A message is rejected as `expired` when it is older than its TTL or more than 2 minutes off. TTL defaults to 120 s with a 300 s cap. Duplicate nonces from the same source are rejected for at least 10 minutes. Error categories: invalid_parameters, not_paired, not_found, internal, unauthorized, replay_detected, expired, external_control_disabled, confirmation_required, unsupported_op, capability_off.

Twilio credentials and the number pool live only on the VictorSpoof backend and in CI secrets. They are never shared with the fleet or stored in the app.

## 11. Settings backup (config.export / config.import)

VictorSpoof follows the fleet UNIFIED_BACKUP format (droidcommand-AI `docs/specs/UNIFIED_BACKUP.md`).

- **What is exported:** only look-and-feel preferences: theme, voice preset, soundboard volume, haptics, tips and text size.
- **What is never exported:** Twilio credentials, auth tokens, the number pool, phone numbers, account ids, tokens, pairings, the node id, or any fleet/call setting. Two layers enforce this. First, an allowlist means only the preferences above are ever read. Second, a refusal scan blocks the whole export if any forbidden name (such as `twilio`, `authToken`, `numberPool`, `token`, `account`, `phone`) or anything that looks like a phone number, credential or id shows up.
- **Export:** go to Settings → **Export settings**. The backup text appears so you can copy it.
- **Import:** paste a backup, then tap **Preview (dry run)**. This shows exactly what would change and writes nothing. Tap **Import…** and confirm the dialog to apply it. Existing preferences are kept unless the backup asks to overwrite.
- An import is refused if it contains any forbidden setting. An import can never turn on fleet external control, remote commands, or any calling or auto-dial behavior. Every applied import turns fleet external control **OFF**.
- Fleet apps can't run export or import remotely (`remoteAllowed:false`). They get `capability_off`. Both work only from your own taps in Settings.

## 12. Troubleshooting

- **"Finish onboarding first" / Call stays locked:** complete the disclaimer, 18+ and AUP steps. AUP acceptance must reach the server.
- **"denied: daily_limit" or "target_daily_limit":** you hit the daily limits. Try tomorrow (limits reset at midnight UTC).
- **"denied: blocked_target:…":** that number is protected and can never be called.
- **"denied: own_number_not_verified":** verify your own number first, or use a VictorSpoof fun number.
- **"token unavailable (503)":** the server's voice provider isn't configured yet. Try later.
- **Contact picker does nothing:** allow the Contacts permission in Android Settings → Apps → VictorSpoof.
- **No audio:** allow the Microphone permission.
- **"Import refused (invalid_parameters)":** the backup contains a forbidden or invalid setting, or it came from an unknown format. Export a fresh backup.
- **Fleet app gets `not_paired`:** pair its exact package and cert in Settings. **`external_control_disabled`:** turn on external control.

## 13. Privacy

- We store on the server: your account id, your 18+ confirmation, your AUP acceptance (version, time, IP, app version), and per-call rate-limit records (target number and date) needed to enforce the limits.
- On your phone: your consent status, paired fleet apps, and a VictorSpoof-only prank history with masked numbers. App data is excluded from cloud backups.
- We never read your call log or SMS. Contacts are read only when you pick one.
- Recordings exist only when you turn recording on, and the announcement plays.
- Twilio (primary) or Telnyx (backup) carries the calls under their own privacy terms.

## 14. Legal

VictorSpoof follows the U.S. **Truth in Caller ID Act of 2009** (47 U.S.C. § 227(e)): misleading caller ID with intent to defraud, cause harm, or wrongfully obtain anything of value is illegal. VictorSpoof never lets you pick arbitrary caller ID, blocks sensitive numbers and names, and always reveals the prank. You remain responsible for complying with local laws, including call-recording consent laws.
