# -*- coding: utf-8 -*-
"""
重新构建裁剪版 Java LevelDB（无 Guava / 无 Snappy）。

用法（在仓库根目录 = 编辑器 下执行）：
    python build_leveldb_java.py

产物：
    编辑器/app/libs/leveldb-java.jar       （实现，约 255 KB）
    编辑器/app/libs/leveldb-java-api.jar   （接口，约 8 KB）

## 为什么要自己构建
原版 https://github.com/HiveGamesOSS/leveldb-mcpe-java (1.1.0)
依赖 Guava（2.33 MB / 1814 个类）和可选的 Snappy。
本库实际只用到 Guava 的 30 个类型，却要背上整包；
Snappy 更是完全用不上（MC 存档是 raw deflate，见 CompressionType.ZLIB_RAW）。

裁剪后：
- 不再需要 guava.jar
- 不再需要 snappy-java / iq80-snappy
- leveldb 模块变成零依赖

改动集中在 leveldb-java-src/CHANGES.md 里列出的文件。

## 为什么不用 mvn
项目根 pom.xml 继承 io.airlift:airbase，构建要联网拉一堆插件；
而且本机没装 Maven。用 javac + zipfile 更直接，也不慢。
"""
import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'leveldb-java-src')
OUT = os.path.join(HERE, 'build', 'leveldb-java-classes')
LIBS = os.path.join(HERE, 'app', 'libs')

JAVAC = r'C:\jdk17\bin\javac.exe'
if not os.path.exists(JAVAC):
    JAVAC = shutil.which('javac') or 'javac'


def collect_sources():
    out = []
    for mod in ('leveldb-api', 'leveldb'):
        base = os.path.join(SRC, mod, 'src', 'main', 'java')
        for r, _d, files in os.walk(base):
            for f in files:
                if f.endswith('.java'):
                    out.append(os.path.join(r, f))
    return out


def pack(jar_path, keep):
    """把 OUT 里满足 keep(rel_path) 的 class 打成 jar。"""
    zip_date = (1980, 1, 1, 0, 0, 0)
    n = 0
    with zipfile.ZipFile(jar_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for r, _d, files in os.walk(OUT):
            for f in files:
                if not f.endswith('.class'):
                    continue
                full = os.path.join(r, f)
                rel = os.path.relpath(full, OUT).replace(os.sep, '/')
                if not keep(rel):
                    continue
                zi = zipfile.ZipInfo(rel, zip_date)
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, open(full, 'rb').read())
                n += 1
    return n, os.path.getsize(jar_path)


def main():
    if not os.path.isdir(SRC):
        print('[ERROR] 找不到源码目录:', SRC)
        return 1

    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    sources = collect_sources()
    print('源文件 %d 个，编译中…' % len(sources))
    r = subprocess.run(
        [JAVAC, '-encoding', 'UTF-8', '-nowarn', '-d', OUT] + sources,
        capture_output=True)
    err = ((r.stdout or b'') + (r.stderr or b'')).decode('gbk', 'replace')
    if r.returncode != 0:
        print('[ERROR] 编译失败')
        print(err[:3000])
        return 1

    # api = org/iq80/leveldb/X.class（4 段），impl = 其余
    is_api = lambda rel: len(rel.split('/')) == 4 and rel.startswith('org/')

    api_jar = os.path.join(LIBS, 'leveldb-java-api.jar')
    impl_jar = os.path.join(LIBS, 'leveldb-java.jar')
    os.makedirs(LIBS, exist_ok=True)

    na, sa = pack(api_jar, is_api)
    nj, sj = pack(impl_jar, lambda rel: not is_api(rel))

    print('leveldb-java-api.jar : %3d class  %6.1f KB' % (na, sa / 1024))
    print('leveldb-java.jar     : %3d class  %6.1f KB' % (nj, sj / 1024))

    # 确认没有混进 Guava
    for jar in (api_jar, impl_jar):
        with zipfile.ZipFile(jar) as z:
            bad = [n for n in z.namelist() if 'com/google' in n]
        if bad:
            print('[ERROR] %s 里混进了 Guava: %s' % (os.path.basename(jar), bad[:3]))
            return 1

    print('完成。记得跑一次验证（见 CHANGES.md）。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
