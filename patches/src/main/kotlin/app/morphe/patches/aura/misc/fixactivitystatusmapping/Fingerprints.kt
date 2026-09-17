/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.aura.misc.fixactivitystatusmapping

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Locates ActivityDataSource.parseActivityStatus(String).
 *
 * The method takes a status string and maps it to an ActivityStatus enum.
 * It explicitly handles "pending" and "error" but lets every other value
 * fall through to SUCCESS — including the finish status that means the turn
 * is waiting on the user, which then renders as a green Completed badge.
 *
 * Anchors: the two string literals the method compares against ("pending",
 * "error") plus the const-class / sget-object enum access pattern. The WFU
 * literal itself is deliberately NOT used as an anchor (it never appears in
 * the method body — that absence is the bug).
 */
internal object ParseActivityStatusFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L", // ActivityStatus enum object
    parameters = listOf("Ljava/lang/String;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST_STRING,
        Opcode.INVOKE_VIRTUAL, // String.equals
        Opcode.IF_EQZ,
        Opcode.SGET_OBJECT, // ActivityStatus.PENDING / ERROR enum constants
        Opcode.RETURN_OBJECT,
    ),
    strings = listOf("pending", "error"),
)

/**
 * Locates the hardcoded ActivityStatus.SUCCESS in
 * ActivityDataSource.parseActivityEntry(ActivityEntry).
 *
 * In the old-format path (entry.timestamp == null), the status is hardcoded
 * to SUCCESS without consulting entry.status at all. Anchors: the method
 * takes an ActivityEntry, returns ActivityAction, and contains an sget-object
 * of the SUCCESS enum constant alongside the "changed_files" string literal
 * used just below the hardcoded assignment.
 */
internal object ParseActivityEntryHardcodedSuccessFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L", // ActivityAction object
    parameters = listOf("Lcom/facebook/aura/status/repo/ActivityEntry;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.SGET_OBJECT, // ActivityStatus.SUCCESS
        Opcode.CONST_STRING, // "changed_files" just below
    ),
    strings = listOf("changed_files"),
)
