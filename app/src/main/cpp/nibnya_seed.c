/*
 * 樱喵编辑器 —— 种子地形 JNI 桥。
 *
 * 引擎：cubiomes —— MIT License, Copyright (c) 2020 Cubitect
 *       https://github.com/Cubitect/cubiomes
 *       源码副本在 cpp/cubiomes/。MIT 与本项目的 AGPL-3.0 兼容，
 *       按 MIT 要求在此保留版权与许可声明。
 *
 * ## 设计要点：纯函数，无状态
 *
 * 每个入口都只依赖参数。Generator 在调用期间 malloc 出来、用完 free ——
 * setupGenerator + applySeed 实测约 0.05 ms，比加锁便宜得多。
 * 好处是 [SaveTiles] 那两个生成线程可以同时调用，不需要任何互斥。
 *
 * 参考了 SeedCraft 的 jni_bridge.c（GPL-3.0）的**接口形状**，
 * 但代码是本项目自己写的（SeedCraft 是 GPL，不能直接拷）。
 *
 * ## 为什么不把群系 ID 直接返回给 Java
 *
 * 一块瓦片 128×128 = 16384 个采样点。每次都过 JNI 传 int[] 再在 Java 里
 * 查表染色，等于多一次 64 KB 的拷贝 + 16384 次数组访问。
 * 这里直接在 C 里查好 ARGB 写进 out 数组，Java 侧只管 `setPixels`。
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdatomic.h>

#include "cubiomes/generator.h"
#include "cubiomes/biomes.h"
#include "cubiomes/finders.h"
#include "cubiomes/util.h"

#define TAG "NibnyaSeed"

/* ── 版本映射 ───────────────────────────────────────────────
 *
 * 存档里写的是 Bedrock 的版本号（如 "1.26.52"），cubiomes 认的是
 * Java 侧的 MCVersion 枚举。两者不是一一对应，但群系算法在
 * 同一个大版本内是稳定的，所以按主版本号就近取。
 *
 * **超出 cubiomes 支持范围时钳到 MC_NEWEST** —— 宁可地形略有偏差，
 * 也不能因为版本不认识就直接不画（那样用户看到的是全黑）。
 */
static int map_mc(int mc_level)
{
    switch (mc_level) {
    case 0:  return MC_1_21;      /* 1.21.x 及以后 */
    case 1:  return MC_1_20;      /* 1.20.x */
    case 2:  return MC_1_19;      /* 1.19.x */
    case 3:  return MC_1_18;      /* 1.18.x */
    case 4:  return MC_1_17;      /* 1.17.x */
    case 5:  return MC_1_16;      /* 1.16.x */
    default: return MC_UNDEF;     /* 见 new_generator：让调用方看到错误 */
    }
}

/* 采样高度（**方块单位**）。
 *
 * ⚠️ 单位很容易搞错，这里写清楚：
 *   Range.y 在 scale==1 时是 1:1 方块坐标，在 scale>1 时是 1:4。
 *   （见 biomenoise.h 的 Range 注释）
 * 所以不能只存一个数 —— 必须按 scale 换算。否则放大到
 * 1 方块/采样点时采样高度会掉到 y=79 方块（地下），整屏变成洞穴群系色。
 *
 * 数值取自 SeedCraft：太高会采到天空、太低会采到洞穴群系。 */
static int sample_y_blocks(int mc, int dim)
{
    switch (dim) {
    case DIM_NETHER: return 40;                          /* 下界：地表附近 */
    case DIM_END:    return 64;
    default:         return (mc >= MC_1_18) ? 316 : 60;  /* 1.18+ 地形抬高 */
    }
}

/* 方块高度 → Range.y 要求的单位：scale==1 → 1:1，否则 1:4。 */
static int range_y(int mc, int dim, int scale)
{
    int yb = sample_y_blocks(mc, dim);
    return (scale == 1) ? yb : (yb >> 2);
}

