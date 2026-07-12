# Additional clean files
cmake_minimum_required(VERSION 3.16)

if("${CONFIG}" STREQUAL "" OR "${CONFIG}" STREQUAL "")
  file(REMOVE_RECURSE
  "CMakeFiles/ciphercodex-shell_autogen.dir/AutogenUsed.txt"
  "CMakeFiles/ciphercodex-shell_autogen.dir/ParseCache.txt"
  "CMakeFiles/input-probe_autogen.dir/AutogenUsed.txt"
  "CMakeFiles/input-probe_autogen.dir/ParseCache.txt"
  "ciphercodex-shell_autogen"
  "input-probe_autogen"
  )
endif()
