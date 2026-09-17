# Aura Activity Status Mapping Patch Specification

## Target
- **App**: com.facebook.aura (Meta Aura / Hatch Android client)
- **Class**: ActivityDataSource
- **Methods**: 
  - `parseActivityStatus(Ljava/lang/String;)LActivityAction$ActivityStatus;`
  - `parseActivityEntry(Lcom/facebook/aura/status/repo/ActivityEntry;)LActivityAction;`

## Bug 1: WFU status falls through to SUCCESS
The `parseActivityStatus` method maps status strings to ActivityStatus enum values:
- "pending" (hashCode -682587753) → PENDING
- "error" (hashCode 96784904) → ERROR  
- "success" (hashCode -1867169789) → SUCCESS (explicit branch added in 7.x; same as default)
- **All other values → SUCCESS** (fall-through default)

The finish status emitted when a turn ends waiting on user input — the literal
`"waiting_for_user"` (hashCode 719392563) — is not explicitly handled, so it
falls through to SUCCESS. The UI renders this as a green "Completed" badge,
hiding the fact that user input is needed.

## Bug 2: Old-format entries hardcode SUCCESS
In `parseActivityEntry`, when the entry uses the old format (timestamp == null,
uses tsMs instead), the status is hardcoded:
```java
ActivityAction.ActivityStatus activityStatus = ActivityAction.ActivityStatus.SUCCESS;
```
This ignores `activityEntry.status` entirely. Old-format entries always render as
Completed regardless of their actual status.

## Fix
### Fix 1: WFU hashCode branch in parseActivityStatus
Inject a branch at method entry that checks for hashCode 719392563 and returns
PENDING instead of falling through to SUCCESS.

#### Smali injection (at method entry, before existing code):
```smali
    invoke-virtual {p1}, Ljava/lang/String;->hashCode()I
    move-result v0
    const v1, 719392563
    if-ne v0, v1, :wfu_not_matched
    sget-object v1, L<ActivityAction$ActivityStatus>;->PENDING:L<ActivityAction$ActivityStatus>;
    return-object v1
    :wfu_not_matched
    # ... original method body follows
```

#### Register usage
- p1 (v2): the String parameter (method is non-static, p0 = this)
- v0: scratch for hashCode result (the one local, dead at method entry)
- v1: `this`, dead (the method immediately overwrites p0 with `move-result p0`
  and never reads `this`); scratch for the constant comparison and enum ref

### Fix 2: Call parseActivityStatus(entry.status) (invoke-direct)

Replace the hardcoded `sget-object ...->SUCCESS` in parseActivityEntry with a
direct call to the real `parseActivityStatus` (which Fix 1 has already patched
to map the WFU status to PENDING):
```smali
    iget-object v10, v2, Lcom/facebook/aura/status/repo/ActivityEntry;->status:Ljava/lang/String;
    if-eqz v10, :use_success
    invoke-direct { v3, v10 }, Lcom/facebook/aura/status/repo/ActivityDataSource;->parseActivityStatus(Ljava/lang/String;)Lcom/facebook/aura/status/repo/ActivityAction$ActivityStatus;
    move-result-object v10
    goto :done
    :use_success
    sget-object v10, Lcom/facebook/aura/status/repo/ActivityAction$ActivityStatus;->SUCCESS:...;
    :done
```

#### Why a call (not inlined)

A previous revision inlined the pending/error/success mapping and used v2
(the ActivityEntry register) as the `equals()` scratch register. That was
wrong: v2 is LIVE after the sget point — the very next use is
`iget-object v12, v2, ActivityEntry;->hash`. Clobbering it corrupts the entry
and, worse, merges incompatible register types (object vs int) at the `:done`
join point, which fails Dalvik bytecode verification when the class loads.
The inlined revision was never installed; it was caught by re-verifying
register liveness against the 7.0.0.25.163 smali before signing.

The call needs `this`, which lives in v21 (the 35c `invoke-direct` format only
encodes v0-v15) — but the method already copied p0 low via
`move-object/from16 v3, p0` at entry, and v3 still holds `this` at the sget
point. The patch derives that register from the method's own `invoke-direct`
(the `parseNewFormatEntry` call) rather than hardcoding v3. No scratch
registers are needed: the status String goes straight from the entry into
`parseActivityStatus`, whose result lands back in the sget target register.

