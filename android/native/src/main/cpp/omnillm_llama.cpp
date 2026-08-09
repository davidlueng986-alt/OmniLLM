/**
 * OmniLLM llama native engine — lifecycle + dispatch (ENGINE-LLAMACPP).
 *
 * Dual backend:
 * 1) EXPERIMENTAL_FIXTURE — always available; real C++ tokenize/generate loop.
 * 2) Upstream llama.cpp — when OMNILLM_HAS_LLAMA_CPP and path/FD provided.
 *
 * Never writes OmniLLM DB. Never elevates qualification status.
 */

#include "omnillm_llama.h"
#include "omnillm_backend.h"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {

std::mutex g_mu;
std::atomic<int32_t> g_model_seq{0};
std::atomic<int32_t> g_session_seq{0};

/**
 * Ownership model (COR-01): sessions and models are reference-counted so that
 * `close_session` / `unload_model` become *logical removal* — they remove the
 * map entry under g_mu but destruction (which frees llama_context / llama_model)
 * is deferred until the last in-flight generate drops its strong reference.
 * A generate thread therefore can never hold a dangling SessionHandle* even
 * when close/unload overlap generation; cancel keeps working because the entry
 * is only logically removed.
 */
struct ModelRef {
    omnillm::ModelHandle handle;

    ~ModelRef() {
        if (handle.kind == omnillm::ModelKind::UpstreamGguf) {
            omnillm::upstream_unload_model(&handle);
        }
    }
};

struct SessionRef {
    omnillm::SessionHandle handle;
    /**
     * Strong reference to the owning model: unload_model also becomes logical
     * (deferred) until the last session that used the model is destroyed, so a
     * llama_context is never left referencing a freed llama_model.
     */
    std::shared_ptr<ModelRef> model;

    ~SessionRef() {
        if (handle.kind == omnillm::ModelKind::UpstreamGguf) {
            omnillm::upstream_close_session(&handle);
        } else {
            omnillm::fixture_close_session(&handle);
        }
    }
};

std::unordered_map<std::string, std::shared_ptr<ModelRef>> g_models;
std::unordered_map<std::string, std::shared_ptr<SessionRef>> g_sessions;

/**
 * Cooperative cancel tokens (COR-23d): bounded. Every generate purges its own
 * token on completion; request_cancel clears the set when it overflows so an
 * idle/stale cancel cannot grow without bound.
 */
constexpr size_t kMaxCancelOps = 1024;
std::unordered_set<std::string> g_cancel_ops;

bool is_cpu_backend(const char* backend) {
    return backend != nullptr && std::strcmp(backend, "cpu") == 0;
}

bool is_fixture_request(
    const char* storage_root_key,
    const char* installation_key,
    const char* resolved_model_path) {
    if (installation_key != nullptr &&
        std::strcmp(installation_key, OMNILLM_LLAMA_FIXTURE_TOKEN) == 0) {
        return true;
    }
    if (resolved_model_path != nullptr &&
        std::strncmp(
            resolved_model_path,
            OMNILLM_LLAMA_FIXTURE_PATH_PREFIX,
            std::strlen(OMNILLM_LLAMA_FIXTURE_PATH_PREFIX)) == 0) {
        return true;
    }
    if (storage_root_key != nullptr &&
        std::strncmp(
            storage_root_key,
            OMNILLM_LLAMA_FIXTURE_PATH_PREFIX,
            std::strlen(OMNILLM_LLAMA_FIXTURE_PATH_PREFIX)) == 0) {
        return true;
    }
    if (resolved_model_path != nullptr &&
        std::strcmp(resolved_model_path, OMNILLM_LLAMA_FIXTURE_TOKEN) == 0) {
        return true;
    }
    return false;
}

bool is_op_cancelled(const char* op) {
    if (op == nullptr) return false;
    std::lock_guard<std::mutex> lock(g_mu);
    return g_cancel_ops.find(op) != g_cancel_ops.end();
}

bool has_path_or_fd(const char* resolved_model_path, int32_t model_fd) {
    if (model_fd >= 0) return true;
    if (resolved_model_path != nullptr && resolved_model_path[0] != '\0') {
        // fixture: prefix is not a filesystem GGUF path
        if (std::strncmp(
                resolved_model_path,
                OMNILLM_LLAMA_FIXTURE_PATH_PREFIX,
                std::strlen(OMNILLM_LLAMA_FIXTURE_PATH_PREFIX)) == 0) {
            return false;
        }
        if (std::strcmp(resolved_model_path, OMNILLM_LLAMA_FIXTURE_TOKEN) == 0) {
            return false;
        }
        return true;
    }
    return false;
}

}  // namespace

