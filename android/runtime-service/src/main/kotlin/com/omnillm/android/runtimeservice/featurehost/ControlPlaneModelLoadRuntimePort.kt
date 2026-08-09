package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.features.modelhub.ports.ModelLoadRuntimePort

/**
 * Control-plane implementation of [ModelLoadRuntimePort] (M4): exposes the
 * attached primary engine build + observed device fingerprint for ModelHub
 * LOAD commands. Fail closed (null) before engine packs attach — the feature
 * never loads native engines itself (INV-001).
 */
class ControlPlaneModelLoadRuntimePort(
    private val engineExecute: EngineExecuteBinding?,
    private val deviceFingerprint: () -> DeviceExecutionFingerprint? = {
        com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
            .defaultDeviceFingerprint()
    },
) : ModelLoadRuntimePort {

    override fun primaryEngineBuildId(): String? {
        val attachment = engineExecute?.attachment ?: return null
        return attachment.llamaCppRegistration?.engineBuildId?.value
            ?: attachment.registrationsByEngineId.values.firstOrNull()?.engineBuildId?.value
    }

    override fun deviceExecutionFingerprint(): DeviceExecutionFingerprint? =
        deviceFingerprint()
}
