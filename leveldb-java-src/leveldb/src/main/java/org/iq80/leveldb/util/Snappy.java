package org.iq80.leveldb.util;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Snappy 压缩的空实现。
 *
 * ## 为什么整个去掉
 * 原版通过 `org.xerial.snappy`（JNI，带 .so）或 `org.iq80.snappy`
 * （纯 Java）二选一来提供 Snappy。两个都是 optional 依赖，
 * 加起来约 1 MB+。
 *
 * 本项目的用途是**读 Minecraft 存档**，而 MC 的区块用 raw deflate
 * （见 {@link CompressionType#ZLIB_RAW}），从不使用 Snappy。
 * 因此这里保留 API 形状、去掉实现：
 *
 * - {@link #available()} 恒为 false —— DbImpl 据此在
 *   要求 SNAPPY 时直接报错，而不是等到读表时才崩。
 * - 其余方法抛 {@link UnsupportedOperationException}。
 *   正常流程走不到：Table 只在压缩字节是 SNAPPY 时才会调 uncompress，
 *   而 MC 存档里没有这种块。
 *
 * ⚠️ 将来如果要写 SNAPPY 压缩的库（比如把存档压缩方式改掉），
 * 需要把真正的实现加回来。
 */
public final class Snappy {

    private Snappy() {
    }

    /** 恒 false：本构建不含 Snappy 实现。 */
    public static boolean available() {
        return false;
    }

    public static ByteBuffer uncompress(ByteBuffer compressed) throws IOException {
        throw unsupported();
    }

    public static ByteBuffer uncompress(ByteBuffer compressed, ByteBuffer uncompressed) throws IOException {
        throw unsupported();
    }

    public static void uncompress(byte[] input, int inputOffset, int length, byte[] output, int outputOffset)
            throws IOException {
        throw unsupported();
    }

    public static int compress(byte[] input, int inputOffset, int length, byte[] output, int outputOffset)
            throws IOException {
        throw unsupported();
    }

    public static byte[] compress(String text) throws IOException {
        throw unsupported();
    }

    public static int maxCompressedLength(int length) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "Snappy is not bundled in this build; only ZLIB/ZLIB_RAW are supported");
    }
}
