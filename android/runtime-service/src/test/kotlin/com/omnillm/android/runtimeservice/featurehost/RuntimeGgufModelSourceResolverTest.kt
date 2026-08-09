package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.identity.IdentityHashing
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.ModelInstallationAggregate
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.modelstore.FilesystemModelStorePort
import com.omnillm.data.modelstore.FilesystemQuarantineStore
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.memory.InMemoryInstallationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Host tests for [RuntimeGgufModelSourceResolver]: READY installation →
 * content re-verify (INV-010) → in-process real path for llama-cpp load.
 */
class RuntimeGgufModelSourceResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = "real-gguf-bytes-v1".toByteArray()
    private val revision = ModelRevisionId.parse("b".repeat(64))
    private val packageId = ArtifactPackageId.parse("c".repeat(64))
    private val installId = InstallationId("11111111-2222-4333-8444-555555555555")
    private val digest = Sha256Digest.parse("d".repeat(64))

    private fun readyInstallation(): InstallationSnapshot {
        val aggregate = ModelInstallationAggregate(
            installationId = installId,
            state = "READY",
        )
        return InstallationSnapshot(
            aggregate = aggregate,
            modelRevisionId = revision,
            artifactPackageId = packageId,
            storageRootKey = "installations/${installId.value}",
        )
    }

    private fun plan() = Plan(
        planId = PlanId.parse("plan-gguf-resolver"),
        requestId = RequestId.parse(UUID.randomUUID().toString()),
        principalId = PrincipalId.parse("principal-test"),
        modelRevisionId = revision,
        engineBuildId = EngineBuildId.parse("llama-cpp-b9999-android"),
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-resolver"),
        canonicalInputDigest = digest,
        resourceEnvelope = ResourceEnvelope(
            steady = ResourceVector(cpuAnonBytes = 1_024L, nativeThreads = 1L),
            peak = ResourceVector(cpuAnonBytes = 2_048L, nativeThreads = 2L),
        ),
        expiryMonotonic = 1_000_000L,
        runtimeEpoch = 1L,
    )

    /** Promote a real payload via the filesystem store (role-file projection). */
    private suspend fun promotePayload(filesRoot: java.nio.file.Path): FilesystemModelStorePort {
        val store = FilesystemModelStorePort.create(
            filesRoot = filesRoot,
            monotonicNowMs = { 1_000L },
        )
        val key = QuarantineKey("job-resolver", "attempt-1")
        val expectedBlob = com.omnillm.core.canonical.generated.BlobId.parse(
            IdentityHashing.sha256Hex(payload),
        )
        val opened = store.openQuarantine(
            key = key,
            installationId = com.omnillm.core.identity.InstallationId.ofValidated(installId.value),
            modelRevisionId = revision,
            artifactPackageId = packageId,
            declared = listOf(
                DeclaredArtifactFile(
                    role = "WEIGHTS",
                    blobId = expectedBlob,
                    byteLength = payload.size.toLong(),
                ),
            ),
            deadlineMonotonic = 60_000L,
        )
        assertTrue(opened is OmniResult.Ok)
        val mat = store.materializeFromStream(
            key = key,
            role = "WEIGHTS",
            expectedBlobId = expectedBlob,
            expectedByteLength = payload.size.toLong(),
            input = ByteArrayInputStream(payload),
        )
        assertTrue(mat is OmniResult.Ok)
        val promoted = store.atomicPromote(
            key = key,
            installationId = com.omnillm.core.identity.InstallationId.ofValidated(installId.value),
            modelRevisionId = revision,
            artifactPackageId = packageId,
        )
        assertTrue(promoted is OmniResult.Ok)
        return store
    }

    @Test
    fun resolve_readyInstallation_returnsRealPath() = runBlocking {
        val root = tmp.root.toPath()
        val store = promotePayload(root)
        val repo = InMemoryInstallationRepository()
        assertTrue(repo.save(readyInstallation()) is OmniResult.Ok)

        val resolver = RuntimeGgufModelSourceResolver(
            filesRoot = root,
            installations = repo,
            readyContent = store,
        )
        val result = resolver.resolve(plan())
        assertTrue("resolve failed: $result", result is OmniResult.Ok)
        val source = (result as OmniResult.Ok).value
        assertEquals("installations/${installId.value}", source.storageRootKey)
        assertTrue("expected real path, got ${source.resolvedModelPath}", !source.resolvedModelPath.isNullOrBlank())
        val file = java.io.File(source.resolvedModelPath!!)
        assertTrue("resolved path must exist", file.isFile)
        assertTrue(file.readBytes().contentEquals(payload))
    }

    @Test
    fun resolve_noReadyInstallation_failsClosed() = runBlocking {
        val root = tmp.root.toPath()
        val store = promotePayload(root)
        val repo = InMemoryInstallationRepository()
        // Save as non-READY (aggregate state DISCOVERED via discovered()).
        val discovered = InstallationSnapshot.discovered(
            installationId = installId,
            modelRevisionId = revision,
            artifactPackageId = packageId,
        )
        assertTrue(repo.save(discovered) is OmniResult.Ok)

        val resolver = RuntimeGgufModelSourceResolver(
            filesRoot = root,
            installations = repo,
            readyContent = store,
        )
        val result = resolver.resolve(plan())
        assertTrue("must fail closed without READY installation", result is OmniResult.Err)
    }

    @Test
    fun resolve_missingRevision_returnsNotFound() = runBlocking {
        val root = tmp.root.toPath()
        val store = promotePayload(root)
        val repo = InMemoryInstallationRepository()
        val resolver = RuntimeGgufModelSourceResolver(
            filesRoot = root,
            installations = repo,
            readyContent = store,
        )
        val result = resolver.resolve(plan())
        assertTrue("must be NOT_FOUND", result is OmniResult.Err)
    }
}
