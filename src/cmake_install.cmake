# Install script for directory: /home/mchen/fsl-nfs-ganesha/src

# Set the install prefix
IF(NOT DEFINED CMAKE_INSTALL_PREFIX)
  SET(CMAKE_INSTALL_PREFIX "/usr/local")
ENDIF(NOT DEFINED CMAKE_INSTALL_PREFIX)
STRING(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
IF(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  IF(BUILD_TYPE)
    STRING(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  ELSE(BUILD_TYPE)
    SET(CMAKE_INSTALL_CONFIG_NAME "Debug")
  ENDIF(BUILD_TYPE)
  MESSAGE(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
ENDIF(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)

# Set the component getting installed.
IF(NOT CMAKE_INSTALL_COMPONENT)
  IF(COMPONENT)
    MESSAGE(STATUS "Install component: \"${COMPONENT}\"")
    SET(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  ELSE(COMPONENT)
    SET(CMAKE_INSTALL_COMPONENT)
  ENDIF(COMPONENT)
ENDIF(NOT CMAKE_INSTALL_COMPONENT)

# Install shared libraries without execute permission?
IF(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  SET(CMAKE_INSTALL_SO_NO_EXE "0")
ENDIF(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)

IF(NOT CMAKE_INSTALL_LOCAL_ONLY)
  # Include the install script for each subdirectory.
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/log/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/config_parsing/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/cidr/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/test/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/avl/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/hashtable/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/NodeList/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/cache_inode/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/SAL/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/libntirpc/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/RPCAL/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/Protocols/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/support/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/os/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/FSAL/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/idmapper/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/MainNFSD/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/tools/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/scripts/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/secnfs/cmake_install.cmake")
  INCLUDE("/home/mchen/fsl-nfs-ganesha/src/util/cmake_install.cmake")

ENDIF(NOT CMAKE_INSTALL_LOCAL_ONLY)

IF(CMAKE_INSTALL_COMPONENT)
  SET(CMAKE_INSTALL_MANIFEST "install_manifest_${CMAKE_INSTALL_COMPONENT}.txt")
ELSE(CMAKE_INSTALL_COMPONENT)
  SET(CMAKE_INSTALL_MANIFEST "install_manifest.txt")
ENDIF(CMAKE_INSTALL_COMPONENT)

FILE(WRITE "/home/mchen/fsl-nfs-ganesha/src/${CMAKE_INSTALL_MANIFEST}" "")
FOREACH(file ${CMAKE_INSTALL_MANIFEST_FILES})
  FILE(APPEND "/home/mchen/fsl-nfs-ganesha/src/${CMAKE_INSTALL_MANIFEST}" "${file}\n")
ENDFOREACH(file)
