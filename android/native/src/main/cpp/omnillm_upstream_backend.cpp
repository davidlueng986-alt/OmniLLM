/**
 * Upstream ggml-org/llama.cpp backend (path/FD load, context, tokenize, generate).
 *
 * Compiled only when OMNILLM_HAS_LLAMA_CPP is defined (vendored tree present).
 * Qualification cells remain UNQUALIFIED until device evidence — this file does
 * not mint SUPPORTED.
 */

#include "omnillm_backend.h"

#ifdef OMNILLM_HAS_LLAMA_CPP

#include "llama.h"

#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#if !defined(_WIN32) || defined(__ANDROID__)
#include <unistd.h>
#endif

namespace omnillm {
namespace {

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
    });
}

bool cancelled(
    const int32_t* cancel_flag,
    const char* operation_token,
    bool (*is_op_cancelled)(const char* op)) {
    if (cancel_flag != nullptr && *cancel_flag != 0) return true;
    if (is_op_cancelled != nullptr && operation_token != nullptr) {
        return is_op_cancelled(operation_token);
    }
    return false;
}

llama_model* load_model_file(const char* path) {
    ensure_backend();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU-only until accelerator cells are qualified
    return llama_model_load_from_file(path, mparams);
}

llama_model* load_model_file_ptr(FILE* fp) {
    ensure_backend();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    return llama_model_load_from_file_ptr(fp, mparams);
}

}  // namespace

bool upstream_linked() { return true; }

int32_t upstream_load_model_path(
    const char* path,
    int32_t /*n_gpu_layers*/,
    ModelHandle* out) {
    if (path == nullptr || path[0] == '\0' || out == nullptr) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    llama_model* model = load_model_file(path);
    if (model == nullptr) {
        return OMNILLM_LLAMA_MODEL_OPEN_FAILED;
    }
    out->kind = ModelKind::UpstreamGguf;
    out->upstream_model = model;
    return OMNILLM_LLAMA_OK;
}

int32_t upstream_load_model_fd(
    int32_t fd,
    int32_t /*n_gpu_layers*/,
    ModelHandle* out) {
    if (fd < 0 || out == nullptr) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
#if defined(_WIN32) && !defined(__ANDROID__)
    // Host Windows unit builds: FD path not supported for llama FILE*.
    (void)fd;
    return OMNILLM_LLAMA_UNSUPPORTED_OPERATION;
#else
    // Dup so ownership of the original FD stays with the broker.
    const int dup_fd = dup(fd);
    if (dup_fd < 0) {
        return OMNILLM_LLAMA_MODEL_OPEN_FAILED;
    }
    FILE* fp = fdopen(dup_fd, "rb");
    if (fp == nullptr) {
        close(dup_fd);
        return OMNILLM_LLAMA_MODEL_OPEN_FAILED;
    }
    llama_model* model = load_model_file_ptr(fp);
    // llama owns the FILE* after success? API takes FILE* — close after load.
    // Current llama.cpp reads through the FILE during load; close when done.
    std::fclose(fp);
    if (model == nullptr) {
        return OMNILLM_LLAMA_MODEL_OPEN_FAILED;
    }
    out->kind = ModelKind::UpstreamGguf;
    out->upstream_model = model;
    return OMNILLM_LLAMA_OK;
#endif
}

