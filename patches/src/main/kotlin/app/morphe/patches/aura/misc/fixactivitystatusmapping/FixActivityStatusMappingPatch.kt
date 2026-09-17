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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
// "waiting_for_user".hashCode() — verified against 7.0.0.25.163 decompile.
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
        // Register map (verified against apktool smali of 7.0.0.25.163,
        // .locals 1):
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

        // Fix 2: Replace the hardcoded SUCCESS sget in parseActivityEntry's
        // old-format path with a call to parseActivityStatus(entry.status).
        //
        // Register map (verified against apktool smali of 7.0.0.25.163):
        //   v2 = the ActivityEntry (p1), copied low at method entry. LIVE
        //        before AND after the sget point (the next use is
        //        `iget-object v12, v2, ->hash`), so it is only READ here,
        //        never written. A previous revision used v2 as the equals()
        //        scratch register; that corrupted the entry and would fail
        //        bytecode verification when the class loads.
        //   vN = the sget target register, dead at the sget point by
        //        definition; carries the status String, then the mapped enum.
        //   `this` = derived from the method's own invoke-direct (v3 here),
        //        never hardcoded: the 35c invoke-direct cannot encode the p0
        //        slot (v21), but the method already copied it low.
        //
        // No scratch registers are needed and no class paths are hardcoded:
        // the entry type comes from the method's parameter list, the callee
        // from the resolved parseActivityStatus fingerprint, and the
        // null-guard SUCCESS enum from the sget being replaced.
        ParseActivityEntryHardcodedSuccessFingerprint.method.apply {
            val methodImpl = implementation!!
            val sgetIndex = methodImpl.instructions.indexOfFirst {
                it.opcode == Opcode.SGET_OBJECT &&
                    (it as ReferenceInstruction).reference.let { ref ->
                        ref is FieldReference && ref.name == "SUCCESS"
                    }
            }
            val sgetInsn = getInstruction<ReferenceInstruction>(sgetIndex)
            val targetRegister = (sgetInsn as OneRegisterInstruction).registerA
            val enumRef = sgetInsn.reference as FieldReference

            // Entry register: the low copy of the ActivityEntry parameter
            // (move-object/from16 vX, p1 at method entry).
            val entryParamReg = methodImpl.registerCount - 1
            val entryRegister = (methodImpl.instructions.first {
                (it.opcode == Opcode.MOVE_OBJECT_FROM16 ||
                    it.opcode == Opcode.MOVE_OBJECT_16 ||
                    it.opcode == Opcode.MOVE_OBJECT) &&
                    (it as TwoRegisterInstruction).registerB == entryParamReg
            } as TwoRegisterInstruction).registerA
            val entryType = parameterTypes[0]

            // `this`: first register of the method's own invoke-direct on
            // ActivityDataSource (the parseNewFormatEntry call).
            val thisRegister = (methodImpl.instructions.first {
                it.opcode == Opcode.INVOKE_DIRECT &&
                    ((it as ReferenceInstruction).reference as? MethodReference)
                        ?.definingClass == definingClass
            } as FiveRegisterInstruction).registerC

            val callee = "${statusMethod.definingClass}->${statusMethod.name}" +
                "(Ljava/lang/String;)${statusMethod.returnType}"

            val smali = """
                iget-object v$targetRegister, v$entryRegister, $entryType->status:Ljava/lang/String;
                if-eqz v$targetRegister, :use_success
                invoke-direct { v$thisRegister, v$targetRegister }, $callee
                move-result-object v$targetRegister
                goto :done
                :use_success
                sget-object v$targetRegister, ${enumRef.definingClass}->${enumRef.name}:${enumRef.definingClass}
                :done
            """.trimIndent()

            removeInstructions(sgetIndex, 1)
            addInstructions(sgetIndex, smali.toInstructions(this))
        }
    }
}
