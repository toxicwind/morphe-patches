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

### Fix 2: Parse old-format entry status
Replace the hardcoded `sget-object ...->SUCCESS` in parseActivityEntry with:
```smali
    iget-object v2, v1, Lcom/facebook/aura/status/repo/ActivityEntry;->status:Ljava/lang/String;
    if-nez v2, :status_not_null
    const-string v2, ""
    :status_not_null
    invoke-direct {p0, v2}, L<ActivityDataSource>;->parseActivityStatus(Ljava/lang/String;)L<ActivityAction$ActivityStatus>;
    move-result-object vX  # X = original target register
```

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

## Status
- [x] Patch source written (morphe format, both bugs)
- [x] Fingerprints defined (parseActivityStatus + hardcoded SUCCESS)
- [x] HashCodes verified against decompiled source
- [x] Files staged on awrawr-pc under morphe-patches tree
- [ ] Patcher CLI built (gradle, long)
- [ ] Patches bundle built (gradle, long)
- [ ] APK obtained from device
- [ ] Patch applied and tested
- [ ] Installed on Pixel 9 Pro XL
