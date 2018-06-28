set(base_SRC
  PathMapping.cpp
)

add_library(secnfs_base ${base_SRC})
target_link_libraries(secnfs_base secnfs_util)

add_unittest(PathMappingTest secnfs_base)
add_unittest(AddressLockTest secnfs_base)