static Generator *new_generator(jlong seed, jint mc_level, jint dim)
{
    int mc = map_mc((int) mc_level);
    if (mc <= MC_UNDEF || mc > MC_NEWEST)
        return NULL;   /* 版本档位不认识 —— 调用方映射成 -2，界面提示 */
    if (dim < DIM_OVERWORLD || dim > DIM_END)
        return NULL;

    Generator *g = (Generator *) malloc(sizeof(Generator));
    if (!g) return NULL;

    /* 第三个参数是 flags：0 = 默认世界类型。
     * LARGE_BIOMES 之类的选项这里不暴露，地图上用不到。 */
    setupGenerator(g, mc, 0);
    applySeed(g, (int) dim, (uint64_t) seed);
    return g;
}

/* ── 群系调色板 ─────────────────────────────────────────────
 *
 * 直接用 cubiomes 的 initBiomeColors() 初始化一张静态表，
 * 而不是手抄颜色 —— 那个函数有 94 项，抄错一个就是永久的地图偏色，
 * 而且 cubiomes 升级后新加的群系会自动跟上。
 *
 * 初始化时机：第一次调用时懒加载。这里用一个普通 int 标志而不是
 * pthread_once —— 最坏情况是两个线程同时初始化，写进去的是完全相同
 * 的内容（纯查表计算，没有副作用），重复一次无害，省掉一次同步开销。
 */
static unsigned int COLOR_TABLE[256];
static atomic_int   COLOR_READY = 0;

static void init_colors_impl(void)
{
    unsigned char rgb[256][3];
    initBiomeColors(rgb);

    for (int i = 0; i < 256; i++) {
        /* alpha 强制 FF：initBiomeColors 对未知 ID 填 0，
         * 若直接当 ARGB 用会得到全透明 —— 地图上就是黑洞。 */
        COLOR_TABLE[i] = 0xFF000000u
                       | ((unsigned int) rgb[i][0] << 16)
                       | ((unsigned int) rgb[i][1] << 8)
                       | ((unsigned int) rgb[i][2]);
    }

    /* 未知 / 未定义 ID 走这个中性色，比纯黑好分辨。
     * the_void 本身是 0x000000，也要在这里拉回来一点。 */
    COLOR_TABLE[0]        = 0xFF1B2A3A;
    COLOR_TABLE[the_void] = 0xFF101820;
}

/*
 * 用 acquire/release 保证「表写完了」才置位：
 * 两个线程同时进来时，可能都执行 init（写的是相同内容，无害），
 * 但后读的一方必须看到完整的表。
 */
static void ensure_colors(void)
{
    if (atomic_load_explicit(&COLOR_READY, memory_order_acquire))
        return;
    init_colors_impl();
    atomic_store_explicit(&COLOR_READY, 1, memory_order_release);
}

/* COLOR_TABLE 是按 256 项建的，但 cubiomes 内部用负数表示
 * 「未知 / 未定义」（如 getBiomeAt 失败返回 -1），越界访问会读到
 * 相邻的静态变量，颜色随机。这里必须挡掉。 */
static inline unsigned int biome_color(int id)
{
    if (id < 0 || id >= 256)
        return 0xFF1B2A3A;
    return COLOR_TABLE[id];
}

/* ── 入口 ────────────────────────────────────────────────── */

/*
 * 生成一块瓦片的群系颜色。
 *
 * @param out  预分配的 int[]（ARGB），长度必须 >= w*h
 * @return 0 成功；-1 参数非法；-2 引擎不支持该版本；-3 内存不足
 *
 * **坐标是方块坐标**，不是区块坐标。调用方算好左上角传进来即可。
 */
