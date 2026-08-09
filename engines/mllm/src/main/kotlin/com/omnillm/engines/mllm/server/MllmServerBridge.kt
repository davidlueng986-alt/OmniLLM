package com.omnillm.engines.mllm.server

/**
 * Bridge to the upstream Go in-app server entry point (`mllm_server.aar`,
 * `gomllm.Gomllm.startServer`). Device-side only — the GoMobile binding
 * (`libgojni.so`) can only load on Android arm64.
 *
 * [MllmServerBackend] depends on this interface so host JVM unit tests can
 * substitute a fake; production wiring uses [GomllmServerBridge].
 *
 * Honest upstream facts (mllm-chat v2.0, verified 2026-08-09):
 * - The AAR exposes exactly one entry: `startServer(modelPath, ocrPath, tmpDir,
 *   enableProbing)`. It starts an OpenAI-compatible HTTP/SSE server on
 *   `127.0.0.1:8080` and registers a session for the chat model in
 *   `modelPath` (request `model` field must match the model directory name).
 * - There is **no stop/unload API** — the server lives until process death.
 * - The server does **not** authenticate requests; the runtime credential is
 *   adapter-enforced at this boundary (ENGINE-MLLM §7).
 */
interface MllmServerBridge {
    /**
     * Start the in-app Go server for [modelPath] (directory with `.mllm` model
     * files). [ocrPath] is left empty (OCR/multimodal unsupported-by-default).
     * Returns the upstream status string (diagnostics only — server reachability
     * is proven separately by a loopback probe).
     */
    fun startServer(
        modelPath: String,
        ocrPath: String,
        tmpDir: String,
        enableProbing: Boolean,
    ): String

    /**
     * Best-effort server stop. Upstream exposes no stop API, so this returns
     * false — [MllmServerBackend.shutdown] must report the limitation honestly.
     */
    fun stopServer(): Boolean
}

/**
 * Production bridge. `android.system.Os.setenv("TMPDIR", ...)` mirrors the
 * upstream demo (`MainActivity.startLocalMllmServer`) — the Go server needs a
 * writable temp dir. Referencing `android.system.Os` keeps this class
 * device-only (never exercised by host unit tests).
 */
object GomllmServerBridge : MllmServerBridge {

    override fun startServer(
        modelPath: String,
        ocrPath: String,
        tmpDir: String,
        enableProbing: Boolean,
    ): String {
        android.system.Os.setenv("TMPDIR", tmpDir, true)
        return gomllm.Gomllm.startServer(modelPath, ocrPath, tmpDir, enableProbing)
    }

    override fun stopServer(): Boolean = false
}