int32_t upstream_create_session(
    const ModelHandle& model,
    int32_t n_ctx,
    int64_t seed,
    bool has_seed,
    int32_t n_threads,
    SessionHandle* out) {
    if (out == nullptr || model.upstream_model == nullptr || n_ctx <= 0) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    auto* lmodel = static_cast<llama_model*>(model.upstream_model);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = static_cast<uint32_t>(n_ctx < 512 ? n_ctx : 512);
    if (n_threads > 0) {
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads;
    }
    llama_context* ctx = llama_init_from_model(lmodel, cparams);
    if (ctx == nullptr) {
        return OMNILLM_LLAMA_CONTEXT_CREATE_FAILED;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (smpl == nullptr) {
        llama_free(ctx);
        return OMNILLM_LLAMA_CONTEXT_CREATE_FAILED;
    }
    // Default chain: optional temp/top-k/top-p applied at generate time;
    // base dist sampler for greedy/default.
    const uint32_t seed_u = has_seed
        ? static_cast<uint32_t>(seed & 0xffffffffu)
        : 0xC0FFEEu;
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed_u));

    out->kind = ModelKind::UpstreamGguf;
    out->n_ctx = n_ctx;
    out->seed = seed;
    out->has_seed = has_seed;
    out->upstream_ctx = ctx;
    out->upstream_sampler = smpl;
    out->n_past = 0;
    out->prompt_tokens.clear();
    return OMNILLM_LLAMA_OK;
}

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
    size_t out_stop_reason_cap) {
    if (session == nullptr || session->upstream_ctx == nullptr ||
        model.upstream_model == nullptr || max_tokens <= 0 ||
        out_prompt_tokens == nullptr || out_completion_tokens == nullptr ||
        out_stop_reason == nullptr || out_stop_reason_cap == 0) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }

    auto* lmodel = static_cast<llama_model*>(model.upstream_model);
    auto* ctx = static_cast<llama_context*>(session->upstream_ctx);
    auto* smpl = static_cast<llama_sampler*>(session->upstream_sampler);
    const llama_vocab* vocab = llama_model_get_vocab(lmodel);
    if (vocab == nullptr) {
        return OMNILLM_LLAMA_INTERNAL;
    }

    // Rebuild sampler chain when sampling params present (fail closed if invalid).
    if (smpl != nullptr && (has_temperature || has_top_p || has_top_k)) {
        llama_sampler_free(smpl);
        auto sparams = llama_sampler_chain_default_params();
        smpl = llama_sampler_chain_init(sparams);
        if (smpl == nullptr) {
            session->upstream_sampler = nullptr;
            return OMNILLM_LLAMA_GENERATE_FAILED;
        }
        if (has_top_k && top_k > 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        }
        if (has_top_p && top_p > 0.0f && top_p < 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        }
        if (has_temperature) {
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        }
        const uint32_t seed_u = session->has_seed
            ? static_cast<uint32_t>(session->seed & 0xffffffffu)
            : 0xC0FFEEu;
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed_u));
        session->upstream_sampler = smpl;
    }

    const char* text = (prompt_text != nullptr && prompt_text[0] != '\0')
        ? prompt_text
        : "";
    // Tokenize
    const int32_t text_len = static_cast<int32_t>(std::strlen(text));
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
    std::vector<llama_token> tokens(static_cast<size_t>(n_ctx));
    const int32_t n_tok = llama_tokenize(
        vocab,
        text,
        text_len,
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        /*add_special=*/true,
        /*parse_special=*/true);
    if (n_tok < 0) {
        // Buffer too small — fail closed (no silent truncate of unknown size).
        return OMNILLM_LLAMA_TOKENIZE_FAILED;
    }
    if (n_tok == 0) {
        return OMNILLM_LLAMA_TOKENIZE_FAILED;
    }
    tokens.resize(static_cast<size_t>(n_tok));
    if (n_tok >= n_ctx) {
        return OMNILLM_LLAMA_RESOURCE_EXHAUSTED;
    }

    if (cb != nullptr) {
        char meta[192];
        std::snprintf(
            meta,
            sizeof(meta),
            "library=%s;mode=upstream;native=true;promptTokens=%d",
            OMNILLM_LLAMA_LABEL,
            n_tok);
        cb(OMNILLM_LLAMA_EVT_METADATA, nullptr, meta, user_data);
    }

    // Prefill
    for (int32_t i = 0; i < n_tok; ++i) {
        if (cancelled(cancel_flag, operation_token, is_op_cancelled)) {
            if (cb != nullptr) {
                cb(OMNILLM_LLAMA_EVT_STOP, nullptr, "stopReason=CANCELLED", user_data);
            }
            *out_prompt_tokens = n_tok;
            *out_completion_tokens = 0;
            copy_cstr(out_stop_reason, out_stop_reason_cap, "CANCELLED");
            return OMNILLM_LLAMA_CANCELLED;
        }
        llama_batch batch = llama_batch_get_one(&tokens[static_cast<size_t>(i)], 1);
        if (llama_decode(ctx, batch) != 0) {
            return OMNILLM_LLAMA_GENERATE_FAILED;
        }
    }

    int32_t completed = 0;
    for (int32_t i = 0; i < max_tokens; ++i) {
        if (cancelled(cancel_flag, operation_token, is_op_cancelled)) {
            if (cb != nullptr) {
                cb(OMNILLM_LLAMA_EVT_STOP, nullptr, "stopReason=CANCELLED", user_data);
            }
            *out_prompt_tokens = n_tok;
            *out_completion_tokens = completed;
            copy_cstr(out_stop_reason, out_stop_reason_cap, "CANCELLED");
            return OMNILLM_LLAMA_CANCELLED;
        }

        const llama_token id = llama_sampler_sample(smpl, ctx, -1);
        llama_sampler_accept(smpl, id);

        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        char piece[256];
        piece[0] = '\0';
        const int32_t n_piece = llama_token_to_piece(
            vocab, id, piece, static_cast<int32_t>(sizeof(piece) - 1), 0, true);
        if (n_piece < 0) {
            return OMNILLM_LLAMA_GENERATE_FAILED;
        }
        if (n_piece > 0 && n_piece < static_cast<int32_t>(sizeof(piece))) {
            piece[n_piece] = '\0';
        } else {
            piece[0] = '\0';
        }

        char dig[65];
        if (n_piece > 0) {
            digest_hex64_from_bytes(piece, static_cast<size_t>(n_piece), dig);
        } else {
            digest_hex64_from_bytes(&id, sizeof(id), dig);
        }

        if (cb != nullptr) {
            char attrs[64];
            std::snprintf(attrs, sizeof(attrs), "index=%d;tokenId=%d;mode=upstream", i, id);
            cb(OMNILLM_LLAMA_EVT_TOKEN_DELTA, dig, attrs, user_data);
        }
        completed++;

        llama_batch batch = llama_batch_get_one(
            const_cast<llama_token*>(&id), 1);
        if (llama_decode(ctx, batch) != 0) {
            return OMNILLM_LLAMA_GENERATE_FAILED;
        }
    }

    if (cb != nullptr) {
        char usage[80];
        std::snprintf(
            usage,
            sizeof(usage),
            "promptTokens=%d;completionTokens=%d",
            n_tok,
            completed);
        cb(OMNILLM_LLAMA_EVT_USAGE, nullptr, usage, user_data);
        cb(OMNILLM_LLAMA_EVT_STOP, nullptr, "stopReason=COMPLETED", user_data);
    }
    *out_prompt_tokens = n_tok;
    *out_completion_tokens = completed;
    copy_cstr(out_stop_reason, out_stop_reason_cap, "COMPLETED");
    return OMNILLM_LLAMA_OK;
}

