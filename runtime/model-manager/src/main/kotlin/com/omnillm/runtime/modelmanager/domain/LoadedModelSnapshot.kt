package com.omnillm.runtime.modelmanager.domain

import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelAggregate
import com.omnillm.core.state.domain.LoadedModelId

/**
 * LOADED_MODEL aggregate projection (CORE-MODEL §1.2).
 *
 * Keyed by LoadKey dimensions; holds engine build, placement, allocation, and
 * references a READY installation. Installation drain triggers loaded-model
 * drain — the two state machines are never merged into one column.
 */
data class LoadedModelSnapshot(
    val aggregate: LoadedModelAggregate,
    val loadKey: LoadKey,
    val engineBuildId: EngineBuildId,
    val placementClass: String,
    val allocationHandleId: AllocationHandleId? = null,
    val runtimeEpoch: Long,
    val sessionReferenceCount: Int = 0,
) {
    val loadedModelId: LoadedModelId get() = aggregate.loadedModelId
    val installationId: InstallationId get() = aggregate.installationId
    val state: String get() = aggregate.state

    fun isLoaded(): Boolean = state == "LOADED"
    fun isTerminal(): Boolean = aggregate.isTerminal()

    init {
        require(placementClass.isNotEmpty()) { "placementClass must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(sessionReferenceCount >= 0) { "sessionReferenceCount must be non-negative" }
    }

    companion object {
        fun planned(
            loadedModelId: LoadedModelId,
            installationId: InstallationId,
            loadKey: LoadKey,
            engineBuildId: EngineBuildId,
            placementClass: String,
            runtimeEpoch: Long,
        ): LoadedModelSnapshot =
            LoadedModelSnapshot(
                aggregate = LoadedModelAggregate.initial(loadedModelId, installationId),
                loadKey = loadKey,
                engineBuildId = engineBuildId,
                placementClass = placementClass,
                runtimeEpoch = runtimeEpoch,
            )
    }
}
