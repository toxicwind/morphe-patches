/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.aura.misc.fixactivitystatusmapping

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.smali.toInstructions
import app.morphe.patches.aura.shared.Constants.COMPATIBILITY_AURA
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Fixes the activity-status mapping bugs in the Aura app.
 *
 * Bug 1: ActivityDataSource.parseActivityStatus(String) explicitly handles
 * "pending" and "error" but maps every other string to ActivityStatus.SUCCESS.
 * The finish status emitted when a turn ends waiting on the user therefore
 * renders as a green Completed badge; the user never sees that input is needed.
 *
 * Bug 2: ActivityDataSource.parseActivityEntry(ActivityEntry) hardcodes
 * ActivityStatus.SUCCESS for old-format entries without ever consulting
 * entry.status. Old-format entries always render as Completed regardless of
 * their actual status.
 *
 * Fix 1 injects a branch at parseActivityStatus entry: if the input string's
 * hashCode matches the WFU status literal (719392563), return
 * ActivityStatus.PENDING instead of falling through to SUCCESS.
 *
 * Fix 2 replaces the hardcoded SUCCESS sget in parseActivityEntry with a call
 * to parseActivityStatus(entry.status), so old-format entries get their real
 * status.
 *
 * The WFU literal is matched by hashCode rather than by string constant so the
 * patch never embeds the status token itself.
 *
 * Rollback: disable the patch in Morphe Manager and re-patch, or reinstall
 * the stock APK. Both injected branches are purely additive — removing the
 * patch restores the original control flow byte-for-byte.
 */
private const val WFU_STATUS_HASHCODE = 719392563

@Suppress("unused")
val fixActivityStatusMappingPatch = bytecodePatch(
    name = "Fix activity status mapping",
    description = "Map the waiting-on-user finish status to PENDING instead of SUCCESS, and parse old-format entry status instead of hardcoding SUCCESS."
) {
    compatibleWith(COMPATIBILITY_AURA)

    execute {
        // Resolve BOTH fingerprints before any modification. Fingerprint
        // matching runs against the pristine dex; resolving upfront avoids
        // any interaction between Fix 1's edits and Fix 2's matching.
        val statusMethod = ParseActivityStatusFingerprint.method
        val entryMethod = ParseActivityEntryHardcodedSuccessFingerprint.method

        // Fix 1: WFU hashCode branch in parseActivityStatus.
        // Register map (verified against apktool smali of 6.0.0.48.164):
        //   v0 = the one local, dead at method entry.
        //   v1 = p0 = `this`, dead: the method immediately overwrites it with
        //        `move-result p0` after the hashCode call and never reads `this`.
        //   v2 = p1 = the status String parameter.
        statusMethod.apply {
            val stringRegister = 2 // v2: p1, the status String
            val hashRegister = 0 // v0: dead at entry, scratch for the int hash
            val tmpRegister = 1 // v1: `this`, dead; scratch for const + enum ref

            // Find the ActivityStatus class from an existing sget-object so the
            // injected code references the real enum type on this build.
            val sgetIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.SGET_OBJECT
            }
            val sgetRef = (getInstruction<ReferenceInstruction>(sgetIndex).reference as FieldReference)
            val enumType = sgetRef.definingClass
            // Derive the PENDING field name from the existing PENDING sget if present,
            // else fall back to the literal field name.
            val pendingField = sgetRef.name
                .takeIf { it.contains("PENDING", ignoreCase = true) } ?: "PENDING"

            addInstructionsWithLabels(
                0,
                """
                    invoke-virtual { v$stringRegister }, Ljava/lang/String;->hashCode()I
                    move-result v$hashRegister
                    const v$tmpRegister, $WFU_STATUS_HASHCODE
                    if-ne v$hashRegister, v$tmpRegister, :wfu_not_matched
                    sget-object v$tmpRegister, $enumType->$pendingField:$enumType
                    return-object v$tmpRegister
                """.trimIndent(),
                ExternalLabel("wfu_not_matched", getInstruction(0)),
            )
        }

        // Fix 2: Replace hardcoded SUCCESS in parseActivityEntry old-format path
        // with an inlined status mapping (pending->PENDING, error->ERROR,
        // else->SUCCESS).
        //
        // NOTE: Inlining (instead of calling parseActivityStatus) avoids the
        // v15 register limit. The parseActivityStatus method needs `this` in
        // v21, which neither the inline smali compiler nor the 35c instruction
        // format can encode. v2 (the ActivityEntry) is dead after the sget
        // point (verified via register liveness), so it doubles as the temp.
        // v10 (the sget target) is dead by definition.
        ParseActivityEntryHardcodedSuccessFingerprint.method.apply {
            val sgetIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.SGET_OBJECT &&
                    (it as ReferenceInstruction).reference.let { ref ->
                        ref is FieldReference && ref.name == "SUCCESS"
                    }
            }
            val sgetInsn = getInstruction<ReferenceInstruction>(sgetIndex)
            val targetRegister = (sgetInsn as OneRegisterInstruction).registerA
            val entryRegister = 2
            val tempRegister = 2
            val statusClass = "Lcom/facebook/aura/status/repo/ActivityAction\$ActivityStatus;"

            val smali = """
                iget-object v$targetRegister, v$entryRegister, Lcom/facebook/aura/status/repo/ActivityEntry;->status:Ljava/lang/String;
                if-nez v$targetRegister, :use_success
                const-string v$tempRegister, "pending"
                invoke-virtual { v$targetRegister, v$tempRegister }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v$tempRegister
                if-eqz v$tempRegister, :check_error
                sget-object v$targetRegister, $statusClass->PENDING:$statusClass
                goto :done
                :check_error
                const-string v$tempRegister, "error"
                invoke-virtual { v$targetRegister, v$tempRegister }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v$tempRegister
                if-eqz v$tempRegister, :use_success
                sget-object v$targetRegister, $statusClass->ERROR:$statusClass
                goto :done
                :use_success
                sget-object v$targetRegister, $statusClass->SUCCESS:$statusClass
                :done
            """.trimIndent()

            removeInstructions(sgetIndex, 1)
            addInstructions(sgetIndex, smali.toInstructions(this))
        }
    }
}
