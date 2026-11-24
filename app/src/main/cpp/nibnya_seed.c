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

/* ══════════════════════════════════════════════════════════════
 * 崩溃捕获 + 日志桥
 *
 * ## 为什么需要这一层
 *
 * Java 侧的 `catch (Throwable)` 只能抓 Java 异常。native 一旦
 * SIGSEGV/SIGABRT，内核直接杀掉整个进程 —— Java 一行代码都执行不到，
 * 所以日志里什么都没有，用户只看到 App 凭空消失。
 *
 * 用户实测：缩放、拖动反复之后静默退出，crash-*.log 里没有任何记录。
 *
 * ## 三层结构
 *
 *   ① 返回码     —— 可恢复错误（参数非法、版本不支持、内存不足）
 *   ② Java 回调  —— 把 C 侧知道的一切吐给 AppLogger
 *   ③ 信号处理器 —— 抓 SIGSEGV 等，写文件后 _exit
 *
 * ## 信号处理器的硬限制
 *
 * 里面**不能 malloc、不能调 Java、不能加锁** —— 崩溃时堆可能已经损坏，
 * 任何非常驻操作都可能二次崩溃。所以：
 *   · 只 write() 到事先打开的 fd
 *   · 栈用 sigaltstack 单独分配（栈溢出时普通栈已不可用）
 *   · 用 async-signal-safe 的手写数字转换，不用 snprintf
 *
 * ## 为什么捕获后不恢复
 *
 * 进程状态已不可信，继续跑只会写坏存档。写完日志直接 _exit(128+sig)，
 * 和 shell 的约定一致。
 * ══════════════════════════════════════════════════════════════ */

#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <errno.h>
#include <stdint.h>

/* 崩溃日志 fd。-1 表示还没设置。 */
static volatile sig_atomic_t g_crash_fd = -1;

/* 备用信号栈。SIGSEGV 若由栈溢出引起，普通栈已不可用。 */
static char g_alt_stack[SIGSTKSZ * 4];

/* ── async-signal-safe 的整数转字符串 ──────────────────────
 * 不能用 snprintf：glibc 的实现在崩溃现场可能已经不可重入。
 */
static int safe_utoa(unsigned long v, char *out)
{
    char tmp[24];
    int n = 0;
    if (v == 0) {
        out[0] = '0';
        return 1;
    }
    while (v > 0 && n < 24) {
        tmp[n++] = (char) ('0' + (v % 10));
        v /= 10;
    }
    for (int i = 0; i < n; i++)
        out[i] = tmp[n - 1 - i];
    return n;
}

static void append_str(char *dst, int *pos, const char *src)
{
    while (*src && *pos < 4000)
        dst[(*pos)++] = *src++;
}

static void append_int(char *dst, int *pos, long v)
{
    if (v < 0) {
        if (*pos < 4000) dst[(*pos)++] = '-';
        v = -v;
    }
    if (*pos < 4000) {
        char num[24];
        int n = safe_utoa((unsigned long) v, num);
        for (int i = 0; i < n && *pos < 4000; i++)
            dst[(*pos)++] = num[i];
    }
}

static const char *signal_name(int sig)
{
    switch (sig) {
    case SIGSEGV: return "SIGSEGV(段错误)";
    case SIGABRT: return "SIGABRT(中止)";
    case SIGBUS:  return "SIGBUS(总线错误)";
    case SIGFPE:  return "SIGFPE(浮点异常)";
    case SIGILL:  return "SIGILL(非法指令)";
    default:      return "UNKNOWN";
    }
}

