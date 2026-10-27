cmake_minimum_required(VERSION 3.22)
project(s24jxl LANGUAGES C CXX)

set(LIBJXL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libjxl" CACHE PATH
    "Read-only libjxl v0.11.1 source tree, including its submodules")
if(NOT EXISTS "${LIBJXL_DIR}/lib/include/jxl/encode.h")
    message(FATAL_ERROR "libjxl not found: run git submodule update --init --recursive, or set libjxl.dir in local.properties (LIBJXL_DIR for host CMake)")
endif()
if(ANDROID AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "s24jxl supports only arm64-v8a on Android")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(PROVISION_DEPENDENCIES OFF CACHE BOOL "" FORCE)
foreach(feature TOOLS DEVTOOLS FUZZERS BENCHMARK EXAMPLES MANPAGES DOXYGEN JNI
        JPEGLI JPEGLI_LIBJPEG SJPEG OPENEXR VIEWERS PLUGINS TCMALLOC COVERAGE
        TRANSCODE_JPEG BOXES)
    set(JPEGXL_ENABLE_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
set(JPEGXL_BUNDLE_LIBPNG OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_SKCMS ON CACHE BOOL "" FORCE)
# libjxl 0.11.1 marks its experimental SVE/RVV paths as not passing tests.
# Keep its supported NEON dispatch paths and tune scheduling for Snapdragon 8 Gen 3.
set(JPEGXL_ENABLE_SIZELESS_VECTORS OFF CACHE BOOL "" FORCE)
foreach(codec PNG JPEG GIF OpenEXR)
    set(CMAKE_DISABLE_FIND_PACKAGE_${codec} TRUE)
endforeach()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(MSVC)
    add_compile_options($<$<CONFIG:Release>:/O2>)
else()
    add_compile_options($<$<CONFIG:Release>:-O3> -ffunction-sections -fdata-sections)
endif()
if(ANDROID)
    add_compile_options(-mtune=cortex-x4)
endif()

# All generated files stay in this build tree. Do not provision or modify sources.
add_subdirectory("${LIBJXL_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/libjxl" EXCLUDE_FROM_ALL)

add_library(s24jxl SHARED jxl_jni.cc)
add_library(s24lumdir SHARED lumdir_median.cc lumdir_directional.cc)
if(MSVC)
    target_compile_options(s24lumdir PRIVATE /fp:strict)
else()
    target_compile_options(s24lumdir PRIVATE -ffp-contract=off -fno-fast-math)
endif()
target_link_libraries(s24jxl PRIVATE jxl)
set_target_properties(s24jxl PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
if(ANDROID)
    target_link_options(s24jxl PRIVATE
        "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL"
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/s24jxl.map")
    set_property(TARGET s24jxl APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/s24jxl.map")
else()
    # Also build the identical JNI implementation as a host DLL for verification.
    find_package(JNI REQUIRED)
    target_include_directories(s24jxl PRIVATE ${JNI_INCLUDE_DIRS})
    target_include_directories(s24lumdir PRIVATE ${JNI_INCLUDE_DIRS})
endif()