extern "C" void omnillm_llama_library_label(char* out_buf, size_t out_cap) {
    if (omnillm::upstream_linked()) {
        std::snprintf(
            out_buf,
            out_cap,
            "%s+upstream;fixture=%s",
            OMNILLM_LLAMA_LABEL,
            OMNILLM_LLAMA_FIXTURE_TOKEN);
    } else {
        std::snprintf(
            out_buf,
            out_cap,
            "%s+fixture_only;mode=%s",
            OMNILLM_LLAMA_LABEL,
            OMNILLM_LLAMA_FIXTURE_TOKEN);
    }
}

extern "C" int32_t omnillm_llama_abi_version(void) {
    return OMNILLM_LLAMA_ABI_VERSION;
}

extern "C" int32_t omnillm_llama_upstream_linked(void) {
    return omnillm::upstream_linked() ? 1 : 0;
}

extern "C" int32_t omnillm_llama_probe(
    const char* backend,
    const char* operation_token,
    char* out_attrs,
    size_t out_attrs_cap) {
    if (backend == nullptr || operation_token == nullptr ||
        operation_token[0] == '\0') {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    if (!is_cpu_backend(backend)) {
        if (out_attrs != nullptr && out_attrs_cap > 0) {
            std::snprintf(
                out_attrs,
                out_attrs_cap,
                "backend=%s;library=%s;native=true;available=false;upstreamLinked=%s",
                backend,
                OMNILLM_LLAMA_LABEL,
                omnillm::upstream_linked() ? "true" : "false");
        }
        return OMNILLM_LLAMA_UNSUPPORTED_OPERATION;
    }
    if (out_attrs != nullptr && out_attrs_cap > 0) {
        std::snprintf(
            out_attrs,
            out_attrs_cap,
            "backend=cpu;library=%s;native=true;available=true;operationToken=%s;"
            "abi=%d;upstreamLinked=%s;fixtureAvailable=true;fixtureMode=%s",
            OMNILLM_LLAMA_LABEL,
            operation_token,
            OMNILLM_LLAMA_ABI_VERSION,
            omnillm::upstream_linked() ? "true" : "false",
            OMNILLM_LLAMA_FIXTURE_TOKEN);
    }
    return OMNILLM_LLAMA_OK;
}

extern "C" int32_t omnillm_llama_load_model(
    const char* storage_root_key,
    const char* installation_key,
    const char* backend,
    int32_t n_ctx,
    int32_t n_threads,
    const char* privileged_load_ticket_id,
    const char* resolved_model_path,
    int32_t model_fd,
    char* out_model_token,
    size_t out_model_token_cap) {
    if (storage_root_key == nullptr || storage_root_key[0] == '\0' ||
        installation_key == nullptr || installation_key[0] == '\0' ||
        backend == nullptr || backend[0] == '\0' ||
        privileged_load_ticket_id == nullptr ||
        privileged_load_ticket_id[0] == '\0' ||
        out_model_token == nullptr || out_model_token_cap == 0) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    if (n_ctx <= 0 || n_threads <= 0) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    if (!is_cpu_backend(backend)) {
        return OMNILLM_LLAMA_UNSUPPORTED_OPERATION;
    }

    omnillm::ModelHandle rec;
    rec.storage_root_key = storage_root_key;
    rec.installation_key = installation_key;
    rec.backend = backend;
    rec.n_ctx = n_ctx;
    rec.n_threads = n_threads;
    rec.upstream_model = nullptr;

    const bool want_fixture = is_fixture_request(
        storage_root_key, installation_key, resolved_model_path);
    const bool path_or_fd = has_path_or_fd(resolved_model_path, model_fd);

    // Fail closed (INV-018 / ENGINE-LLAMACPP honesty):
    // - Explicit EXPERIMENTAL_FIXTURE markers → fixture only
    // - Path or FD → upstream GGUF (or NOT_AVAILABLE if not linked)
    // - Broker-only keys without fixture markers → NOT_AVAILABLE
    //   (never silently substitute fixture for a real install intent)
    if (want_fixture) {
        // Exploratory packaging / control-plane proof only; never elevates cells.
        rec.kind = omnillm::ModelKind::ExperimentalFixture;
    } else if (path_or_fd) {
        if (!omnillm::upstream_linked()) {
            // Path/FD requested but upstream not linked in this binary.
            return OMNILLM_LLAMA_NOT_AVAILABLE;
        }
        int32_t rc = OMNILLM_LLAMA_MODEL_OPEN_FAILED;
        if (model_fd >= 0) {
            rc = omnillm::upstream_load_model_fd(model_fd, 0, &rec);
        } else {
            rc = omnillm::upstream_load_model_path(resolved_model_path, 0, &rec);
        }
        if (rc != OMNILLM_LLAMA_OK) {
            return rc;
        }
        rec.kind = omnillm::ModelKind::UpstreamGguf;
    } else {
        return OMNILLM_LLAMA_NOT_AVAILABLE;
    }

    const int32_t id = g_model_seq.fetch_add(1) + 1;
    char token_buf[80];
    if (rec.kind == omnillm::ModelKind::ExperimentalFixture) {
        std::snprintf(token_buf, sizeof(token_buf), "fixture-model-%d", id);
    } else {
        std::snprintf(token_buf, sizeof(token_buf), "gguf-model-%d", id);
    }

    auto mref = std::make_shared<ModelRef>();
    mref->handle = std::move(rec);
    {
        std::lock_guard<std::mutex> lock(g_mu);
        g_models[token_buf] = std::move(mref);
    }
    omnillm::copy_cstr(out_model_token, out_model_token_cap, token_buf);
    return OMNILLM_LLAMA_OK;
}

extern "C" int32_t omnillm_llama_create_session(
    const char* model_token,
    int32_t n_ctx,
    int64_t seed,
    int32_t has_seed,
    char* out_session_token,
    size_t out_session_token_cap) {
    if (model_token == nullptr || model_token[0] == '\0' ||
        out_session_token == nullptr || out_session_token_cap == 0 ||
        n_ctx <= 0) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }

    omnillm::ModelHandle model;
    std::shared_ptr<ModelRef> model_ref;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        auto it = g_models.find(model_token);
        if (it == g_models.end()) {
            return OMNILLM_LLAMA_INVALID_ARGUMENT;
        }
        model_ref = it->second;
        model = model_ref->handle;
    }

    omnillm::SessionHandle sess;
    sess.model_token = model_token;
    int32_t rc = OMNILLM_LLAMA_OK;
    if (model.kind == omnillm::ModelKind::UpstreamGguf) {
        rc = omnillm::upstream_create_session(
            model, n_ctx, seed, has_seed != 0, model.n_threads, &sess);
    } else {
        rc = omnillm::fixture_create_session(
            model, n_ctx, seed, has_seed != 0, &sess);
    }
    if (rc != OMNILLM_LLAMA_OK) {
        return rc;
    }

    const int32_t id = g_session_seq.fetch_add(1) + 1;
    char token_buf[80];
    std::snprintf(token_buf, sizeof(token_buf), "native-session-%d", id);
    auto sref = std::make_shared<SessionRef>();
    sref->handle = std::move(sess);
    // Keep the model alive for the session's lifetime (deferred unload, COR-01).
    sref->model = std::move(model_ref);
    {
        std::lock_guard<std::mutex> lock(g_mu);
        g_sessions[token_buf] = std::move(sref);
    }
    omnillm::copy_cstr(out_session_token, out_session_token_cap, token_buf);
    return OMNILLM_LLAMA_OK;
}