JNIEXPORT jint JNICALL
Java_yhjmew_nibnya_map_SeedTerrainSource_nativeTile(
        JNIEnv *env, jobject thiz,
        jlong seed, jint mcLevel, jint dim,
        jint blockX, jint blockZ, jint cells, jint blocksPerCell,
        jintArray out)
{
    (void) thiz;

    if (cells <= 0 || blocksPerCell <= 0)
        return -1;

    jsize len = (*env)->GetArrayLength(env, out);
    if (len < (jsize) cells * cells)
        return -1;

    ensure_colors();

    Generator *g = new_generator(seed, mcLevel, dim);
    if (!g)
        return -2;

    /* cubiomes 的 scale 只接受 1 / 4 / 16 / 64 / 256。
     * 我们的档位正好是这套（1/4/16/64/256），但 1 表示「1 方块 = 1 采样点」，
     * 而 cubiomes 的 scale=1 是 1:1 方块尺度，能直接用。 */
    int scale = blocksPerCell;

    Range r;
    r.scale = scale;
    r.x = (int) floor((double) blockX / scale);
    r.z = (int) floor((double) blockZ / scale);
    r.sx = cells;
    r.sz = cells;
    r.y = range_y(map_mc((int) mcLevel), (int) dim, scale);
    r.sy = 1;

    /*
     * getMinCacheSize 已经包含内部临时缓冲：
     *   · 1.18+ / 非主世界且 scale<=1 时，会额外加一段 Voronoi 源区间
     *     （generator.c 里 len += sx*sy*sz）
     *   · 1.8~1.17 会加层叠缓存的最大需求
     * 所以按它给的长度分配就够了，不要再自己乘系数。
     */
    size_t need = getMinCacheSize(g, r.scale, r.sx, 1, r.sz);
    if (need == 0) {
        free(g);
        return -2;
    }
    int *cache = (int *) malloc(need * sizeof(int));
    if (!cache) {
        free(g);
        return -3;
    }

    int err = genBiomes(g, cache, r);
    if (err == 0) {
        int total = cells * cells;
        jint *dst = (*env)->GetIntArrayElements(env, out, NULL);
        if (dst) {
            for (int i = 0; i < total; i++)
                dst[i] = (jint) biome_color(cache[i]);
            (*env)->ReleaseIntArrayElements(env, out, dst, 0);
        } else {
            err = -3;
        }
    }

    free(cache);
    free(g);
    return (err == 0) ? 0 : -4;
}

/*
 * 单个位置的群系 ID。给将来的结构查找 / 调试用。
 */
JNIEXPORT jint JNICALL
Java_yhjmew_nibnya_map_SeedTerrainSource_nativeBiomeAt(
        JNIEnv *env, jobject thiz,
        jlong seed, jint mcLevel, jint dim, jint blockX, jint blockZ)
{
    (void) env; (void) thiz;
    Generator *g = new_generator(seed, mcLevel, dim);
    if (!g) return -1;

    int mc = map_mc((int) mcLevel);
    /* getBiomeAt 的 scale 固定传 4，所以 y 用 1:4 单位 */
    int id = getBiomeAt(g, 4,
                        (int) floor((double) blockX / 4.0),
                        range_y(mc, (int) dim, 4),
                        (int) floor((double) blockZ / 4.0));
    free(g);
    return id;
}

/*
 * 世界出生点估计（仅主世界）。返回 int[2]{x,z}；失败返回 null。
 *
 * 用 estimateSpawn 而不是 getSpawn：后者能给出更准的点，但要扫描
 * 「附近有没有草方块」，在手机上可能要几百毫秒，而地图居中只需要
 * 一个「大致在哪」——误差 +/-256 方块在这个缩放级别看不出来。
 */
JNIEXPORT jintArray JNICALL
Java_yhjmew_nibnya_map_SeedTerrainSource_nativeSpawn(
        JNIEnv *env, jobject thiz, jlong seed, jint mcLevel)
{
    (void) thiz;
    Generator *g = new_generator(seed, mcLevel, DIM_OVERWORLD);
    if (!g) return NULL;

    Pos p = estimateSpawn(g, NULL);
    free(g);

    jint v[2];
    v[0] = p.x;
    v[1] = p.z;
    jintArray res = (*env)->NewIntArray(env, 2);
    if (res)
        (*env)->SetIntArrayRegion(env, res, 0, 2, v);
    return res;
}
