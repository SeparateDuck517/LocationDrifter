cmake_minimum_required(VERSION 3.18.1)
project("locationdrifter_sensor")

find_package(shadowhook REQUIRED CONFIG)

add_library(
    locationdrifter_sensor
    SHARED
    sensor_step_hook.cpp
)

target_compile_features(locationdrifter_sensor PRIVATE cxx_std_17)

target_link_libraries(
    locationdrifter_sensor
    shadowhook::shadowhook
    log
)
