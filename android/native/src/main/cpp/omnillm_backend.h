/**
 * Internal backend interface for libomnillm_llama (not exported).
 */
#pragma once

#include "omnillm_llama.h"

#include <cstdint>
#include <string>
#include <vector>

namespace omnillm {

enum class ModelKind {
    /** Deterministic C++ path — labeled EXPERIMENTAL_FIXTURE, never QUALIFIED. */
    ExperimentalFixture,
    /** Real llama.cpp GGUF model handle. */
    UpstreamGguf,
};

struct ModelHandle {
    ModelKind kind = ModelKind::ExperimentalFixture;
    std::string storage_root_key;
    std::string installation_key;
    std::string backend;
    int32_t n_ctx = 0;
    int32_t n_threads = 0;
    /** Opaque upstream pointer (llama_model*) when kind == UpstreamGguf. */
    void* upstream_model = nullptr;
};

struct SessionHandle {
    std::string model_token;
    ModelKind kind = ModelKind::ExperimentalFixture;
    int32_t n_ctx = 0;
    int64_t seed = 0;
    bool has_seed = false;
    /** Opaque upstream pointers (llama_context*, llama_sampler*). */
    void* upstream_ctx = nullptr;
    void* upstream_sampler = nullptr;
    /** Fixture decode state. */
    std::vector<int32_t> prompt_tokens;
    int32_t n_past = 0;
};

/** True when compiled with OMNILLM_HAS_LLAMA_CPP. */
bool upstream_linked();

/** Load GGUF from path. Returns null model on failure (caller maps status). */
int32_t upstream_load_model_path(
    const char* path,
    int32_t n_gpu_layers,
    ModelHandle* out);

/** Load GGUF from open FD (dup + fdopen). fd is not closed by caller contract. */
int32_t upstream_load_model_fd(
    int32_t fd,
    int32_t n_gpu_layers,
    ModelHandle* out);

int32_t upstream_create_session(
    const ModelHandle& model,
    int32_t n_ctx,
    int64_t seed,
    bool has_seed,
    int32_t n_threads,
    SessionHandle* out);

int32_t upstream_generate(
    SessionHandle* session,
    const ModelHandle& model,
    const char* prompt_text,
    int32_t max_tokens,
    float temperature,
    bool has_temperature,
    float top_p,
    bool has_top_p,
    int32_t top_k,
    bool has_top_k,
    const int32_t* cancel_flag,
    const char* operation_token,
    bool (*is_op_cancelled)(const char* op),
    omnillm_llama_stream_cb cb,
    void* user_data,
    int32_t* out_prompt_tokens,
    int32_t* out_completion_tokens,
    char* out_stop_reason,
    size_t out_stop_reason_cap);

void upstream_close_session(SessionHandle* session);
void upstream_unload_model(ModelHandle* model);

/** Fixture: deterministic tokenize of prompt into pseudo-token ids. */
std::vector<int32_t> fixture_tokenize(const char* prompt_text);

int32_t fixture_create_session(
    const ModelHandle& model,
    int32_t n_ctx,
    int64_t seed,
    bool has_seed,
    SessionHandle* out);

int32_t fixture_generate(
    SessionHandle* session,
    const char* prompt_text,
    int32_t max_tokens,
    const int32_t* cancel_flag,
    const char* operation_token,
    bool (*is_op_cancelled)(const char* op),
    omnillm_llama_stream_cb cb,
    void* user_data,
    int32_t* out_prompt_tokens,
    int32_t* out_completion_tokens,
    char* out_stop_reason,
    size_t out_stop_reason_cap);

void fixture_close_session(SessionHandle* session);

/** Hex SHA-256-shaped digest from bytes (deterministic FNV-based filler for wire). */
void digest_hex64_from_bytes(const void* data, size_t len, char out_hex[65]);

void copy_cstr(char* out, size_t cap, const char* src);

}  // namespace omnillm
