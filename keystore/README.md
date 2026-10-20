# Keystores

`victorspoof-debug.jks` is a **debug-only** keystore, committed on purpose so every
fleet node and CI run produce APKs with the same debug signing cert (pairing is by
package + cert).

- alias / store password / key password: `victorspoof-debug`
- SHA-256: `A4:84:62:2B:7E:86:E1:D9:F8:04:56:6E:B7:89:2D:EE:3C:64:EB:8E:B9:13:9E:04:25:BC:65:F3:EF:D0:FF:EE`

**The release key is never committed.** Release builds read it from CI secrets
(`VS_RELEASE_KEYSTORE_B64`, `VS_RELEASE_STORE_PASSWORD`, `VS_RELEASE_KEY_ALIAS`,
`VS_RELEASE_KEY_PASSWORD`). Without them, release signing is not configured.