void upstream_close_session(SessionHandle* session) {
    if (session == nullptr) return;
    if (session->upstream_sampler != nullptr) {
        llama_sampler_free(static_cast<llama_sampler*>(session->upstream_sampler));
        session->upstream_sampler = nullptr;
    }
    if (session->upstream_ctx != nullptr) {
        llama_free(static_cast<llama_context*>(session->upstream_ctx));
        session->upstream_ctx = nullptr;
    }
}

void upstream_unload_model(ModelHandle* model) {
    if (model == nullptr) return;
    if (model->upstream_model != nullptr) {
        llama_model_free(static_cast<llama_model*>(model->upstream_model));
        model->upstream_model = nullptr;
    }
}

}  // namespace omnillm

#else  // !OMNILLM_HAS_LLAMA_CPP

namespace omnillm {

bool upstream_linked() { return false; }

int32_t upstream_load_model_path(
    const char* /*path*/,
    int32_t /*n_gpu_layers*/,
    ModelHandle* /*out*/) {
    return OMNILLM_LLAMA_NOT_AVAILABLE;
}

int32_t upstream_load_model_fd(
    int32_t /*fd*/,
    int32_t /*n_gpu_layers*/,
    ModelHandle* /*out*/) {
    return OMNILLM_LLAMA_NOT_AVAILABLE;
}

int32_t upstream_create_session(
    const ModelHandle& /*model*/,
    int32_t /*n_ctx*/,
    int64_t /*seed*/,
    bool /*has_seed*/,
    int32_t /*n_threads*/,
    SessionHandle* /*out*/) {
    return OMNILLM_LLAMA_NOT_AVAILABLE;
}

int32_t upstream_generate(
    SessionHandle* /*session*/,
    const ModelHandle& /*model*/,
    const char* /*prompt_text*/,
    int32_t /*max_tokens*/,
    float /*temperature*/,
    bool /*has_temperature*/,
    float /*top_p*/,
    bool /*has_top_p*/,
    int32_t /*top_k*/,
    bool /*has_top_k*/,
    const int32_t* /*cancel_flag*/,
    const char* /*operation_token*/,
    bool (* /*is_op_cancelled*/)(const char* op),
    omnillm_llama_stream_cb /*cb*/,
    void* /*user_data*/,
    int32_t* /*out_prompt_tokens*/,
    int32_t* /*out_completion_tokens*/,
    char* /*out_stop_reason*/,
    size_t /*out_stop_reason_cap*/) {
    return OMNILLM_LLAMA_NOT_AVAILABLE;
}

void upstream_close_session(SessionHandle* /*session*/) {}
void upstream_unload_model(ModelHandle* /*model*/) {}

}  // namespace omnillm

#endif  // OMNILLM_HAS_LLAMA_CPP
