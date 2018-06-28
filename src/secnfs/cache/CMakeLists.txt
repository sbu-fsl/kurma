set(secnfs_cache_SRC
  ProxyCache.cpp
  DirtyExtentManager.cpp
  FileCache.cpp
  WriteBackManager.hpp
)

add_library(secnfs_cache STATIC ${secnfs_cache_SRC})
target_link_libraries(secnfs_cache
  ${boost_LIBS}
  ${base_LIBS}
  secnfs_util
  support_base64
)

add_unittest(FileCacheTest secnfs_cache)
add_unittest(FileExtentTest secnfs_cache)
add_unittest(ProxyCacheTest secnfs_cache)
add_unittest(WriteBackManagerTest secnfs_cache)
add_unittest(DirtyExtentTest secnfs_cache)
add_unittest(DirtyExtentManagerTest secnfs_cache)
add_unittest(RevalidatorTest secnfs_cache)
add_unittest(AlignedTest secnfs_cache)
