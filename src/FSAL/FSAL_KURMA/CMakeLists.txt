add_definitions(
  -D__USE_GNU
  -D_GNU_SOURCE
)

set( LIB_PREFIX 64)

########### library dependency ###############
# set secnfs cpp & .thrift dir
set(SECNFS_CPP "${CMAKE_SOURCE_DIR}/secnfs")
set(SECNFS_CPP_BINARY "${PROJECT_BINARY_DIR}/secnfs")

# find thrift
# set(THRIFT "$ENV{THRIFT}")
# INCLUDE(FindPkgConfig)
# set( ENV{PKG_CONFIG_PATH} "$ENV{PKG_CONFIG_PATH}:${THRIFT}/lib/pkgconfig" )
INCLUDE(FindPkgConfig)
set( ENV{PKG_CONFIG_PATH} "$ENV{PKG_CONFIG_PATH}:/usr/local/lib/pkgconfig" )
pkg_check_modules (THRIFT_C_GLIB REQUIRED thrift_c_glib>=0.9.2)
message(STATUS "thrift_c_glib include: " ${THRIFT_C_GLIB_INCLUDE_DIRS})
message(STATUS "thrift_c_glib library: " ${THRIFT_C_GLIB_LDFLAGS})
include_directories(${THRIFT_C_GLIB_INCLUDE_DIRS})
link_directories(${THRIFT_C_GLIB_LIBRARY_DIRS})
SET(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)

# generate thrift c code
SET( THRIFT_PROTO ${SECNFS_CPP}/proto/Kurma.thrift)
SET( THRIFT_OUTDIR ${SECNFS_CPP_BINARY}/proto)
SET( THRIFT_FLAGS -r --gen c_glib -o ${THRIFT_OUTDIR})
SET( THRIFT_ARGS ${THRIFT_FLAGS} ${THRIFT_PROTO})

FIND_PROGRAM(THRIFT_BIN thrift ${THRIFT}/bin /bin /usr/bin /usr/local/bin)
IF (THRIFT_BIN)
  IF(NOT EXISTS ${THRIFT_PROTO})
    MESSAGE(SEND_ERROR "Cannot find " ${THRIFT_PROTO})
  ENDIF(NOT EXISTS ${THRIFT_PROTO})
ELSE(THRIFT_BIN)
  MESSAGE(SEND_ERROR "Cannot find thrift in your env path.")
ENDIF(THRIFT_BIN)

set(THRIFT_OUTPUT_FILES
  ${THRIFT_OUTDIR}/gen-c_glib/kurma_service.c
  ${THRIFT_OUTDIR}/gen-c_glib/kurma_service.h
  ${THRIFT_OUTDIR}/gen-c_glib/kurma_types.c
  ${THRIFT_OUTDIR}/gen-c_glib/kurma_types.h
  ${THRIFT_OUTDIR}/gen-c_glib/namespace_types.c
  ${THRIFT_OUTDIR}/gen-c_glib/namespace_types.h
)

add_custom_command(
  OUTPUT  ${THRIFT_OUTPUT_FILES}
  COMMAND ${THRIFT_BIN} ${THRIFT_ARGS}
  DEPENDS ${THRIFT_PROTO} ${SECNFS_CPP}/proto/Namespace.thrift
  COMMENT "Generating thrift files: ${THRIFT_OUTPUT_FILES}"
)
set_property(SOURCE kurma.c APPEND PROPERTY OBJECT_DEPENDS ${THRIFT_OUTPUT_FILES})

# include and compile generated header and sources
include_directories(${THRIFT_OUTDIR})
FILE(GLOB THRIFT_OUTPUT_SOURCES ${THRIFT_OUTDIR}/gen-c_glib/*.c)

########### next target ###############

SET(fsalkurma_LIB_SRCS
  main.c
  export.c
  handle.c
  file.c
  xattrs.c
  ../../support/perf_stats.c
  kurma_methods.h
  kurma.c
  ${THRIFT_OUTPUT_FILES}
  xxhash.c
  xxhash.h
  )

add_library(fsalkurma SHARED ${fsalkurma_LIB_SRCS})

target_link_libraries(fsalkurma
  ${THRIFT_C_GLIB_LIBRARIES}
  ${SYSTEM_LIBRARIES} # link jemalloc
  )

set_target_properties(fsalkurma PROPERTIES VERSION 4.2.0 SOVERSION 4)
install(TARGETS fsalkurma COMPONENT fsal DESTINATION ${FSAL_DESTINATION} )


########### install files ###############

# vim:expandtab:shiftwidth=2:tabstop=2:
