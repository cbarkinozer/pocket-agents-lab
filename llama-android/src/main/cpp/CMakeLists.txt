cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
set(OFFICIAL_BINDING "${LLAMA_SRC}/examples/llama.android/lib/src/main/cpp")

# Use upstream's official JNI binding, reducing its default 8192-token context
# to 1024 for the RAM-constrained Galaxy A32 proof of concept.
file(READ "${OFFICIAL_BINDING}/ai_chat.cpp" AI_CHAT_SOURCE)
string(REPLACE
    "constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;"
    "constexpr int   DEFAULT_CONTEXT_SIZE    = 1024;"
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}"
)
# The official Android example formats messages through the legacy template path,
# which cannot pass template kwargs. Qwen3.5 consequently enters thinking mode and
# spends the entire small routing budget inside <think>. Use the model's Jinja
# template and explicitly disable thinking for this constrained agent workload.
# Some Qwen templates throw when Jinja is applied to a system-only partial
# conversation, so keep non-user turns on the legacy formatter and never allow a
# template exception to escape across JNI (which would abort the Android process).
set(NO_THINK_FORMATTER [=[
static std::string chat_format_single_no_thinking(
        const common_chat_templates * tmpls,
        const std::vector<common_chat_msg> & past_msg,
        const common_chat_msg & new_msg,
        bool add_ass) {
    if (!add_ass) {
        return common_chat_format_single(tmpls, past_msg, new_msg, false, false);
    }

    try {
        // Reconstruct the exact prefix already decoded by the legacy Android
        // binding, then render the complete user turn with Jinja kwargs.
        std::string formatted_past;
        std::vector<common_chat_msg> seen;
        for (const auto & msg : past_msg) {
            formatted_past += common_chat_format_single(tmpls, seen, msg, false, false);
            seen.push_back(msg);
        }

        common_chat_templates_inputs inputs;
        inputs.use_jinja = true;
        inputs.enable_thinking = false;
        inputs.messages = past_msg;
        inputs.messages.push_back(new_msg);
        inputs.add_generation_prompt = true;
        const auto formatted_all = common_chat_templates_apply(tmpls, inputs).prompt;
        if (formatted_all.compare(0, formatted_past.size(), formatted_past) != 0) {
            throw std::runtime_error("Jinja and decoded legacy chat prefixes differ");
        }
        return formatted_all.substr(formatted_past.size());
    } catch (const std::exception & error) {
        LOGw("%s: no-thinking Jinja unavailable; using safe legacy template: %s",
             __func__, error.what());
    } catch (...) {
        LOGw("%s: no-thinking Jinja failed; using safe legacy template", __func__);
    }
    return common_chat_format_single(tmpls, past_msg, new_msg, true, false);
}

]=])
string(REPLACE
    "static std::string chat_add_and_format(const std::string &role, const std::string &content) {"
    "${NO_THINK_FORMATTER}static std::string chat_add_and_format(const std::string &role, const std::string &content) {"
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}"
)
string(REPLACE
    "auto formatted = common_chat_format_single(\n            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);"
    "auto formatted = chat_format_single_no_thinking(\n            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER);"
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}"
)
# The upstream sample shifts a full context indefinitely. Hybrid recurrent
# models (observed with Qwen3.5) can abort inside llama_memory_hybrid::seq_add.
# Stop generation at the boundary; Kotlin performs one fresh-context repair.
string(REPLACE
    "LOGw(\"%s: Context full! Shifting...\", __func__);\n        shift_context();"
    "LOGw(\"%s: Context full; stopping safely before shift\", __func__);\n        return nullptr;"
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}"
)
# Add the one lifecycle operation the official sample does not expose: return to
# the already-decoded system-prompt boundary without unloading model weights.
string(APPEND AI_CHAT_SOURCE [=[

static constexpr const char * POCKET_ROUTING_GRAMMAR = R"grammar(
root ::= answer | device | battery | storage | media-info | files | notes | save-note | health
answer ::= "{\"action\":\"answer\",\"text\":\"\"}"
device ::= "{\"action\":\"tool\",\"name\":\"get_device_info\",\"args\":{}}"
battery ::= "{\"action\":\"tool\",\"name\":\"get_battery_info\",\"args\":{}}"
storage ::= "{\"action\":\"tool\",\"name\":\"get_storage_info\",\"args\":{}}"
media-info ::= "{\"action\":\"tool\",\"name\":\"get_media_info\",\"args\":{}}"
files ::= "{\"action\":\"tool\",\"name\":\"search_local_files\",\"args\":{}}"
notes ::= "{\"action\":\"tool\",\"name\":\"search_notes\",\"args\":{}}"
save-note ::= "{\"action\":\"tool\",\"name\":\"save_note\",\"args\":{}}"
health ::= "{\"action\":\"workflow\",\"name\":\"phone_health_check\",\"args\":{}}"
)grammar";

static constexpr const char * POCKET_SCOPE_GRAMMAR = R"grammar(
root ::= answer | live
answer ::= "{\"scope\":\"answer\"}"
live ::= "{\"scope\":\"live_device\"}"
)grammar";

static constexpr const char * POCKET_AGENT_ROUTING_GRAMMAR = R"grammar(
root ::= answer | device | battery | storage | media-info | files | notes | save-note | health | optimize | open-storage | open-battery | open-camera | open-wallpaper | review-background | launch-app | spotify-search | youtube-search | telegram-draft | media-toggle | media-next | media-previous | open-media-access | set-timer | set-alarm | calendar-event
answer ::= "{\"action\":\"answer\",\"text\":\"\"}"
device ::= "{\"action\":\"tool\",\"name\":\"get_device_info\",\"args\":{}}"
battery ::= "{\"action\":\"tool\",\"name\":\"get_battery_info\",\"args\":{}}"
storage ::= "{\"action\":\"tool\",\"name\":\"get_storage_info\",\"args\":{}}"
media-info ::= "{\"action\":\"tool\",\"name\":\"get_media_info\",\"args\":{}}"
files ::= "{\"action\":\"tool\",\"name\":\"search_local_files\",\"args\":{}}"
notes ::= "{\"action\":\"tool\",\"name\":\"search_notes\",\"args\":{}}"
save-note ::= "{\"action\":\"tool\",\"name\":\"save_note\",\"args\":{}}"
health ::= "{\"action\":\"workflow\",\"name\":\"phone_health_check\",\"args\":{}}"
optimize ::= "{\"action\":\"workflow\",\"name\":\"phone_optimization_report\",\"args\":{}}"
open-storage ::= "{\"action\":\"propose\",\"name\":\"open_storage_settings\",\"args\":{}}"
open-battery ::= "{\"action\":\"propose\",\"name\":\"open_battery_settings\",\"args\":{}}"
open-camera ::= "{\"action\":\"propose\",\"name\":\"open_camera\",\"args\":{}}"
open-wallpaper ::= "{\"action\":\"propose\",\"name\":\"open_wallpaper_settings\",\"args\":{}}"
review-background ::= "{\"action\":\"propose\",\"name\":\"review_background_apps\",\"args\":{}}"
launch-app ::= "{\"action\":\"propose\",\"name\":\"launch_app\",\"args\":{}}"
spotify-search ::= "{\"action\":\"propose\",\"name\":\"search_spotify\",\"args\":{}}"
youtube-search ::= "{\"action\":\"propose\",\"name\":\"search_youtube\",\"args\":{}}"
telegram-draft ::= "{\"action\":\"propose\",\"name\":\"draft_telegram_message\",\"args\":{}}"
media-toggle ::= "{\"action\":\"propose\",\"name\":\"media_play_pause\",\"args\":{}}"
media-next ::= "{\"action\":\"propose\",\"name\":\"media_next\",\"args\":{}}"
media-previous ::= "{\"action\":\"propose\",\"name\":\"media_previous\",\"args\":{}}"
open-media-access ::= "{\"action\":\"propose\",\"name\":\"open_media_access\",\"args\":{}}"
set-timer ::= "{\"action\":\"propose\",\"name\":\"set_timer\",\"args\":{}}"
set-alarm ::= "{\"action\":\"propose\",\"name\":\"set_alarm\",\"args\":{}}"
calendar-event ::= "{\"action\":\"propose\",\"name\":\"create_calendar_event\",\"args\":{}}"
)grammar";