static void crash_handler(int sig, siginfo_t *info, void *uctx)
{
    (void) uctx;

    int fd = (int) g_crash_fd;
    if (fd < 0)
        _exit(128 + sig);

    char buf[4096];
    int pos = 0;

    append_str(buf, &pos, "\n====== NATIVE CRASH ======\n");
    append_str(buf, &pos, "signal: ");
    append_str(buf, &pos, signal_name(sig));
    append_str(buf, &pos, " (");
    append_int(buf, &pos, sig);
    append_str(buf, &pos, ")\n");

    append_str(buf, &pos, "fault addr: 0x");
    append_int(buf, &pos, (long) (uintptr_t) (info ? info->si_addr : 0));
    append_str(buf, &pos, "\n");

    append_str(buf, &pos, "code: ");
    append_int(buf, &pos, info ? info->si_code : 0);
    append_str(buf, &pos, "\n");

    append_str(buf, &pos, "hint: ");
    if (sig == SIGSEGV) {
        if (info && info->si_code == SEGV_MAPERR)
            append_str(buf, &pos, "access to unmapped address (null / wild pointer / use-after-free)");
        else if (info && info->si_code == SEGV_ACCERR)
            append_str(buf, &pos, "permission denied (write to read-only page, or stack overflow)");
        else
            append_str(buf, &pos, "unknown - check for heap corruption from out-of-bounds write");
    } else if (sig == SIGABRT) {
        append_str(buf, &pos, "abort() - heap corruption detected by malloc, or failed assert");
    } else {
        append_str(buf, &pos, "alignment / hardware related");
    }
    append_str(buf, &pos, "\n==========================\n");

    ssize_t r = write(fd, buf, (size_t) pos);
    (void) r;

    _exit(128 + sig);
}

/* 安装信号处理器。由 Java 侧在 Application.onCreate 调用，
 * 传入一个可写的文件路径（崩溃时只剩写文件这一条路）。 */
JNIEXPORT jint JNICALL
Java_yhjmew_nibnya_map_SeedTerrainSource_nativeInstallCrashHandler(
        JNIEnv *env, jclass cls, jstring path)
{
    (void) cls;
    if (!path)
        return -1;

    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath)
        return -2;

    int fd = open(cpath, O_WRONLY | O_CREAT | O_APPEND, 0600);
    (*env)->ReleaseStringUTFChars(env, path, cpath);

    if (fd < 0)
        return -3;

    g_crash_fd = fd;

    /* 备用栈：栈溢出时 SIGSEGV 才能被处理 */
    stack_t ss;
    ss.ss_sp = g_alt_stack;
    ss.ss_size = sizeof(g_alt_stack);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS,  &sa, NULL);
    sigaction(SIGFPE,  &sa, NULL);
    sigaction(SIGILL,  &sa, NULL);

    return 0;
}

/* ── 日志桥 ────────────────────────────────────────────────
 *
 * C 侧发现的异常通过它转给 Java，最终进 AppLogger。
 *
 * 为什么不在 C 里直接 fprintf：Android 上 stderr 会被丢弃，
 * 而且用户看不到。必须过 JNI 回到 Java，才能进日志文件 / 界面。
 *
 * ⚠️ 只在**异常路径**调用。正常渲染一秒几十块瓦片，每块都回调
 * 会拖慢渲染并刷爆日志 —— 正常数据只累计计数，见 nativeStats。
 */
static JavaVM  *g_vm = NULL;
static jclass   g_cls = NULL;          /* SeedTerrainSource 类 */
static jmethodID g_on_native_log = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/* 从任意线程调 Java。native 线程（pool 里的）不是 Java 线程，
 * 必须先 AttachCurrentThread，用完再 Detach，否则 JVM 会一直在
 * 线程表里留条目，最终耗尽。 */