extern "C" int32_t omnillm_llama_generate(
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
    int32_t /*stop_sequence_count*/,
    const int32_t* cancel_flag,
    omnillm_llama_stream_cb cb,
    void* user_data,
    int32_t* out_prompt_tokens,
    int32_t* out_completion_tokens,
    char* out_stop_reason,
    size_t out_stop_reason_cap) {
    if (session_token == nullptr || session_token[0] == '\0' ||
        operation_token == nullptr || operation_token[0] == '\0' ||
        prompt_digest_hex == nullptr || prompt_digest_hex[0] == '\0' ||
        max_tokens <= 0 ||
        out_prompt_tokens == nullptr || out_completion_tokens == nullptr ||
        out_stop_reason == nullptr || out_stop_reason_cap == 0) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }

    // Strong refs keep the session + its model alive for the whole generate,
    // even when close_session/unload_model logically remove them under g_mu
    // concurrently (COR-01): erasing the map entry can no longer free memory
    // that the generate thread is using. Destruction is deferred until the
    // last reference (this local + the map entry) is dropped.
    std::shared_ptr<ModelRef> model_ref;
    std::shared_ptr<SessionRef> sess_ref;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        auto sit = g_sessions.find(session_token);
        if (sit == g_sessions.end()) {
            return OMNILLM_LLAMA_INVALID_ARGUMENT;
        }
        auto mit = g_models.find(sit->second->handle.model_token);
        if (mit == g_models.end()) {
            return OMNILLM_LLAMA_INVALID_ARGUMENT;
        }
        model_ref = mit->second;
        sess_ref = sit->second;
    }
    omnillm::SessionHandle* sess_ptr = &sess_ref->handle;
    const omnillm::ModelHandle& model = model_ref->handle;

    // Prompt text: prefer UTF-8 body; else use digest as synthetic tokenize input.
    const char* prompt_text =
        (prompt_utf8 != nullptr && prompt_utf8[0] != '\0')
            ? prompt_utf8
            : prompt_digest_hex;

    int32_t rc;
    // Generate without the global lock so request_cancel can insert into
    // g_cancel_ops; the strong refs above guarantee the session/model outlive
    // the call regardless of concurrent close/unload.
    if (model.kind == omnillm::ModelKind::UpstreamGguf) {
        rc = omnillm::upstream_generate(
            sess_ptr,
            model,
            prompt_text,
            max_tokens,
            temperature,
            has_temperature != 0,
            top_p,
            has_top_p != 0,
            top_k,
            has_top_k != 0,
            cancel_flag,
            operation_token,
            is_op_cancelled,
            cb,
            user_data,
            out_prompt_tokens,
            out_completion_tokens,
            out_stop_reason,
            out_stop_reason_cap);
    } else {
        rc = omnillm::fixture_generate(
            sess_ptr,
            prompt_text,
            max_tokens,
            cancel_flag,
            operation_token,
            is_op_cancelled,
            cb,
            user_data,
            out_prompt_tokens,
            out_completion_tokens,
            out_stop_reason,
            out_stop_reason_cap);
    }
    // Purge this operation's cooperative-cancel token now that the operation
    // is over (COR-23d); request_cancel caps the set for stale/late cancels.
    {
        std::lock_guard<std::mutex> lock(g_mu);
        g_cancel_ops.erase(operation_token);
    }
    return rc;
}

