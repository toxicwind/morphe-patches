# Aura Activity Status Mapping Patch Specification

## Target
- **App**: com.facebook.aura (Meta Aura / Hatch Android client)
- **Class**: ActivityDataSource
- **Methods**: 
  - `parseActivityStatus(Ljava/lang/String;)LActivityAction$ActivityStatus;` (line 1214)
  - `parseActivityEntry(Lcom/facebook/aura/status/repo/ActivityEntry;)LActivityAction;` (line 835)

## Bug 1: WFU status falls through to SUCCESS
The `parseActivityStatus` method maps status strings to ActivityStatus enum values:
- "pending" (hashCode -682587753) → PENDING
- "error" (hashCode 96784904) → ERROR  
- **All other values → SUCCESS** (fall-through default)

The finish status emitted when a turn ends waiting on user input (hashCode 719392563)
is not explicitly handled, so it falls through to SUCCESS. The UI renders this as a
green "Completed" badge, hiding the fact that user input is needed.

## Bug 2: Old-format entries hardcode SUCCESS
In `parseActivityEntry`, when the entry uses the old format (timestamp == null,
uses tsMs instead), line 866 hardcodes:
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
    sget-object v0, L<ActivityAction$ActivityStatus>;->PENDING:L<ActivityAction$ActivityStatus>;
    return-object v0
    :wfu_not_matched
    # ... original method body follows
```

#### Register usage
- p1: the String parameter (method is non-static, p0 = this)
- v0: scratch for hashCode result, then for the enum object
- v1: scratch for the constant comparison

### Fix 2: Parse old-format entry status (INLINED)
Replace the hardcoded `sget-object ...->SUCCESS` in parseActivityEntry with an
inlined status mapping:
```smali
    iget-object v10, v2, Lcom/facebook/aura/status/repo/ActivityEntry;->status:Ljava/lang/String;
    if-nez v10, :use_success
    const-string v2, "pending"
    invoke-virtual { v10, v2 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v2
    if-eqz v2, :check_error
    sget-object v10, Lcom/facebook/aura/status/repo/ActivityAction$ActivityStatus;->PENDING:...;
    goto :done
    :check_error
    const-string v2, "error"
    invoke-virtual { v10, v2 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v2
    if-eqz v2, :use_success
    sget-object v10, Lcom/facebook/aura/status/repo/ActivityAction$ActivityStatus;->ERROR:...;
    goto :done
    :use_success
    sget-object v10, Lcom/facebook/aura/status/repo/ActivityAction$ActivityStatus;->SUCCESS:...;
    :done
```

#### Why inlined (not a method call)
The `parseActivityStatus` method is private instance method requiring `this`.
In Aura 7.0.0.25.163, `this` lives in v21. The inline smali compiler rejects
registers above v15, the 35c instruction format is limited to v0-v15, and the
3rc (range) format requires consecutive registers. Inlining the
pending->PENDING, error->ERROR, else->SUCCESS logic avoids `this` entirely.
v2 (the ActivityEntry) is dead after the sget point (verified via register
liveness analysis), so it serves as the temp. v10 (the sget target) is dead
by definition.

### Why hashCode matching
The status literal is matched by its Java hashCode (719392563) rather than by
string constant to avoid embedding the token in the patch.

## Verification
1. hashCode("pending") = -682587753 ✓ (matches decompiled source)
2. hashCode("error") = 96784904 ✓ (matches decompiled source)
3. WFU status hashCode = 719392563 (from prior recon)

## Rollback
- **Morphe**: Disable "Fix activity status mapping" in Morphe Manager, re-patch APK, reinstall.
- **Manual**: Remove the injected branch; the original control flow is purely additive
  so removal restores byte-for-byte original behavior.
- **Nuclear**: Reinstall stock com.facebook.aura APK from Play Store / backup.

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
- [x] HashCodes verified against decompiled source
- [x] Physical APK pulled: base-base.apk (29,673,430 bytes, sha256 1b307eed...)
- [x] Fingerprint fixed for 7.0.0.25.163 (SGET_OBJECT/CONST_STRING non-adjacent)
- [x] Fix 2 inlined (v21 register limit bypassed)
- [x] Bundle built: patches-1.43.0.mpp (via kotlinc direct, Gradle blocked on plugin auth)
- [x] Patch applied: aura-7.0.0.25.163-patched-unsigned.apk (27,131,634 bytes, exit=0)
- [x] Verified: PENDING/ERROR branches present, hardcoded SUCCESS replaced
- [ ] Installed on Pixel 9 Pro XL (needs Chris to reconnect phone)
