add_definitions(
  -D__USE_GNU
  -D_GNU_SOURCE
)

set( LIB_PREFIX 64)

########### next target ###############

SET(fsaldbg_LIB_SRCS
   handle.c
   file.c
   xattrs.c
   dbgfs_methods.h
   main.c
   export.c
   fsal_convert.c
)

add_library(fsaldbg SHARED ${fsaldbg_LIB_SRCS})

target_link_libraries(fsaldbg
  gos
)

set_target_properties(fsaldbg PROPERTIES VERSION 4.2.0 SOVERSION 4)
install(TARGETS fsaldbg COMPONENT fsal DESTINATION ${FSAL_DESTINATION} )


########### install files ###############
