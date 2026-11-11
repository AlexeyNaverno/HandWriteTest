# Signing key

`handwrite-release.jks` signs the APKs published on GitHub Releases. It exists so that users can
install a newer build **over** an older one: Android refuses an update signed by a different key.

**F-Droid does not use this key.** The store builds every app from source and signs the result with
its own per-app key, so a build on F-Droid is independent of this file. It is kept in the repository
only so that the GitHub releases stay updatable.

- alias: `handwrite`
- store/key password: `handwrite2026` (also read from `HANDWRITE_KS_PASS` / `HANDWRITE_KS_ALIAS`,
  which is how CI and a local Gradle release build pick it up)
- certificate SHA-256: `75e493e2415173b5e4c0567d8d4f1e3b28fb25e2190ffd9fec3d465a9b82b948`

A published password is not a security problem in the way it sounds - the key protects the *update
chain*, not the code, which is public. Losing the key would be worse than publishing it: users would
have to uninstall and reinstall. If you ever build the app for yourself, sign with your own key
instead of this one.