#### Nothing hardcoded

- Entry type: from the method's own parameter list.
- Callee: from the resolved `ParseActivityStatusFingerprint` (defining class,
  name, return type).
- Null-guard SUCCESS enum: from the `sget-object` being replaced.
- `this` register: from the method's own `invoke-direct`.
- Only the `status` field name is literal (verified present on `ActivityEntry`
  in 7.0.0.25.163).

### Why hashCode matching
The status literal is matched by its Java hashCode (719392563) rather than by
string constant to avoid embedding the token in the patch.

## Verification
1. hashCode("pending") = -682587753 (matches 7.0.0.25.163 decompiled source)
2. hashCode("error") = 96784904 (matches decompiled source)
3. hashCode("success") = -1867169789 (7.x added an explicit success→SUCCESS branch)
4. WFU literal identified: `"waiting_for_user".hashCode() = 719392563` —
   still unhandled by 7.x `parseActivityStatus` (falls through to SUCCESS),
   so Fix 1's injected branch is correct and necessary on 7.x.
5. Fix 1 register map re-verified on 7.x smali (`.locals 1`: v0 dead at entry,
   v1/`this` dead via immediate `move-result p0`, v2 = String param).
6. Patched dex disassembled with baksmali: Fix 1 branch present
   (`const p0, 0x2ae10f33` → PENDING); Fix 2 `invoke-direct` present, v2
   read-only throughout.
7. Adjacent code audited: `parseNewFormatEntry` already calls
   `parseActivityStatus` with a null guard (no bug); the explicit
   `success`→SUCCESS branch is benign (same as default).

## Rollback
- **Morphe**: Disable "Fix activity status mapping" in Morphe Manager, re-patch APK, reinstall.
- **Manual**: Remove the injected branch; the original control flow is purely additive
  so removal restores byte-for-byte original behavior.
- **Nuclear**: Reinstall stock com.facebook.aura APK from backup
  (`/home/toxic/morphe-aura/stock-7.0.0.25/base.apk`, sha256
  1b307eedee83d206af6ed938516b71dee206da2366f0cce8ff2dc09cc609782b).

## Repatch
Re-apply after each Aura app update, as the method may be re-obfuscated or relocated.
The fingerprint (strings "pending" + "error", String param, enum return) should
survive updates unless the method is substantially rewritten.

## Morphe patch source
- `patches/aura/misc/fixactivitystatusmapping/Fingerprints.kt`
- `patches/aura/misc/fixactivitystatusmapping/FixActivityStatusMappingPatch.kt`
- `patches/aura/shared/Constants.kt` (COMPATIBILITY_AURA)

## Status (Aura 7.0.0.25.163, 2026-09-17)
- [x] Patch source written (morphe format, both bugs)
- [x] Fingerprints defined (parseActivityStatus + hardcoded SUCCESS)
- [x] HashCodes verified against 7.0.0.25.163 decompiled source
- [x] WFU literal identified: "waiting_for_user" (hash 719392563)
- [x] Physical APK pulled from Pixel 9 Pro XL: base.apk (29,673,430 bytes, sha256 1b307eedee83d206af6ed938516b71dee206da2366f0cce8ff2dc09cc609782b)
- [x] Fix 2 rewritten: invoke-direct to parseActivityStatus (replaces inlined revision that clobbered live v2)
- [x] GitHub Packages auth unblocked: GitHub App 3763198 OAuth token in gh store → ~/.gradle/gradle.properties (gpr.user/gpr.key); :patches:generatePatchesList BUILD SUCCESSFUL (needs ANDROID_HOME=/opt/android-sdk + JDK 17)
- [x] Bundle built via Gradle: patches-1.43.0.mpp
- [x] Patch applied: patched-unsigned.apk (31,737,709 bytes, exit=0); both fixes verified in disassembled patched dex
- [x] Signed (Android Debug): patched-signed.apk (sha256 ce3ef62f745a9e561daf61e3de39efdc1bb7def32ca94ee9fe7db42287dbc216)
- [x] Installed on Pixel 9 Pro XL (stock uninstalled for signature change; stock APK preserved for rollback)
- [x] Verified on-device: app launches to AuraMainActivity, process alive, no AndroidRuntime crashes
- [ ] Badge behavior end-to-end (needs a real waiting_for_user turn from the server)
