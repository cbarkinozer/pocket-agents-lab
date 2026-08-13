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
