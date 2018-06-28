add_definitions(
  -D__USE_GNU
  -D_GNU_SOURCE
)

set( LIB_PREFIX 64)

########### next target ###############

SET(fsalav_LIB_SRCS
   handle.c
   file.c
   xattrs.c
   avfs_methods.h
   main.c
   export.c
   fsal_convert.c
   antivirus.c
)

add_library(fsalav SHARED ${fsalav_LIB_SRCS})

add_library(clamav SHARED IMPORTED)
set_property(TARGET clamav PROPERTY IMPORTED_LOCATION /usr/local/lib64/libclamav.so)

target_link_libraries(fsalav
  gos
  clamav
)

set_target_properties(fsalav PROPERTIES VERSION 4.2.0 SOVERSION 4)
install(TARGETS fsalav COMPONENT fsal DESTINATION ${FSAL_DESTINATION} )

########### install files ###############
