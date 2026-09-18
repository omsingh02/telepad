cmake_minimum_required(VERSION 3.21)
project(telepad-server C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(MSVC)
    add_compile_options(/W4 /wd4100 /wd4068)
    add_compile_definitions(_CRT_SECURE_NO_WARNINGS WIN32_LEAN_AND_MEAN)
else()
    add_compile_options(-Wall -Wextra -Wno-unused-parameter)
endif()

# Vendored noise-c (clone from github.com/rweather/noise-c).
add_subdirectory(third_party/noise-c)

add_executable(telepad-server
    src/main.c
    src/noise_session.c
    src/pairing_store.c
)

if(WIN32)
    target_sources(telepad-server PRIVATE
        src/input_windows.c
        src/now_playing_windows.c
        src/clipboard_windows.c
    )
    target_link_libraries(telepad-server PRIVATE
        ws2_32 user32 shell32 advapi32 ole32 runtimeobject windowsapp
    )
endif()

target_include_directories(telepad-server PRIVATE
    include
    third_party/noise-c/include
)

target_link_libraries(telepad-server PRIVATE noiseprotocol)

# ── Tests ────────────────────────────────────────────────────────────
enable_testing()
add_executable(test_protocol tests/test_protocol.c)
target_include_directories(test_protocol PRIVATE include)
add_test(NAME protocol_layout COMMAND test_protocol)
