/**
 * OmniLLM llama native C API (ENGINE-LLAMACPP §2).
 *
 * Production-shaped surface for the JNI bridge. Implementation links pinned
 * ggml-org/llama.cpp when vendored (OMNILLM_HAS_LLAMA_CPP) and always supports
 * a deterministic EXPERIMENTAL_FIXTURE path that exercises a real C++
 * tokenize → decode-loop → token-delta stream (not a Kotlin-only stub).
 *
 * Rules:
 * - Opaque model/session tokens only (never expose raw pointers on wire)
 * - No OmniLLM DB / catalog writes
 * - Unsupported ops fail closed
 * - Capability matrix / qualification YAML must stay UNQUALIFIED / UNKNOWN
 */
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Library soname / loadLibrary name: omnillm_llama */
#define OMNILLM_LLAMA_LIBRARY_NAME "omnillm_llama"
/** Diagnostic label — not a trust elevation. */
#define OMNILLM_LLAMA_LABEL "omnillm-llama-0.2"
/**
 * ABI version — bump when JNI / C signatures change.
 * Must stay aligned with JniNativeMapping.ABI_VERSION_EXPECTED.
 */
#define OMNILLM_LLAMA_ABI_VERSION 2

/** Magic installation / path tokens that select EXPERIMENTAL_FIXTURE (not GGUF). */
#define OMNILLM_LLAMA_FIXTURE_TOKEN "EXPERIMENTAL_FIXTURE"
#define OMNILLM_LLAMA_FIXTURE_PATH_PREFIX "fixture:"

/** Status codes — keep aligned with JniNativeMapping.Status in Kotlin. */
enum OmnillmLlamaStatus {
    OMNILLM_LLAMA_OK = 0,
    OMNILLM_LLAMA_NOT_AVAILABLE = 1,
    OMNILLM_LLAMA_INVALID_ARGUMENT = 2,
    OMNILLM_LLAMA_UNSUPPORTED_PARAMETER = 3,
    OMNILLM_LLAMA_UNSUPPORTED_OPERATION = 4,
    OMNILLM_LLAMA_MODEL_OPEN_FAILED = 5,
    OMNILLM_LLAMA_CONTEXT_CREATE_FAILED = 6,
    OMNILLM_LLAMA_TOKENIZE_FAILED = 7,
    OMNILLM_LLAMA_GENERATE_FAILED = 8,
    OMNILLM_LLAMA_CANCELLED = 9,
    OMNILLM_LLAMA_DEADLINE_EXCEEDED = 10,
    OMNILLM_LLAMA_RESOURCE_EXHAUSTED = 11,
    OMNILLM_LLAMA_INTERNAL = 12,
    OMNILLM_LLAMA_WORKER_CRASH = 13,
};

/** Stream event kinds — keep aligned with NativeStreamKind ordinal mapping. */
enum OmnillmLlamaStreamKind {
    OMNILLM_LLAMA_EVT_METADATA = 0,
    OMNILLM_LLAMA_EVT_TOKEN_DELTA = 1,
    OMNILLM_LLAMA_EVT_USAGE = 2,
    OMNILLM_LLAMA_EVT_DIAGNOSTIC = 3,
    OMNILLM_LLAMA_EVT_WARNING = 4,
    OMNILLM_LLAMA_EVT_STOP = 5,
};

typedef void (*omnillm_llama_stream_cb)(
    int32_t kind,
    const char* payload_digest_hex,
    const char* attributes_kv,
    void* user_data);

/** Returns OMNILLM_LLAMA_LABEL (+ build mode) into out_buf (NUL-terminated). */
void omnillm_llama_library_label(char* out_buf, size_t out_cap);

/** ABI version constant. */
int32_t omnillm_llama_abi_version(void);

/**
 * Bounded probe. backend must be "cpu" for this build.
 * Writes attributes_kv "k=v;k=v" into out_attrs when non-null.
 * Reports upstreamLinked / fixtureAvailable honestly.
 */
