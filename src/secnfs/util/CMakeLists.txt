set(util_SRC
  histogram.cpp
  string_util.cpp
  coding.cpp
  base64.cpp
  testutil.cpp
  hash.cpp
  cache_interface.cpp
  lrucache.cpp
  fileutil.cpp
  status.cpp
  env.cpp
  env_posix.cpp
  blockmap.cpp
  protobuf.cpp
)

# for include generated protobuf headers
include_directories(${CMAKE_CURRENT_BINARY_DIR})

add_library(secnfs_util STATIC ${util_SRC})
target_link_libraries(secnfs_util secnfs_port secnfs_proto support_base64)

add_unittest(blockmap_test secnfs_util)
add_unittest(coding_test secnfs_util)
add_unittest(cache_test secnfs_util)
add_unittest(fileutil_test secnfs_util)
add_unittest(env_test secnfs_util)
add_unittest(protobuf_test secnfs_util)
add_unittest(iclutil_test secnfs_util)
add_unittest(base64_test secnfs_util)
