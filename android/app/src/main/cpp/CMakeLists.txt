cmake_minimum_required(VERSION 3.22.1)
project("telepad")

add_library(
        telepad
        SHARED
        udp_sender.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        telepad
        ${log-lib})
