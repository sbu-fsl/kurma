add_definitions(
  -D__USE_GNU
  -D_GNU_SOURCE
)

set(LIB_PREFIX 64)

set(BOOST "$ENV{BOOST_ROOT}")
include_directories(${BOOST})
set(BOOST_LIB_DIR "${BOOST}/stage/lib/")
find_library(BOOST_FS_LIB boost_filesystem ${BOOST_LIB_DIR})
find_library(BOOST_REGEX_LIB boost_regex ${BOOST_LIB_DIR})
find_library(BOOST_SYS_LIB boost_system ${BOOST_LIB_DIR})
set(boost_LIBS
  ${BOOST_FS_LIB}
  ${BOOST_SYS_LIB}
  ${BOOST_REGEX_LIB})

add_definitions(-std=c++11)

# for include generated protobuf headers
message(STATUS "binary dir library: " ${CMAKE_CURRENT_BINARY_DIR})
include_directories(${CMAKE_CURRENT_BINARY_DIR})

########### unit tests ################

enable_testing()

#set(GTEST "$ENV{GOOGLE_TEST}")
message(STATUS "GTEST: " ${GTEST})
include_directories(
  "${GTEST}"
  "${GTEST}/include"
)

#set(GMOCK "$ENV{GOOGLE_MOCK}")
message(STATUS "GMOCK: " ${GMOCK})
include_directories(
  "${GMOCK}"
  "${GMOCK}/include"
)

include_directories("${CMAKE_CURRENT_SOURCE_DIR}")

#add_library(gtest "${GTEST}/src/gtest-all.cc")
#add_library(gtest_main "${GTEST}/src/gtest_main.cc")
add_library(gmock "${GMOCK}/src/gmock-all.cc")
add_library(gmock_main "${GMOCK}/src/gmock_main.cc")

#find_library(TBB_LIB tbb "/usr/local/lib64/tbb/")
#message(STATUS "tbb library: " ${TBB_LIB})

find_library(GFLAGS_LIB gflags "/usr/local/lib")
message(STATUS "gflags library: " ${GFLAGS_LIB})

find_library(GLOG_LIB glog "/usr/local/lib")
message(STATUS "glog library: " ${GLOG_LIB})

set(base_LIBS
  pthread
  ${GFLAGS_LIB}
  ${GLOG_LIB}
  ${PROTOBUF_LIBRARIES}
)

set(test_LIB
  pthread
  gtest
  gtest_main
  gmock
  gmock_main
)

function (add_unittest TestName LibName)
  add_executable(${TestName} "${CMAKE_CURRENT_SOURCE_DIR}/${TestName}.cpp")
  target_link_libraries(${TestName} ${base_LIBS} ${boost_LIBS} ${test_LIB} ${LibName})
  add_test(NAME ${TestName} COMMAND ${TestName})
endfunction (add_unittest)

set (CMAKE_POSITION_INDEPENDENT_CODE TRUE) # compile all static lib with -fPIC
add_subdirectory(port)
add_subdirectory(util)
add_subdirectory(base)
add_subdirectory(proto)
add_subdirectory(cache)
add_subdirectory(capi)