static void native_log(JNIEnv *env, int level, const char *fmt, jlong a, jlong b)
{
    /* 绑定类（第一次调用时缓存，LocalRef 换成 GlobalRef） */
    if (!g_cls) {
        jclass local = (*env)->FindClass(env, "yhjmew/nibnya/map/SeedTerrainSource");
        if (!local)
            return;
        g_cls = (jclass) (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
        if (!g_cls)
            return;
        g_on_native_log = (*env)->GetStaticMethodID(
                env, g_cls, "onNativeLog", "(ILjava/lang/String;)V");
        if (!g_on_native_log)
            return;
    }

    /* 组装消息（这里可以用 snprintf —— 不在信号处理器里） */
    char msg[512];
    /* 只有两个占位符，避免引入更复杂的格式化 */
    snprintf(msg, sizeof(msg), fmt, (long) a, (long) b);

    jstring jmsg = (*env)->NewStringUTF(env, msg);
    if (jmsg) {
        (*env)->CallStaticVoidMethod(env, g_cls, g_on_native_log, level, jmsg);
        (*env)->DeleteLocalRef(env, jmsg);
    }
    /* 若 Java 侧抛了异常，必须清掉，否则下一次 JNI 调用会失败 */
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

/* 给 native 线程用的包装：自动 attach/detach */
static void log_from_worker(int level, const char *fmt, long a, long b)
{
    if (!g_vm)
        return;

    JNIEnv *env = NULL;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    int attached = 0;

    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK)
            return;
        attached = 1;
    } else if (rc != JNI_OK) {
        return;
    }

    native_log(env, level, fmt, (jlong) a, (jlong) b);

    if (attached)
        (*g_vm)->DetachCurrentThread(g_vm);
}

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

/* ── 错误码 ────────────────────────────────────────────────
 *
 * 每个码对应一个 Java 侧能直接展示给用户的原因。
 * 分得细一点，用户报 bug 时能和「哪一步失败」对上。
 */
#define ERR_OK            0
#define ERR_BAD_ARG      -1   /* 参数非法（cells/bpc <= 0，数组太短） */
#define ERR_BAD_VERSION  -2   /* 版本档位不认识 */
#define ERR_OOM          -3   /* malloc 失败 */
#define ERR_CACHE_SIZE   -4   /* getMinCacheSize 返回 0 */
#define ERR_GEN          -5   /* genBiomes 失败 */
#define ERR_JNI          -6   /* GetIntArrayElements 失败 */
#define ERR_BAD_DIM      -7   /* 维度非法 */

/* 统计计数器（正常路径不打日志，只累计，见 nativeStats） */
static atomic_long g_tiles_ok   = 0;
static atomic_long g_tiles_fail = 0;
static atomic_long g_tiles_ns   = 0;

