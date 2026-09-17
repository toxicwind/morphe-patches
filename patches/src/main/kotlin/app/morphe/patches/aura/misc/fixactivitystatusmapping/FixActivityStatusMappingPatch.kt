/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.aura.misc.fixactivitystatusmapping

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.aura.shared.Constants.COMPATIBILITY_AURA
import com.android.tools.smali.dexlib2.Opcode
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
        // Fix 1: WFU hashCode branch in parseActivityStatus
        ParseActivityStatusFingerprint.method.apply {
            val paramRegister = 0 // p0: the status String
            val hashRegister = 1 // free: int hashCode
            val enumRegister = 2 // free: ActivityStatus object

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
                    invoke-virtual { v$paramRegister }, Ljava/lang/String;->hashCode()I
                    move-result v$hashRegister
                    const v$hashRegister, $WFU_STATUS_HASHCODE
                    if-ne v$hashRegister, :wfu_not_matched
                    sget-object v$enumRegister, $enumType->$pendingField:$enumType
                    return-object v$enumRegister
                """.trimIndent(),
                ExternalLabel("wfu_not_matched", getInstruction(0)),
            )
        }

        // Fix 2: Replace hardcoded SUCCESS in parseActivityEntry old-format path
        // with a call to parseActivityStatus(entry.status).
        ParseActivityEntryHardcodedSuccessFingerprint.method.apply {
            val sgetIndex = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.SGET_OBJECT &&
                    (it as ReferenceInstruction).reference.let { ref ->
                        ref is FieldReference && ref.name == "SUCCESS"
                    }
            }
            val sgetInsn = getInstruction<ReferenceInstruction>(sgetIndex)
            val targetRegister = (sgetInsn as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
            val entryRegister = 1 // p1: the ActivityEntry parameter
            val statusRegister = 2 // free: String status

            // Replace: sget-object vX, ...->SUCCESS:...
            // With: iget-object vStatus, vEntry, ActivityEntry->status:String
            //       invoke-direct {p0, vStatus}, ActivityDataSource->parseActivityStatus(String):ActivityStatus
            //       move-result-object vX
            val parseMethod = ParseActivityStatusFingerprint.method
            val dataSourceClass = parseMethod.definingClass

            replaceInstruction(
                sgetIndex,
                """
                    iget-object v$statusRegister, v$entryRegister, Lcom/facebook/aura/status/repo/ActivityEntry;->status:Ljava/lang/String;
                    if-nez v$statusRegister, :status_not_null
                    const-string v$statusRegister, ""
                    :status_not_null
                    invoke-direct { p0, v$statusRegister }, $dataSourceClass->parseActivityStatus(Ljava/lang/String;)L${(parseMethod.returnType as String).substring(1)};
                    move-result-object v$targetRegister
                """.trimIndent()
            )
        }
    }
}
