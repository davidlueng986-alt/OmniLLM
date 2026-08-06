/**
 * EXPERIMENTAL_FIXTURE backend — real C++ tokenize + generate loop.
 *
 * Not GGUF inference. Labeled EXPERIMENTAL_FIXTURE; must stay NOT_SUPPORTED /
 * UNQUALIFIED in capability matrix / qualification YAML. Used for packaging,
 * JNI, Registry attach, and cooperative-cancel path proof without a model file.
 */

#include "omnillm_backend.h"

#include <cstdio>
#include <cstring>
#include <string>

namespace omnillm {

void copy_cstr(char* out, size_t cap, const char* src) {
    if (out == nullptr || cap == 0) return;
    if (src == nullptr) {
        out[0] = '\0';
        return;
    }
    std::snprintf(out, cap, "%s", src);
}

void digest_hex64_from_bytes(const void* data, size_t len, char out_hex[65]) {
    // Deterministic 256-bit-shaped hex via split FNV-1a style mixing (not crypto).
    // Wire consumers treat this as an opaque payload digest only.
    const uint8_t* p = static_cast<const uint8_t*>(data);
    uint64_t h[4] = {
        0xcbf29ce484222325ULL,
        0x100000001b3ULL ^ 0x9e3779b97f4a7c15ULL,
        0x84222325cbf29ce4ULL,
        0x243f6a8885a308d3ULL,
    };
    for (size_t i = 0; i < len; ++i) {
        h[i & 3] ^= p[i];
        h[i & 3] *= 0x100000001b3ULL;
        h[(i + 1) & 3] ^= h[i & 3] >> 17;
    }
    h[0] ^= static_cast<uint64_t>(len) * 0x9e3779b97f4a7c15ULL;
    for (int i = 0; i < 4; ++i) {
        for (int b = 0; b < 8; ++b) {
            const int shift = 56 - b * 8;
            const unsigned v = static_cast<unsigned>((h[i] >> shift) & 0xffu);
            std::snprintf(out_hex + i * 16 + b * 2, 3, "%02x", v);
        }
    }
    out_hex[64] = '\0';
}

std::vector<int32_t> fixture_tokenize(const char* prompt_text) {
    std::vector<int32_t> tokens;
    if (prompt_text == nullptr || prompt_text[0] == '\0') {
        tokens.push_back(1);  // BOS-like
        return tokens;
    }
    tokens.push_back(1);
    // Byte-level pseudo tokens (1..255) — exercises tokenize path deterministically.
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(prompt_text);
         *p != 0; ++p) {
        tokens.push_back(static_cast<int32_t>((*p % 255) + 1));
        if (tokens.size() >= 512) break;
    }
    return tokens;
}

int32_t fixture_create_session(
    const ModelHandle& model,
    int32_t n_ctx,
    int64_t seed,
    bool has_seed,
    SessionHandle* out) {
    if (out == nullptr || n_ctx <= 0) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }
    out->kind = ModelKind::ExperimentalFixture;
    out->n_ctx = n_ctx;
    out->seed = seed;
    out->has_seed = has_seed;
    out->upstream_ctx = nullptr;
    out->upstream_sampler = nullptr;
    out->prompt_tokens.clear();
    out->n_past = 0;
    (void)model;
    return OMNILLM_LLAMA_OK;
}

static bool cancelled(
    const int32_t* cancel_flag,
    const char* operation_token,
    bool (*is_op_cancelled)(const char* op)) {
    if (cancel_flag != nullptr && *cancel_flag != 0) return true;
    if (is_op_cancelled != nullptr && operation_token != nullptr) {
        return is_op_cancelled(operation_token);
    }
    return false;
}

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
    size_t out_stop_reason_cap) {
    if (session == nullptr || max_tokens <= 0 ||
        out_prompt_tokens == nullptr || out_completion_tokens == nullptr ||
        out_stop_reason == nullptr || out_stop_reason_cap == 0) {
        return OMNILLM_LLAMA_INVALID_ARGUMENT;
    }

    // Tokenize (real C++ step — not Kotlin stub).
    session->prompt_tokens = fixture_tokenize(prompt_text);
    if (session->prompt_tokens.empty()) {
        return OMNILLM_LLAMA_TOKENIZE_FAILED;
    }
    const int32_t n_prompt = static_cast<int32_t>(session->prompt_tokens.size());
    if (n_prompt > session->n_ctx) {
        return OMNILLM_LLAMA_RESOURCE_EXHAUSTED;
    }
    session->n_past = n_prompt;

    if (cb != nullptr) {
        char meta[192];
        std::snprintf(
            meta,
            sizeof(meta),
            "library=%s;mode=%s;native=true;promptTokens=%d",
            OMNILLM_LLAMA_LABEL,
            OMNILLM_LLAMA_FIXTURE_TOKEN,
            n_prompt);
        cb(OMNILLM_LLAMA_EVT_METADATA, nullptr, meta, user_data);
    }

    // Decode loop: emit deterministic token deltas with cooperative cancel.
    int32_t completed = 0;
    const int32_t emit_n = max_tokens;
    for (int32_t i = 0; i < emit_n; ++i) {
        if (cancelled(cancel_flag, operation_token, is_op_cancelled)) {
            if (cb != nullptr) {
                cb(OMNILLM_LLAMA_EVT_STOP, nullptr, "stopReason=CANCELLED", user_data);
            }
            *out_prompt_tokens = n_prompt;
            *out_completion_tokens = completed;
            copy_cstr(out_stop_reason, out_stop_reason_cap, "CANCELLED");
            return OMNILLM_LLAMA_CANCELLED;
        }

        // Pseudo next-token from prompt mix + index (deterministic).
        int32_t tok = 1000 + (i * 17);
        if (!session->prompt_tokens.empty()) {
            tok ^= session->prompt_tokens[
                static_cast<size_t>(i) % session->prompt_tokens.size()];
        }
        char dig[65];
        digest_hex64_from_bytes(&tok, sizeof(tok), dig);

        if (cb != nullptr) {
            char attrs[64];
            std::snprintf(
                attrs,
                sizeof(attrs),
                "index=%d;tokenId=%d;mode=%s",
                i,
                tok,
                OMNILLM_LLAMA_FIXTURE_TOKEN);
            cb(OMNILLM_LLAMA_EVT_TOKEN_DELTA, dig, attrs, user_data);
        }
        completed++;
        session->n_past++;
    }

    if (cb != nullptr) {
        char usage[80];
        std::snprintf(
            usage,
            sizeof(usage),
            "promptTokens=%d;completionTokens=%d",
            n_prompt,
            completed);
        cb(OMNILLM_LLAMA_EVT_USAGE, nullptr, usage, user_data);
        cb(OMNILLM_LLAMA_EVT_STOP, nullptr, "stopReason=COMPLETED", user_data);
    }
    *out_prompt_tokens = n_prompt;
    *out_completion_tokens = completed;
    copy_cstr(out_stop_reason, out_stop_reason_cap, "COMPLETED");
    return OMNILLM_LLAMA_OK;
}

void fixture_close_session(SessionHandle* session) {
    if (session == nullptr) return;
    session->prompt_tokens.clear();
    session->n_past = 0;
}

}  // namespace omnillm
