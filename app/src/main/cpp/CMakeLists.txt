cmake_minimum_required(VERSION 3.18.1)
project("hermesreverser")

# Capstone 디스어셈블러 라이브러리
find_library(log-lib log)

# 기본 네이티브 라이브러리
add_library(native-lib SHARED native-lib.cpp)

target_link_libraries(native-lib ${log-lib})

# C++ 표준 설정
target_compile_features(native-lib PUBLIC cxx_std_17)
