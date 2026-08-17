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
root ::= answer | device | battery | storage | files | notes | save-note | health
answer ::= "{\"action\":\"answer\",\"text\":\"\"}"
device ::= "{\"action\":\"tool\",\"name\":\"get_device_info\",\"args\":{}}"
battery ::= "{\"action\":\"tool\",\"name\":\"get_battery_info\",\"args\":{}}"
storage ::= "{\"action\":\"tool\",\"name\":\"get_storage_info\",\"args\":{}}"
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
root ::= answer | device | battery | storage | files | notes | save-note | health | open-storage | open-battery | set-timer | set-alarm | calendar-event
answer ::= "{\"action\":\"answer\",\"text\":\"\"}"
device ::= "{\"action\":\"tool\",\"name\":\"get_device_info\",\"args\":{}}"
battery ::= "{\"action\":\"tool\",\"name\":\"get_battery_info\",\"args\":{}}"
storage ::= "{\"action\":\"tool\",\"name\":\"get_storage_info\",\"args\":{}}"
files ::= "{\"action\":\"tool\",\"name\":\"search_local_files\",\"args\":{}}"
notes ::= "{\"action\":\"tool\",\"name\":\"search_notes\",\"args\":{}}"
save-note ::= "{\"action\":\"tool\",\"name\":\"save_note\",\"args\":{}}"
health ::= "{\"action\":\"workflow\",\"name\":\"phone_health_check\",\"args\":{}}"
open-storage ::= "{\"action\":\"propose\",\"name\":\"open_storage_settings\",\"args\":{}}"
open-battery ::= "{\"action\":\"propose\",\"name\":\"open_battery_settings\",\"args\":{}}"
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
set(GENERATED_BINDING "${CMAKE_CURRENT_BINARY_DIR}/ai_chat_pocket.cpp")
file(WRITE "${GENERATED_BINDING}" "${AI_CHAT_SOURCE}")

set(GGML_SYSTEM_ARCH ARM)
set(GGML_CPU_KLEIDIAI OFF)
set(GGML_OPENMP OFF)
add_subdirectory("${LLAMA_SRC}" build-llama)

add_library(ai-chat SHARED "${GENERATED_BINDING}")
target_include_directories(ai-chat PRIVATE
    "${OFFICIAL_BINDING}"
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src"
)
target_link_libraries(ai-chat llama llama-common android log)
