# CMakeLists.txt for SR Native Library
cmake_minimum_required(VERSION 3.22.1)
project("sr_native")

# C++ Standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find required Android libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# Source files
add_library(sr_native SHARED
    native-lib.cpp
    sr_engine.cpp
    jni_utils.cpp
)

# Include directories
target_include_directories(sr_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link libraries
target_link_libraries(sr_native
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)