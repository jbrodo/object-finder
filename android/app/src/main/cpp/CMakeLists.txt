cmake_minimum_required(VERSION 3.22)
project(objectfinder_mirage CXX)

# Motore Cactus (Mirage) collegato staticamente. Esiste solo per ARM: sulle altre architetture
# (emulatore x86_64) si compila la stessa interfaccia JNI che risponde "non disponibile".
set(CACTUS_LIB "${CACTUS_LIB_ROOT}/${ANDROID_ABI}/libcactus.a")

add_library(objectfinder_mirage SHARED mirage_jni.cpp)
target_include_directories(objectfinder_mirage PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if(EXISTS "${CACTUS_LIB}")
    add_library(cactus STATIC IMPORTED)
    set_target_properties(cactus PROPERTIES IMPORTED_LOCATION "${CACTUS_LIB}")
    target_compile_definitions(objectfinder_mirage PRIVATE HAVE_CACTUS=1)
    target_link_libraries(objectfinder_mirage PRIVATE cactus log m)
else()
    message(STATUS "Cactus non disponibile per ${ANDROID_ABI}: Mirage disattivato")
    target_link_libraries(objectfinder_mirage PRIVATE log)
endif()

# Pagine da 16 KB (Android 15+ su alcuni dispositivi).
target_link_options(objectfinder_mirage PRIVATE "-Wl,-z,max-page-size=16384")