int32_t omnillm_llama_probe(
    const char* backend,
    const char* operation_token,
    char* out_attrs,
    size_t out_attrs_cap);

/**
 * Load model by opaque storage keys and optional resolved path / FD.
 *
 * Path / FD selection (ENGINE-LLAMACPP §2, INV-010, fail-closed INV-018):
 * - Explicit fixture markers only → EXPERIMENTAL_FIXTURE backend (no GGUF):
 *     installation_key == EXPERIMENTAL_FIXTURE, or
 *     storage_root_key / resolved_model_path starts with "fixture:", or
 *     resolved_model_path / storage_root_key equals EXPERIMENTAL_FIXTURE.
 * - model_fd >= 0 → load GGUF via FILE* from FD (when upstream linked).
 * - resolved_model_path non-empty filesystem path → load GGUF from path
 *   (when upstream linked).
 * - Broker keys only (no fixture marker, no path/FD) → NOT_AVAILABLE
 *   (no silent fixture substitution for real install intent).
 * - Path/FD requested but upstream not linked → NOT_AVAILABLE.
 * Never elevates qualification / SUPPORTED.
 *
 * On success writes opaque model token into out_model_token.
 */
int32_t omnillm_llama_load_model(
    const char* storage_root_key,
    const char* installation_key,
    const char* backend,
    int32_t n_ctx,
    int32_t n_threads,
    const char* privileged_load_ticket_id,
    const char* resolved_model_path,
    int32_t model_fd,
    char* out_model_token,
    size_t out_model_token_cap);

int32_t omnillm_llama_create_session(
    const char* model_token,
    int32_t n_ctx,
    int64_t seed,
    int32_t has_seed,
    char* out_session_token,
    size_t out_session_token_cap);

/**
 * Tokenize + prefill/decode loop. Cooperative cancel via cancel_flag
 * (non-zero = cancel) and omnillm_llama_request_cancel(operation_token).
 * Emits stream events through cb (TOKEN_DELTA digests, USAGE, STOP).
 *
 * prompt_utf8 may be null/empty; then prompt_digest_hex is used as synthetic
 * prompt text for tokenize (fixture and exploratory paths).
 */
int32_t omnillm_llama_generate(
    const char* session_token,
    const char* operation_token,
    const char* prompt_digest_hex,
    const char* prompt_utf8,
    int32_t max_tokens,
    float temperature,
    int32_t has_temperature,
    float top_p,
    int32_t has_top_p,
    int32_t top_k,
    int32_t has_top_k,
    int32_t stop_sequence_count,
    const int32_t* cancel_flag,
    omnillm_llama_stream_cb cb,
    void* user_data,
    int32_t* out_prompt_tokens,
    int32_t* out_completion_tokens,
    char* out_stop_reason,
    size_t out_stop_reason_cap);

/** Embedding is fail-closed until pooling is qualified. */
int32_t omnillm_llama_embed(
    const char* model_token,
    const char* operation_token,
    const char* input_digest_hex);

/**
 * Logically closes [session_token]. The session is detached from the registry
 * immediately; native handles are freed once no in-flight generate holds the
 * session (reference-counted, COR-01). Safe to call while a generate using the
 * same session is running — cancel must be requested via
 * omnillm_llama_request_cancel for the running operation.
 */
int32_t omnillm_llama_close_session(const char* session_token);

/**
 * Logically unloads [model_token]: all its sessions and the model are detached
 * from the registry; native llama_context / llama_model destruction is deferred
 * until in-flight generates (and the last session) release their references
 * (reference-counted, COR-01). Safe to call concurrently with generates.
 */
int32_t omnillm_llama_unload_model(const char* model_token);

/** Best-effort cooperative cancel for an in-flight generate. */
int32_t omnillm_llama_request_cancel(const char* operation_token);

/**
 * Returns 1 when this binary was linked against vendored llama.cpp,
 * 0 when only EXPERIMENTAL_FIXTURE is available.
 */
int32_t omnillm_llama_upstream_linked(void);

#ifdef __cplusplus
}
#endif