static constexpr const char * POCKET_LIVE_GRAMMAR = R"grammar(
root ::= device | battery | storage | health
device ::= "{\"action\":\"tool\",\"name\":\"get_device_info\",\"args\":{}}"
battery ::= "{\"action\":\"tool\",\"name\":\"get_battery_info\",\"args\":{}}"
storage ::= "{\"action\":\"tool\",\"name\":\"get_storage_info\",\"args\":{}}"
health ::= "{\"action\":\"workflow\",\"name\":\"phone_health_check\",\"args\":{}}"
)grammar";

static constexpr const char * POCKET_NOTE_ROUTING_GRAMMAR = R"grammar(
root ::= save-note | search-notes
save-note ::= "{\"action\":\"tool\",\"name\":\"save_note\",\"args\":{}}"
search-notes ::= "{\"action\":\"tool\",\"name\":\"search_notes\",\"args\":{}}"
)grammar";

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_RoutingGrammar_setModeNative(JNIEnv * env, jclass, jint mode) {
    if (g_model == nullptr) {
        return;
    }
    common_sampler_free(g_sampler);
    g_sampler = nullptr;
    common_params_sampling params;
    params.temp = DEFAULT_SAMPLER_TEMP;
    try {
        if (mode != 0) {
            const char * grammar = mode == 2 ? POCKET_SCOPE_GRAMMAR
                    : mode == 3 ? POCKET_LIVE_GRAMMAR
                    : mode == 4 ? POCKET_AGENT_ROUTING_GRAMMAR
                    : mode == 5 ? POCKET_NOTE_ROUTING_GRAMMAR
                    : POCKET_ROUTING_GRAMMAR;
            params.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
        }
        g_sampler = common_sampler_init(g_model, params);
    } catch (const std::exception & error) {
        common_params_sampling fallback;
        fallback.temp = DEFAULT_SAMPLER_TEMP;
        g_sampler = common_sampler_init(g_model, fallback);
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception != nullptr) {
            env->ThrowNew(exception, error.what());
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_ConversationReset_resetNative(JNIEnv *, jclass) {
    if (g_context == nullptr) {
        return;
    }
    // Full memory reset is required for hybrid recurrent models such as LFM2;
    // truncating only KV positions leaves their recurrent state out of sync.
    reset_long_term_states();
    reset_short_term_states();
}
]=])
string(APPEND AI_CHAT_SOURCE [=[

#include "expert_cache.h"
#include "expert_index.h"
#include <atomic>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <sys/mman.h>
#include <unistd.h>
#include "llama-context.h"
#include "llama-model.h"
#include "ggml-backend.h"

static std::unique_ptr<ExpertCache> g_expert_cache;
static ExpertIndex g_expert_index;
static std::atomic<bool> g_expert_telemetry_enabled { false };
static std::atomic<uint64_t> g_expert_route_events { 0 };
static std::atomic<uint64_t> g_expert_selected_count { 0 };
static std::atomic<uint64_t> g_expert_prefetch_count { 0 };
static std::atomic<bool> g_expert_predictor_enabled { false };
static std::atomic<uint64_t> g_expert_predictor_requests { 0 };
static std::atomic<uint64_t> g_expert_direct_warm_requests { 0 };
static std::atomic<uint64_t> g_expert_direct_drop_requests { 0 };
static std::atomic<bool> g_expert_page_evict_enabled { false };
static std::mutex g_expert_predictor_mutex;
static std::unordered_map<uint32_t, std::vector<uint32_t>> g_expert_last_selection;
static std::unordered_set<uint32_t> g_expert_cold_layers;

// Warm the actual tensor mapping used by ggml. Unlike the duplicate pread
// cache, this operates on llama.cpp's loaded tensor pointer and therefore
// directly influences the kernel file-page residency without changing model
// values or dispatch semantics. It is best-effort: non-mmap buffers simply
// return an error from madvise and remain untouched.
static bool pocket_warm_loaded_expert(uint32_t layer, uint32_t expert) {
    if (g_model == nullptr || expert >= g_expert_index.expert_count()) {
        return false;
    }
    auto * base = dynamic_cast<llama_model_base *>(g_model);
    if (base == nullptr) {
        return false;
    }
    const long raw_page = ::sysconf(_SC_PAGESIZE);
    const uint64_t page = raw_page > 0 ? static_cast<uint64_t>(raw_page) : 4096;
    bool warmed = false;
    for (const auto & info : g_expert_index.tensors()) {
        if (info.layer != layer) {
            continue;
        }
        const std::string prefix = "blk." + std::to_string(layer) + ".ffn_" + info.projection + "_exps.weight";
        for (const auto & named : base->tensors_by_name) {
            if (named.first != prefix || named.second == nullptr || named.second->data == nullptr) {
                continue;
            }
            const uint64_t relative = static_cast<uint64_t>(expert) * info.expert_bytes;
            if (relative > ggml_nbytes(named.second) || info.expert_bytes > ggml_nbytes(named.second) - relative) {
                continue;
            }
            auto * address = static_cast<uint8_t *>(named.second->data) + relative;
            const uintptr_t raw = reinterpret_cast<uintptr_t>(address);
            const uintptr_t aligned = raw - (raw % page);
            const size_t span = static_cast<size_t>(info.expert_bytes + (raw - aligned));
            if (::madvise(reinterpret_cast<void *>(aligned), span, MADV_WILLNEED) == 0) {
                warmed = true;
            }
        }
    }
    if (warmed) {
        g_expert_direct_warm_requests.fetch_add(1, std::memory_order_relaxed);
    }
    return warmed;
}

// Best-effort release of clean pages for an expert that is no longer in the
// previous route. This preserves the mmap mapping and tensor values; Android
// may fault the pages back from the GGUF file when they are needed again.
static bool pocket_drop_loaded_expert(uint32_t layer, uint32_t expert) {
    if (g_model == nullptr || expert >= g_expert_index.expert_count()) {
        return false;
    }
    auto * base = dynamic_cast<llama_model_base *>(g_model);
    if (base == nullptr) {
        return false;
    }
    const long raw_page = ::sysconf(_SC_PAGESIZE);
    const uint64_t page = raw_page > 0 ? static_cast<uint64_t>(raw_page) : 4096;
    bool dropped = false;
    for (const auto & info : g_expert_index.tensors()) {
        if (info.layer != layer) continue;
        const std::string name = "blk." + std::to_string(layer) + ".ffn_" + info.projection + "_exps.weight";
        for (const auto & named : base->tensors_by_name) {
            if (named.first != name || named.second == nullptr || named.second->data == nullptr) continue;
            const uint64_t relative = static_cast<uint64_t>(expert) * info.expert_bytes;
            if (relative > ggml_nbytes(named.second) || info.expert_bytes > ggml_nbytes(named.second) - relative) continue;
            auto * address = static_cast<uint8_t *>(named.second->data) + relative;
            const uintptr_t raw = reinterpret_cast<uintptr_t>(address);
            const uintptr_t aligned = raw - (raw % page);
            const size_t span = static_cast<size_t>(info.expert_bytes + (raw - aligned));
            if (::madvise(reinterpret_cast<void *>(aligned), span, MADV_DONTNEED) == 0) dropped = true;
        }
    }
    if (dropped) g_expert_direct_drop_requests.fetch_add(1, std::memory_order_relaxed);
    return dropped;
}

static uint64_t pocket_drop_all_loaded_expert_pages() {
    if (g_model == nullptr) return 0;
    auto * base = dynamic_cast<llama_model_base *>(g_model);
    if (base == nullptr) return 0;
    uint64_t dropped = 0;
    const long raw_page = ::sysconf(_SC_PAGESIZE);
    const uint64_t page = raw_page > 0 ? static_cast<uint64_t>(raw_page) : 4096;
    for (const auto & named : base->tensors_by_name) {
        const std::string & name = named.first;
        if (name.find(".ffn_gate_exps.weight") == std::string::npos
                && name.find(".ffn_down_exps.weight") == std::string::npos
                && name.find(".ffn_up_exps.weight") == std::string::npos) continue;
        if (named.second == nullptr || named.second->data == nullptr) continue;
        const uintptr_t raw = reinterpret_cast<uintptr_t>(named.second->data);
        const uintptr_t aligned = raw - (raw % page);
        const size_t span = static_cast<size_t>(ggml_nbytes(named.second) + (raw - aligned));
        if (::madvise(reinterpret_cast<void *>(aligned), span, MADV_DONTNEED) == 0) ++dropped;
    }
    return dropped;
}

static std::string pocket_expert_residency_json() {
    uint64_t resident = 0;
    uint64_t mapped = 0;
    uint64_t tensors = 0;
    uint64_t selected_resident = 0;
    uint64_t selected_bytes = 0;
    std::unordered_set<uint64_t> selected_keys;
    {
        std::lock_guard<std::mutex> lock(g_expert_predictor_mutex);
        for (const auto & entry : g_expert_last_selection) {
            for (uint32_t expert : entry.second) {
                selected_keys.insert((static_cast<uint64_t>(entry.first) << 32) | expert);
            }
        }
    }
    if (g_model == nullptr) return "{\"residentBytes\":0,\"mappedBytes\":0,\"residentRatio\":0,\"selectedResidentBytes\":0,\"selectedMappedBytes\":0,\"tensorCount\":0}";
    auto * base = dynamic_cast<llama_model_base *>(g_model);
    if (base == nullptr) return "{\"residentBytes\":0,\"mappedBytes\":0,\"residentRatio\":0,\"selectedResidentBytes\":0,\"selectedMappedBytes\":0,\"tensorCount\":0}";
    const long raw_page = ::sysconf(_SC_PAGESIZE);
    const size_t page = raw_page > 0 ? static_cast<size_t>(raw_page) : 4096;
    for (const auto & named : base->tensors_by_name) {
        const std::string & name = named.first;
        if (name.find(".ffn_gate_exps.weight") == std::string::npos
                && name.find(".ffn_down_exps.weight") == std::string::npos
                && name.find(".ffn_up_exps.weight") == std::string::npos) continue;
        if (named.second == nullptr || named.second->data == nullptr || ggml_nbytes(named.second) == 0) continue;
        const uintptr_t raw = reinterpret_cast<uintptr_t>(named.second->data);
        const uintptr_t aligned = raw - (raw % page);
        const size_t span = static_cast<size_t>(ggml_nbytes(named.second) + (raw - aligned));
        const size_t pages = (span + page - 1) / page;
        std::vector<unsigned char> vec(pages);
        if (::mincore(reinterpret_cast<void *>(aligned), pages * page, vec.data()) != 0) continue;
        ++tensors;
        mapped += static_cast<uint64_t>(ggml_nbytes(named.second));
        for (unsigned char value : vec) if (value & 1) resident += page;
        const size_t expert_count = g_expert_index.expert_count();
        if (expert_count > 0 && ggml_nbytes(named.second) % expert_count == 0) {
            const uint64_t expert_bytes = ggml_nbytes(named.second) / expert_count;
            const uint32_t layer = static_cast<uint32_t>(std::strtoul(name.c_str() + 4, nullptr, 10));
            for (uint32_t expert = 0; expert < expert_count; ++expert) {
                if (selected_keys.find((static_cast<uint64_t>(layer) << 32) | expert) == selected_keys.end()) continue;
                auto * slice = static_cast<uint8_t *>(named.second->data) + expert * expert_bytes;
                const uintptr_t slice_raw = reinterpret_cast<uintptr_t>(slice);
                const uintptr_t slice_aligned = slice_raw - (slice_raw % page);
                const size_t slice_span = static_cast<size_t>(expert_bytes + (slice_raw - slice_aligned));
                std::vector<unsigned char> slice_vec((slice_span + page - 1) / page);
                if (::mincore(reinterpret_cast<void *>(slice_aligned), slice_vec.size() * page, slice_vec.data()) != 0) continue;
                selected_bytes += expert_bytes;
                for (unsigned char value : slice_vec) if (value & 1) selected_resident += page;
            }
        }
    }
    if (resident > mapped) resident = mapped;
    if (selected_resident > selected_bytes) selected_resident = selected_bytes;
    const double ratio = mapped == 0 ? 0.0 : static_cast<double>(resident) / static_cast<double>(mapped);
    return "{\"residentBytes\":" + std::to_string(resident)
            + ",\"mappedBytes\":" + std::to_string(mapped)
            + ",\"residentRatio\":" + std::to_string(ratio)
            + ",\"selectedResidentBytes\":" + std::to_string(selected_resident)
            + ",\"selectedMappedBytes\":" + std::to_string(selected_bytes)
            + ",\"tensorCount\":" + std::to_string(tensors) + "}";
}

static bool pocket_prefetch_expert(uint32_t layer, uint32_t expert) {
    if (g_expert_cache == nullptr || expert >= g_expert_index.expert_count()) {
        return false;
    }
    bool queued = false;
    for (const auto & tensor : g_expert_index.tensors()) {
        if (tensor.layer != layer) {
            continue;
        }
        const uint64_t offset = tensor.offset + static_cast<uint64_t>(expert) * tensor.expert_bytes;
        queued = g_expert_cache->prefetch(tensor.layer, expert, offset, tensor.expert_bytes) || queued;
    }
    if (queued) {
        g_expert_prefetch_count.fetch_add(1, std::memory_order_relaxed);
    }
    return queued;
}

static bool pocket_expert_eval_callback(ggml_tensor * tensor, bool ask, void *) {
    const char * name = ggml_get_name(tensor);
    if (name == nullptr || std::strstr(name, "ffn_moe_topk") == nullptr) {
        return false;
    }
    if (ask) {
        return true;
    }
    const size_t bytes = ggml_nbytes(tensor);
    if (bytes == 0 || bytes % sizeof(int32_t) != 0 || tensor->buffer == nullptr) {
        return true;
    }
    std::vector<int32_t> selected(bytes / sizeof(int32_t));
    ggml_backend_tensor_get(tensor, selected.data(), 0, bytes);
    g_expert_route_events.fetch_add(1, std::memory_order_relaxed);
    g_expert_selected_count.fetch_add(selected.size(), std::memory_order_relaxed);
    const char * separator = std::strrchr(name, '-');
    if (separator != nullptr && separator[1] != '\0') {
        try {
            const uint32_t layer = static_cast<uint32_t>(std::stoul(separator + 1));
            if (g_expert_page_evict_enabled.load(std::memory_order_acquire)) {
                bool first_layer_route = false;
                {
                    std::lock_guard<std::mutex> lock(g_expert_predictor_mutex);
                    first_layer_route = g_expert_cold_layers.insert(layer).second;
                }
                if (first_layer_route) {
                    // Establish a deterministic cold residency baseline before
                    // warming this layer's selected expert slices below.
                    pocket_drop_all_loaded_expert_pages();
                }
            }
            std::vector<bool> seen(g_expert_index.expert_count(), false);
            std::vector<uint32_t> current;
            for (const int32_t expert : selected) {
                if (expert >= 0 && static_cast<uint32_t>(expert) < seen.size()
                        && !seen[static_cast<size_t>(expert)]) {
                    seen[static_cast<size_t>(expert)] = true;
                    current.push_back(static_cast<uint32_t>(expert));
                    const bool page_warm = g_expert_cache != nullptr && g_expert_cache->page_warm();
                    if (!page_warm) {
                        pocket_prefetch_expert(layer, static_cast<uint32_t>(expert));
                    }
                    if (page_warm) {
                        pocket_warm_loaded_expert(layer, static_cast<uint32_t>(expert));
                    }
                }
            }
            if (g_expert_predictor_enabled.load(std::memory_order_acquire)) {
                std::vector<uint32_t> previous;
                {
                    std::lock_guard<std::mutex> lock(g_expert_predictor_mutex);
                    previous = g_expert_last_selection[layer];
                    g_expert_last_selection[layer] = current;
                }
                for (const uint32_t expert : previous) {
                    if (g_expert_page_evict_enabled.load(std::memory_order_acquire)
                            && std::find(current.begin(), current.end(), expert) == current.end()) {
                        pocket_drop_loaded_expert(layer, expert);
                    }
                    if (!(g_expert_cache != nullptr && g_expert_cache->page_warm())
                            && pocket_prefetch_expert(layer, expert)) {
                        g_expert_predictor_requests.fetch_add(1, std::memory_order_relaxed);
                    }
                }
            }
        } catch (...) {
            // Telemetry must never abort inference on an unexpected tensor name.
        }
    }
    return true;
}

static void pocket_install_expert_callback() {
    if (g_context == nullptr) {
        return;
    }
    auto & params = const_cast<llama_cparams &>(g_context->get_cparams());
    params.cb_eval = pocket_expert_eval_callback;
    params.cb_eval_user_data = nullptr;
}

static void pocket_install_expert_callback_if_enabled() {
    if (g_expert_telemetry_enabled.load(std::memory_order_acquire)) {
        pocket_install_expert_callback();
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_openNative(JNIEnv * env, jclass, jstring path, jlong budget_bytes) {
    if (path == nullptr || budget_bytes <= 0) {
        return JNI_FALSE;
    }
    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return JNI_FALSE;
    }
    auto cache = std::make_unique<ExpertCache>();
    const bool opened = cache->open(chars, static_cast<uint64_t>(budget_bytes));
    const bool indexed = g_expert_index.open(chars);
    env->ReleaseStringUTFChars(path, chars);
    if (opened) {
        g_expert_cache = std::move(cache);
    } else {
        g_expert_cache.reset();
    }
    if (!indexed) {
        g_expert_index = ExpertIndex();
    }
    return opened ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_setPageWarmNative(JNIEnv *, jclass, jboolean enabled) {
    if (g_expert_cache != nullptr) {
        g_expert_cache->set_page_warm(enabled == JNI_TRUE);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_setPageEvictNative(JNIEnv *, jclass, jboolean enabled) {
    g_expert_page_evict_enabled.store(enabled == JNI_TRUE, std::memory_order_release);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_releaseAllExpertPagesNative(JNIEnv *, jclass) {
    return static_cast<jlong>(pocket_drop_all_loaded_expert_pages());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_expertResidencyJsonNative(JNIEnv * env, jclass) {
    const std::string json = pocket_expert_residency_json();
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_loadNative(
        JNIEnv *, jclass, jint layer, jint expert, jlong offset, jlong length) {
    if (g_expert_cache == nullptr || layer < 0 || expert < 0 || offset < 0 || length <= 0) {
        return JNI_FALSE;
    }
    return g_expert_cache->load(
            static_cast<uint32_t>(layer),
            static_cast<uint32_t>(expert),
            static_cast<uint64_t>(offset),
            static_cast<uint64_t>(length)) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_prefetchNative(
        JNIEnv *, jclass, jint layer, jint expert, jlong offset, jlong length) {
    if (g_expert_cache == nullptr || layer < 0 || expert < 0 || offset < 0 || length <= 0) {
        return JNI_FALSE;
    }
    return g_expert_cache->prefetch(
            static_cast<uint32_t>(layer),
            static_cast<uint32_t>(expert),
            static_cast<uint64_t>(offset),
            static_cast<uint64_t>(length)) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_awaitPrefetchNative(JNIEnv *, jclass, jint timeout_ms) {
    if (g_expert_cache == nullptr || timeout_ms < 0) return JNI_FALSE;
    return g_expert_cache->wait_idle(static_cast<uint32_t>(timeout_ms)) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_prefetchExpertNative(JNIEnv *, jclass, jint layer, jint expert) {
    if (g_expert_cache == nullptr || layer < 0 || expert < 0 ||
            static_cast<uint32_t>(expert) >= g_expert_index.expert_count()) {
        return JNI_FALSE;
    }
    return pocket_prefetch_expert(static_cast<uint32_t>(layer), static_cast<uint32_t>(expert))
            ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_expertIndexNative(JNIEnv * env, jclass) {
    if (g_model == nullptr) {
        return env->NewStringUTF("{}");
    }
    auto * base = dynamic_cast<llama_model_base *>(g_model);
    if (base == nullptr || base->hparams.n_expert == 0) {
        return env->NewStringUTF("{}");
    }

    const uint64_t expert_count = base->hparams.n_expert;
    std::string json = "{\"architecture\":\"" + base->arch_name()
            + "\",\"expertCount\":" + std::to_string(expert_count)
            + ",\"tensors\":[],\"status\":\"metadata_only\"}";
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_indexFileNative(JNIEnv * env, jclass, jstring path) {
    if (path == nullptr) {
        return env->NewStringUTF("{}");
    }
    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return env->NewStringUTF("{}");
    }
    const bool parsed = g_expert_index.open(chars);
    env->ReleaseStringUTFChars(path, chars);
    if (!parsed) {
        return env->NewStringUTF("{}");
    }
    const std::string json = g_expert_index.summary_json();
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_setTelemetryNative(JNIEnv *, jclass, jboolean enabled) {
    g_expert_telemetry_enabled.store(enabled == JNI_TRUE, std::memory_order_release);
    if (enabled == JNI_TRUE) {
        g_expert_route_events.store(0, std::memory_order_relaxed);
        g_expert_selected_count.store(0, std::memory_order_relaxed);
        g_expert_prefetch_count.store(0, std::memory_order_relaxed);
        g_expert_predictor_requests.store(0, std::memory_order_relaxed);
        g_expert_direct_warm_requests.store(0, std::memory_order_relaxed);
        g_expert_direct_drop_requests.store(0, std::memory_order_relaxed);
        {
            std::lock_guard<std::mutex> lock(g_expert_predictor_mutex);
            g_expert_last_selection.clear();
            g_expert_cold_layers.clear();
        }
        pocket_install_expert_callback();
    } else if (g_context != nullptr) {
        auto & params = const_cast<llama_cparams &>(g_context->get_cparams());
        params.cb_eval = nullptr;
        params.cb_eval_user_data = nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_setPredictorNative(JNIEnv *, jclass, jboolean enabled) {
    g_expert_predictor_enabled.store(enabled == JNI_TRUE, std::memory_order_release);
    if (enabled != JNI_TRUE) {
        std::lock_guard<std::mutex> lock(g_expert_predictor_mutex);
        g_expert_last_selection.clear();
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_telemetryJsonNative(JNIEnv * env, jclass) {
    const std::string json = "{\"enabled\":"
            + std::string(g_expert_telemetry_enabled.load(std::memory_order_acquire) ? "true" : "false")
            + ",\"routeEvents\":" + std::to_string(g_expert_route_events.load(std::memory_order_relaxed))
            + ",\"selectedExperts\":" + std::to_string(g_expert_selected_count.load(std::memory_order_relaxed))
            + ",\"prefetchRequests\":" + std::to_string(g_expert_prefetch_count.load(std::memory_order_relaxed))
            + ",\"predictorRequests\":" + std::to_string(g_expert_predictor_requests.load(std::memory_order_relaxed))
            + ",\"directPageWarmRequests\":" + std::to_string(g_expert_direct_warm_requests.load(std::memory_order_relaxed))
            + ",\"directPageDropRequests\":" + std::to_string(g_expert_direct_drop_requests.load(std::memory_order_relaxed)) + "}";
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_statsNative(JNIEnv * env, jclass) {
    const auto stats = g_expert_cache == nullptr ? ExpertCache::Stats {} : g_expert_cache->stats();
    const std::string json = "{\"hits\":" + std::to_string(stats.hits)
            + ",\"misses\":" + std::to_string(stats.misses)
            + ",\"evictions\":" + std::to_string(stats.evictions)
            + ",\"bytesRead\":" + std::to_string(stats.bytes_read)
            + ",\"residentBytes\":" + std::to_string(stats.resident_bytes)
            + ",\"residentEntries\":" + std::to_string(stats.resident_entries)
            + ",\"pageWarm\":" + std::string(g_expert_cache != nullptr && g_expert_cache->page_warm() ? "true" : "false") + "}";
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_clearNative(JNIEnv *, jclass) {
    if (g_expert_cache != nullptr) {
        g_expert_cache->clear();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_ExpertCacheRuntime_closeNative(JNIEnv *, jclass) {
    g_expert_cache.reset();
    g_expert_page_evict_enabled.store(false, std::memory_order_release);
}
]=])
string(PREPEND AI_CHAT_SOURCE [=[
static void pocket_install_expert_callback();
static void pocket_install_expert_callback_if_enabled();
]=])
string(REPLACE
    "g_context = context;"
    "g_context = context;\n    pocket_install_expert_callback_if_enabled();"
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}"
)
set(GENERATED_BINDING "${CMAKE_CURRENT_BINARY_DIR}/ai_chat_pocket.cpp")
file(WRITE "${GENERATED_BINDING}" "${AI_CHAT_SOURCE}")

set(GGML_SYSTEM_ARCH ARM)
set(GGML_CPU_KLEIDIAI OFF)
set(GGML_OPENMP OFF)
add_subdirectory("${LLAMA_SRC}" build-llama)

add_library(ai-chat SHARED "${GENERATED_BINDING}" expert_cache.cpp expert_index.cpp)
target_include_directories(ai-chat PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${OFFICIAL_BINDING}"
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/src"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src"
)
target_link_libraries(ai-chat llama llama-common android log)