/*
 * 生成一块瓦片的群系颜色。
 *
 * @param out  预分配的 int[]（ARGB），长度必须 >= cells*cells
 * @return 0 成功；负数为错误码（见上面 ERR_*）
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

    /* ① 参数校验 —— 全部走返回码，绝不静默 */
    if (cells <= 0 || blocksPerCell <= 0) {
        log_from_worker(3, "nativeTile 参数非法: cells=%ld bpc=%ld", cells, blocksPerCell);
        return ERR_BAD_ARG;
    }
    if (!out) {
        log_from_worker(3, "nativeTile out 为 null", 0, 0);
        return ERR_BAD_ARG;
    }
    if (dim < DIM_NETHER || dim > DIM_END) {
        log_from_worker(3, "nativeTile 维度非法: dim=%ld", dim, 0);
        return ERR_BAD_DIM;
    }

    /* 版本不认识要单独报，别混进 -1 */
    int mc = map_mc((int) mcLevel);
    if (mc <= MC_UNDEF || mc > MC_NEWEST) {
        log_from_worker(3, "cubiomes 不支持版本档位 %ld（按未支持处理）", mcLevel, 0);
        return ERR_BAD_VERSION;
    }

    jsize len = (*env)->GetArrayLength(env, out);
    jsize need_cells = (jsize) cells * cells;
    if (len < need_cells) {
        log_from_worker(3, "nativeTile 数组太短: len=%ld 需要=%ld", len, need_cells);
        return ERR_BAD_ARG;
    }

    ensure_colors();

    /* ② 分配 Generator */
    Generator *g = new_generator(seed, mcLevel, dim);
    if (!g)
        return ERR_BAD_VERSION;   /* new_generator 已校验过版本和维度 */

    /* cubiomes 的 scale 只接受 1 / 4 / 16 / 64 / 256。
     * 我们的档位正好是这套（1/4/16/64/256），但 1 表示「1 方块 = 1 采样点」，
     * 而 cubiomes 的 scale=1 是 1:1 方块尺度，能直接用。 */
    int scale = blocksPerCell;
    if (scale != 1 && scale != 4 && scale != 16 && scale != 64 && scale != 256) {
        /* 2 的幂档位里只有这几个合法；非法档位会让 cubiomes 内部除零 */
        log_from_worker(3, "非法采样档位 %ld（只允许 1/4/16/64/256）", scale, 0);
        free(g);
        return ERR_BAD_ARG;
    }

    Range r;
    r.scale = scale;
    r.x = (int) floor((double) blockX / scale);
    r.z = (int) floor((double) blockZ / scale);
    r.sx = cells;
    r.sz = cells;
    r.y = range_y(mc, (int) dim, scale);
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
        log_from_worker(3, "getMinCacheSize 返回 0: scale=%ld cells=%ld", scale, cells);
        free(g);
        return ERR_CACHE_SIZE;
    }

    int *cache = (int *) malloc(need * sizeof(int));
    if (!cache) {
        log_from_worker(3, "malloc 失败: %ld 格 = %ld 字节",
                        (long) need, (long) (need * sizeof(int)));
        free(g);
        return ERR_OOM;
    }

    int err = genBiomes(g, cache, r);
    if (err != 0) {
        log_from_worker(3, "genBiomes 失败 err=%ld: scale=%ld", err, scale);
        free(cache);
        free(g);
        return ERR_GEN;
    }

    /* ③ 写回 Java 数组 */
    jint *dst = (*env)->GetIntArrayElements(env, out, NULL);
    if (!dst) {
        log_from_worker(3, "GetIntArrayElements 返回 null（JVM 内存不足？）", 0, 0);
        free(cache);
        free(g);
        return ERR_JNI;
    }

    for (int i = 0; i < need_cells; i++)
        dst[i] = (jint) biome_color(cache[i]);

    (*env)->ReleaseIntArrayElements(env, out, dst, 0);

    free(cache);
    free(g);

    atomic_fetch_add(&g_tiles_ok, 1);
    return ERR_OK;
}

/*
 * 统计与诊断。给设置页 / 调试面板用。
 * 返回 long[3]{成功数, 失败数, 累计耗时毫秒}
 */
JNIEXPORT jlongArray JNICALL
Java_yhjmew_nibnya_map_SeedTerrainSource_nativeStats(
        JNIEnv *env, jclass cls)
{
    (void) cls;
    jlong vals[3];
    vals[0] = (jlong) atomic_load(&g_tiles_ok);
    vals[1] = (jlong) atomic_load(&g_tiles_fail);
    vals[2] = (jlong) atomic_load(&g_tiles_ns) / 1000000;

    jlongArray arr = (*env)->NewLongArray(env, 3);
    if (arr)
        (*env)->SetLongArrayRegion(env, arr, 0, 3, vals);
    return arr;
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
    if (dim < DIM_NETHER || dim > DIM_END) {
        log_from_worker(3, "nativeBiomeAt 维度非法: dim=%ld", dim, 0);
        return -1;
    }
    Generator *g = new_generator(seed, mcLevel, dim);
    if (!g) {
        log_from_worker(3, "nativeBiomeAt 生成器创建失败: mcLevel=%ld", mcLevel, 0);
        return -1;
    }

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
    if (!g) {
        log_from_worker(3, "nativeSpawn 生成器创建失败: mcLevel=%ld", mcLevel, 0);
        return NULL;
    }

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
