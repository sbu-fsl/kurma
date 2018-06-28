add_definitions(
  -D__USE_GNU
  -D_GNU_SOURCE
)

set( LIB_PREFIX 64)

########### library dependency ################

# secnfs cpp library
set(SECNFS_CPP "${CMAKE_SOURCE_DIR}/secnfs")
include_directories(
	${SECNFS_CPP}
)

find_package(GLIB)
message("GLIB ${GLIB_INCLUDE_DIR}")
if (GLIB_FOUND)
  include_directories(${GLIB_INCLUDE_DIRS})
endif()


########### next target ###############

SET(fsalpcache_LIB_SRCS
   handle.c
   file.c
   xattrs.c
   pcachefs_methods.h
   main.c
   export.c
   cache_handle.c
   antivirus.c
   writeback_manager.h
   writeback_manager.c
)

add_library(fsalpcache SHARED ${fsalpcache_LIB_SRCS})

target_link_libraries(fsalpcache
  gos
  secnfs_capi
  clamav
  glib
  ${GLIB_LIBRARIES}
)

set(CMAKE_C_FLAGS_DEBUG "-DDEBUG_PCACHE -g")

set_target_properties(fsalpcache PROPERTIES VERSION 4.2.0 SOVERSION 4)
install(TARGETS fsalpcache COMPONENT fsal DESTINATION ${FSAL_DESTINATION} )


########### install files ###############
