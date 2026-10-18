cmake_minimum_required(VERSION 3.22.1)
project(airplay_tv C CXX)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(UX ${TP}/uxplay/lib)
set(PL ${TP}/libplist)
set(AL ${TP}/alac/codec)

# ---- OpenSSL (libcrypto only), prebuilt per ABI by scripts/build-openssl.sh ----
set(OPENSSL_ROOT ${TP}/openssl/${ANDROID_ABI})
if(NOT EXISTS ${OPENSSL_ROOT}/lib/libcrypto.a)
    message(FATAL_ERROR "Missing ${OPENSSL_ROOT}/lib/libcrypto.a - run scripts/build-openssl.sh first")
endif()
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
        IMPORTED_LOCATION ${OPENSSL_ROOT}/lib/libcrypto.a
        INTERFACE_INCLUDE_DIRECTORIES ${OPENSSL_ROOT}/include)

# ---- libplist (C API only) ----
add_library(plist STATIC
        ${PL}/src/plist.c ${PL}/src/bplist.c ${PL}/src/xplist.c ${PL}/src/jplist.c
        ${PL}/src/oplist.c ${PL}/src/base64.c ${PL}/src/bytearray.c ${PL}/src/hashtable.c
        ${PL}/src/ptrarray.c ${PL}/src/time64.c ${PL}/src/jsmn.c ${PL}/src/out-default.c
        ${PL}/src/out-plutil.c ${PL}/src/out-limd.c
        ${PL}/libcnary/node.c ${PL}/libcnary/node_list.c)
target_include_directories(plist PUBLIC ${PL}/include PRIVATE ${PL}/src ${PL}/libcnary/include)
target_compile_definitions(plist PRIVATE
        HAVE_MEMMEM HAVE_STRNDUP HAVE_STRPTIME HAVE_TIMEGM HAVE_GMTIME_R HAVE_LOCALTIME_R
        HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE PACKAGE_VERSION="2.8.0" _GNU_SOURCE)
target_compile_options(plist PRIVATE -w)

# ---- UxPlay protocol core (llhttp + playfair + lib) ----
add_library(airplay STATIC
        ${UX}/llhttp/api.c ${UX}/llhttp/http.c ${UX}/llhttp/llhttp.c
        ${UX}/playfair/hand_garble.c ${UX}/playfair/modified_md5.c ${UX}/playfair/omg_hax.c
        ${UX}/playfair/playfair.c ${UX}/playfair/sap_hash.c
        ${UX}/airplay_video.c ${UX}/byteutils.c ${UX}/compat.c ${UX}/crypto.c ${UX}/dnssd.c
        ${UX}/fairplay_playfair.c ${UX}/http_request.c ${UX}/http_response.c ${UX}/httpd.c
        ${UX}/logger.c ${UX}/mirror_buffer.c ${UX}/netutils.c ${UX}/pairing.c ${UX}/raop.c
        ${UX}/raop_buffer.c ${UX}/raop_ntp.c ${UX}/raop_rtp.c ${UX}/raop_rtp_mirror.c
        ${UX}/srp.c ${UX}/utils.c
        android_dnssd.c)
target_include_directories(airplay PUBLIC ${UX} ${UX}/playfair ${UX}/llhttp)
target_compile_definitions(airplay PRIVATE
        STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS TARGET_POSIX _LINUX
        _REENTRANT _GNU_SOURCE NOHOLD PLIST_210 PLIST_230
        PUBLIC OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(airplay PRIVATE -O2 -Wno-deprecated-declarations -Wno-unused-variable)
target_link_libraries(airplay PUBLIC plist crypto)

# ---- Apple ALAC decoder ----
add_library(alac STATIC
        ${AL}/ALACDecoder.cpp ${AL}/ALACBitUtilities.c ${AL}/EndianPortable.c
        ${AL}/ag_dec.c ${AL}/dp_dec.c ${AL}/matrix_dec.c)
target_include_directories(alac PUBLIC ${AL})
target_compile_options(alac PRIVATE -O2 -w)

# ---- JNI bridge ----
add_library(airplay_jni SHARED airplay_jni.cpp)
target_link_libraries(airplay_jni airplay alac log)
target_compile_options(airplay_jni PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-multichar)
