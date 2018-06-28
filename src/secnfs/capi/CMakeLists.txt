set(capi_SRC
  path_mapping.cpp
  proxy_cache.cpp
)

add_library(secnfs_capi ${capi_SRC})
target_link_libraries(secnfs_capi secnfs_util secnfs_base secnfs_cache)