extern "C" int32_t omnillm_llama_embed(
    const char* model_token,
    const char* /*operation_token*/,
    const char* /*input_digest_hex*/) {
    if (model_token == nullptr || model_token[0] == '\0') {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    {
        std::lock_guard<std::mutex> lock(g_mu);
        if (g_models.find(model_token) == g_models.end()) {
            return OMNILLM_LLAMA_INVALID_ARGUMENT;
        }
    }
    // Fail closed: pooling not qualified for this build (ENGINE-LLAMACPP §10).
    return OMNILLM_LLAMA_UNSUPPORTED_OPERATION;
}

extern "C" int32_t omnillm_llama_close_session(const char* session_token) {
    if (session_token == nullptr || session_token[0] == '\0') {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    std::lock_guard<std::mutex> lock(g_mu);
    // Logical removal (COR-01): the session is detached from the registry now,
    // but the native handle is freed only when the last in-flight generate
    // drops its strong reference.
    g_sessions.erase(session_token);
    return OMNILLM_LLAMA_OK;
}

extern "C" int32_t omnillm_llama_unload_model(const char* model_token) {
    if (model_token == nullptr || model_token[0] == '\0') {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    std::lock_guard<std::mutex> lock(g_mu);
    // Detach all sessions of this model (logical removal) and then the model
    // itself. Actual llama_context / llama_model destruction is deferred to
    // the refcount: an in-flight generate holds strong refs, so unload cannot
    // free memory still in use (COR-01). Sessions destroyed after the model
    // entry is erased still keep the model alive via SessionRef::model.
    std::vector<std::string> to_close;
    for (const auto& kv : g_sessions) {
        if (kv.second->handle.model_token == model_token) {
            to_close.push_back(kv.first);
        }
    }
    for (const auto& s : to_close) {
        g_sessions.erase(s);
    }
    g_models.erase(model_token);
    return OMNILLM_LLAMA_OK;
}

extern "C" int32_t omnillm_llama_request_cancel(const char* operation_token) {
    if (operation_token == nullptr || operation_token[0] == '\0') {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    std::lock_guard<std::mutex> lock(g_mu);
    // Bounded cooperative cancel (COR-23d): overflow evicts the whole set —
    // cancel is best-effort and each generate re-checks its own token.
    if (g_cancel_ops.size() >= kMaxCancelOps) {
        g_cancel_ops.clear();
    }
    g_cancel_ops.insert(operation_token);
    return OMNILLM_LLAMA_OK;
}
