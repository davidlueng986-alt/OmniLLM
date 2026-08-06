# Companion sandbox packaging notes

**Authority:** `SEC-EXTERNAL-SANDBOX`, `ADR-007`, `ANDROID-DIST`, `ARCH-TRUST-TOPOLOGY`  
**Module:** `:android:companion-sandbox`  
**applicationId:** `com.omnillm.companion`  
**Main app:** `com.omnillm` (`:android:app-ui`)

---

## 1. Why a separate APK

Untrusted accelerated inference must **not** share the main app Linux UID.  
Same-UID workers (`:engine_worker`) only contain crashes — they are **not** a data-security sandbox.

The companion is therefore:

| Property | Value |
|---|---|
| Package / UID | **Different** from `com.omnillm` |
| `sharedUserId` | **Forbidden** |
| Shared private storage | **Forbidden** |
| Keystore / token vault / control-plane DB | **Not present** |
| `INTERNET` / contacts / media / broad storage | **Not declared** (explicitly removed in manifest) |
| Binding | Explicit component + **same-signer** signature permission `com.omnillm.companion.permission.BIND_SANDBOX` + protocol ticket handshake (`CompanionBinderWire` / host `CompanionHostClient` / `HostCompanionClient`) |

Signature permission answers “who may control the companion”; it does **not** make untrusted native code trusted.

---

## 2. Build outputs

```bash
# Debug
./gradlew :android:companion-sandbox:assembleDebug

# Release APK (preferred distribution unit)
./gradlew :android:companion-sandbox:assembleRelease
# → android/companion-sandbox/build/outputs/apk/release/

# Optional AAB (only after multi-package Play strategy is validated)
./gradlew :android:companion-sandbox:bundleRelease
```

Toolchain lock matches main app: **compileSdk/targetSdk 36**, minSdk 28, NDK 28.2.x (`gradle/libs.versions.toml`).

---

## 3. Relationship to main App Bundle

| Artifact | Package | Play role |
|---|---|---|
| Main AAB | `com.omnillm` | Primary listing / install |
| Companion APK (or secondary package) | `com.omnillm.companion` | Optional sandbox for untrusted acceleration |

**Do not** `api()` / merge the companion application module into the main app so that code runs under `com.omnillm` UID — that would violate ADR-007.

Main runtime **binds** to companion via signature permission; main may depend on **protocol types** only, never on companion private data paths.

---

## 4. Signing

- Main and companion must be signed by the **same certificate** for `protectionLevel=signature` bind permission.
- Use the same upload/app signing key family as documented in `tools/ci/README.md`.
- CI workflow `.github/workflows/release.yml` builds **both** packages when signing secrets are present.

---

## 5. Play Console / distribution options

1. **Primary (recommended for architecture fidelity):** distribute companion as a **separately installed** same-signer package (Play multi-package / multiple APKs policy as applicable, or enterprise/side-load with identical trust rules). Side-load must **not** relax model verification or exported-component policy (`ANDROID-BASELINE` §2).
2. **If companion is optional:** main app must fail closed with `TRUST_PLACEMENT_REQUIRED` (or product-equivalent) when acceleration requires companion and it is missing — never fall back to same-UID “fast sandbox”.
3. **Do not claim** “sandboxed GPU” absolute safety; residual driver/kernel risks remain (`SEC-EXTERNAL-SANDBOX` §9).

Recheck Play multi-APK / multi-package rules before every submission (`platform-policy-register` recheckTrigger).

---

## 6. Versioning & protocol

- Companion `versionCode` / `versionName` may advance independently but must advertise **protocol major/minor** in handshake.
- Main app rejects old major protocol, wrong signer digest, replayed tickets, or cross-artifact tickets (fail closed).
- Drain companion before updates; cold start + re-qualify after update.

---

## 7. ProGuard / R8

- Skeleton: `android/companion-sandbox/proguard-rules.pro`
- `isMinifyEnabled` remains false until companion bind + native load smoke passes (`gradle/RELEASE_CHECKLIST.md` §H / §I).

---

## 8. Privacy / backup

- `allowBackup=false`, data extraction rules exclude all domains.
- Companion private data is **request-scoped** and treated as fully controllable by loaded model code — never store main-app secrets here.

---

## 9. Verification checklist

- [ ] `applicationId` is `com.omnillm.companion`
- [ ] Merged manifest has **no** `INTERNET`
- [ ] Only exported service is `CompanionSandboxService` with signature permission
- [ ] Same signing cert as main app
- [ ] Main app `uses-permission` `BIND_SANDBOX` present for runtime bind
- [ ] 16 KB native checks pass when `.so` present
- [ ] Missing companion does not silently use same-UID acceleration
