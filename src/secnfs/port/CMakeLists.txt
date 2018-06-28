file(GLOB_RECURSE port_FILES "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")

add_library(secnfs_port STATIC ${port_FILES})
