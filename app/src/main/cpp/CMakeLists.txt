cmake_minimum_required(VERSION 3.22.1)

project("hyperplay")

message(STATUS "Configuring HyperPlay Native Engine...")
message(STATUS "Android ABI: ${ANDROID_ABI}")

# Set paths
set(CMAKE_CXX_STANDARD 17)

# Add Oboe
message(STATUS "Adding Oboe from: ${CMAKE_CURRENT_SOURCE_DIR}/external/oboe")
add_subdirectory(external/oboe)

# Add Resonance Audio
set(RA_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/resonance_audio)
set(PROJECT_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio)
set(EIGEN3_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/third_party/eigen)
set(PFFFT_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/third_party/pffft)
set(BUILD_RESONANCE_AUDIO_API ON CACHE BOOL "" FORCE)

include(${RA_SOURCE_DIR}/CMakeLists.txt)

add_library(hyperplay SHARED
        native-lib.cpp
        engine/AudioEngine.cpp
        engine/AudioProcessor.cpp
        engine/FFmpegDecoder.cpp
        engine/SoxrResampler.cpp
        engine/UsbNativeStreamer.cpp
        $<TARGET_OBJECTS:ResonanceAudioObj>
        $<TARGET_OBJECTS:SadieHrtfsObj>
        $<TARGET_OBJECTS:PffftObj>
)

# Find libraries
find_library(log-lib log)
find_library(android-lib android)

# Set the location of FFmpeg libraries
set(FFMPEG_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

message(STATUS "Checking for FFmpeg libraries in: ${FFMPEG_LIBS_DIR}")

if(EXISTS ${FFMPEG_LIBS_DIR}/libavcodec.so)
    message(STATUS "FFmpeg libraries found for ${ANDROID_ABI}")
    
    # Import FFmpeg libraries
    add_library(avcodec SHARED IMPORTED)
    set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIBS_DIR}/libavcodec.so)

    add_library(avformat SHARED IMPORTED)
    set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIBS_DIR}/libavformat.so)

    add_library(avutil SHARED IMPORTED)
    set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIBS_DIR}/libavutil.so)

    add_library(swresample SHARED IMPORTED)
    set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIBS_DIR}/libswresample.so)

    add_library(swscale SHARED IMPORTED)
    set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIBS_DIR}/libswscale.so)
    
    set(FFMPEG_FOUND TRUE)
else()
    message(WARNING "FFmpeg libraries NOT found for ${ANDROID_ABI}. Native engine will not work on this architecture.")
    set(FFMPEG_FOUND FALSE)
endif()

# Include paths
target_include_directories(hyperplay PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/engine
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/external/oboe/include
        ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/
        ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/resonance_audio/
        ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/third_party/eigen/
        ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/third_party/pffft/
)

# Force include paths for Resonance Audio components
target_include_directories(ResonanceAudioObj PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/)
target_include_directories(SadieHrtfsObj PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/)
target_include_directories(PffftObj PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/)

# Link libraries
target_link_libraries(hyperplay
        oboe
        ${log-lib}
        ${android-lib}
)

if(FFMPEG_FOUND)
    target_link_libraries(hyperplay
            avcodec
            avformat
            avutil
            swresample
            swscale
    )
    target_compile_definitions(hyperplay PRIVATE HAVE_FFMPEG)
endif()

# --- libsoxr Integration ---
message(STATUS "Integrating libsoxr sources directly...")

set(SOXR_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/soxr/src)
set(SOXR_SOURCES
        ${SOXR_SRC_DIR}/soxr.c
        ${SOXR_SRC_DIR}/data-io.c
        ${SOXR_SRC_DIR}/dbesi0.c
        ${SOXR_SRC_DIR}/filter.c
        ${SOXR_SRC_DIR}/cr.c
        ${SOXR_SRC_DIR}/cr32.c
        ${SOXR_SRC_DIR}/cr64.c
        ${SOXR_SRC_DIR}/vr32.c
        ${SOXR_SRC_DIR}/fft4g32.c
        ${SOXR_SRC_DIR}/fft4g64.c
)

target_sources(hyperplay PRIVATE ${SOXR_SOURCES})
target_compile_definitions(hyperplay PRIVATE SOXR_LIB)
target_include_directories(hyperplay PRIVATE 
        ${CMAKE_CURRENT_SOURCE_DIR}/external/soxr
        ${SOXR_SRC_DIR}
)

# --- libFlac Integration ---
message(STATUS "Integrating libFlac sources...")

set(FLAC_ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/libflac)
set(FLAC_SRC_DIR ${FLAC_ROOT_DIR}/src/libFLAC)
set(FLAC_INCLUDE_DIR ${FLAC_ROOT_DIR}/include)

# Note: These are the core sources needed for basic stream decoding
set(FLAC_SOURCES
        ${FLAC_SRC_DIR}/bitmath.c
        ${FLAC_SRC_DIR}/bitreader.c
        ${FLAC_SRC_DIR}/bitwriter.c
        ${FLAC_SRC_DIR}/cpu.c
        ${FLAC_SRC_DIR}/crc.c
        ${FLAC_SRC_DIR}/fixed.c
        ${FLAC_SRC_DIR}/float.c
        ${FLAC_SRC_DIR}/format.c
        ${FLAC_SRC_DIR}/lpc.c
        ${FLAC_SRC_DIR}/md5.c
        ${FLAC_SRC_DIR}/memory.c
        ${FLAC_SRC_DIR}/metadata_iterators.c
        ${FLAC_SRC_DIR}/metadata_object.c
        ${FLAC_SRC_DIR}/stream_decoder.c
        ${FLAC_SRC_DIR}/stream_encoder.c
        ${FLAC_SRC_DIR}/stream_encoder_framing.c
        ${FLAC_SRC_DIR}/window.c
)

target_compile_definitions(hyperplay PRIVATE 
    FLAC__NO_DLL
    FLAC__HAS_OGG=0
    HAVE_LROUND=1
    HAVE_INTTYPES_H=1
    __STDC_FORMAT_MACROS
    PACKAGE_VERSION="1.4.3"
)

# Add architecture-specific optimizations (NEON for ARM64)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(FLAC_SOURCES ${FLAC_SOURCES} ${FLAC_SRC_DIR}/lpc_intrin_neon.c)
    target_compile_definitions(hyperplay PRIVATE 
        FLAC__CPU_ARM64 
        FLAC__HAS_NEONINTRIN=1
    )
endif()

target_sources(hyperplay PRIVATE ${FLAC_SOURCES})

target_include_directories(hyperplay PRIVATE 
    ${FLAC_INCLUDE_DIR}
    ${FLAC_SRC_DIR}/include # For internal headers like "private/..."
)

# Add FlacDecoder to sources
target_sources(hyperplay PRIVATE engine/FlacDecoder.cpp)

# --- libbs2b (Cross-feed) Integration ---
message(STATUS "Integrating libbs2b sources...")
set(BS2B_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/bs2b)
set(BS2B_SOURCES
        ${BS2B_SRC_DIR}/bs2b.c
)

target_sources(hyperplay PRIVATE ${BS2B_SOURCES})
target_include_directories(hyperplay PRIVATE ${BS2B_SRC_DIR})

# Finalize include paths
target_include_directories(hyperplay PRIVATE 
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/engine
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/external/oboe/include
        ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/
        ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/resonance_audio/
        ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/third_party/eigen/
        ${CMAKE_CURRENT_SOURCE_DIR}/external/resonance-audio/third_party/pffft/
)
