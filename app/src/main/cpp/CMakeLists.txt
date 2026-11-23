# 樱喵编辑器 —— 原生层构建。
#
# 产物：libnibnya.so（固定用软件英文名，不带 ABI 后缀以外的修饰）
#
# 包含两部分：
#   1. nibnya_seed.c   —— 本项目写的 JNI 桥（MIT）
#   2. cubiomes/*.c    —— 群系生成引擎（MIT，© Cubitect）
#
# ## 为什么排除 quadbase.c
# 它依赖 windows.h / pthread.h 做高度图采样，而 T4 只用群系色块，
# 不读高度。带上它反而会在 Android 上编译失败。
#
# ## 为什么不开 -ffast-math
# cubiomes 的噪声计算依赖 IEEE 语义，打开后地形会变样（不是变快）。

cmake_minimum_required(VERSION 3.22.1)
project(nibnya C)

set(CUBIOMES_DIR ${CMAKE_CURRENT_SOURCE_DIR}/cubiomes)

add_library(nibnya SHARED
        nibnya_seed.c

        ${CUBIOMES_DIR}/generator.c
        ${CUBIOMES_DIR}/biomenoise.c
        ${CUBIOMES_DIR}/layers.c
        ${CUBIOMES_DIR}/noise.c
        ${CUBIOMES_DIR}/biomes.c
        ${CUBIOMES_DIR}/finders.c
        ${CUBIOMES_DIR}/util.c
)

target_include_directories(nibnya PRIVATE
        ${CUBIOMES_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}
)

# log：给 __android_log_print 用（现在没用到，留着方便排查）
find_library(log-lib log)
target_link_libraries(nibnya ${log-lib})

# -O2 而非 -O3：cubiomes 是整数/浮点混合的密集循环，
# -O3 的向量化收益很小，反而让 so 体积和首次加载时间上去。
# Android 上首次 JNI 调用要 dlopen + 重定位，so 越小越好。
target_compile_options(nibnya PRIVATE
        -O2
        -fno-strict-aliasing
        -Wall
        -Wno-unused-parameter
)
