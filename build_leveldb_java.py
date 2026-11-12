# -*- coding: utf-8 -*-
"""
重新构建裁剪版 Java LevelDB（无 Guava / 无 Snappy）。

用法（在仓库根目录 = 编辑器 下执行）：
    python build_leveldb_java.py

产物：
    app/libs/leveldb-java.jar       （实现，约 255 KB）
    app/libs/leveldb-java-api.jar   （接口，约 8 KB）

## 源码在哪
独立仓库：https://github.com/yinghuajimew/leveldb-java-src

不放在本仓库里，是因为它是一份第三方 fork 的完整源码（129 个 .java）。
混在一起会让 `git log` 被无关提交淹没，克隆主项目的人也得被迫拉这 0.67 MB。

默认行为：
- 本地已有 leveldb-java-src/  → 直接用它（方便离线改源码）
- 没有                        → 自动 clone 到临时目录，用完删掉

想固定版本就打 tag / 传 --ref：

    python build_leveldb_java.py --ref v1.0.0
    python build_leveldb_java.py --src C:\\path\\to\\leveldb-java-src

## 为什么要自己构建
上游依赖 Guava（2.33 MB / 1814 个类）和可选的 Snappy。
本库只用到 Guava 的 30 个类型；Snappy 完全用不上
（MC 存档是 raw deflate，见 CompressionType.ZLIB_RAW）。

改动说明见 https://github.com/yinghuajimew/leveldb-java-src/blob/main/CHANGES.md

## 为什么不用 Maven
根 pom.xml 继承 io.airlift:airbase，要联网拉一大棵插件树；
本机也未必装了 Maven。javac + zipfile 更直接。

## 验证
改完源码后务必跑一次逐键 SHA-256 比对（不要只比键数量）：

    keys=125559  bytes=211780071
    digest=62e289b6a013838bf0956d06da6ff94e9c83f933420efdb5e2d64c2acdf4b49b
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))

REPO_URL = 'https://github.com/yinghuajimew/leveldb-java-src.git'

OUT = os.path.join(HERE, 'build', 'leveldb-java-classes')
LIBS = os.path.join(HERE, 'app', 'libs')

JAVAC = r'C:\jdk17\bin\javac.exe'
if not os.path.exists(JAVAC):
    JAVAC = shutil.which('javac') or 'javac'


def resolve_source(explicit):
    """返回 (源码目录, 是否为临时目录)。"""
    if explicit:
        p = os.path.abspath(explicit)
        if not os.path.isdir(p):
            raise SystemExit('[ERROR] --src 指定的目录不存在: %s' % p)
        return p, False

    local = os.path.join(HERE, 'leveldb-java-src')
    if os.path.isdir(os.path.join(local, 'leveldb')):
        print('使用本地源码:', local)
        return local, False

    print('未找到本地源码，从 %s 获取…' % REPO_URL)
    tmp = tempfile.mkdtemp(prefix='nibnya-ldb-')
    return tmp, True


def collect_sources(root):
    out = []
    for mod in ('leveldb-api', 'leveldb'):
        base = os.path.join(root, mod, 'src', 'main', 'java')
        if not os.path.isdir(base):
            raise SystemExit('[ERROR] 源码结构不对，缺少 %s' % base)
        for r, _d, files in os.walk(base):
            for f in files:
                if f.endswith('.java'):
                    out.append(os.path.join(r, f))
    return out


def pack(jar_path, keep, cls_dir):
    zip_date = (1980, 1, 1, 0, 0, 0)
    n = 0
    with zipfile.ZipFile(jar_path, 'w', zipfile.ZIP_DEFLATED) as z:
        for r, _d, files in os.walk(cls_dir):
            for f in files:
                if not f.endswith('.class'):
                    continue
                full = os.path.join(r, f)
                rel = os.path.relpath(full, cls_dir).replace(os.sep, '/')
                if not keep(rel):
                    continue
                zi = zipfile.ZipInfo(rel, zip_date)
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, open(full, 'rb').read())
                n += 1
    return n, os.path.getsize(jar_path)


def main():
    ap = argparse.ArgumentParser(description='重新构建裁剪版 Java LevelDB')
    ap.add_argument('--ref', help='git 分支/标签（仅在使用远程源码时生效）')
    ap.add_argument('--src', help='本地源码目录，覆盖默认查找')
    args = ap.parse_args()

    src, is_tmp = resolve_source(args.src)
    clone_dir = src

    try:
        if is_tmp:
            cmd = ['git', 'clone', '--depth', '1']
            if args.ref:
                cmd += ['--branch', args.ref]
            cmd += [REPO_URL, src]
            r = subprocess.run(cmd, capture_output=True)
            if r.returncode != 0:
                err = ((r.stdout or b'') + (r.stderr or b'')).decode('utf-8', 'replace')
                print('[ERROR] clone 失败：')
                print(err[:1500])
                print('\n提示：可以手动 clone 后放到本地，再用 --src 指定。')
                return 1
            print('已获取源码。')

        sources = collect_sources(src)

        if os.path.isdir(OUT):
            shutil.rmtree(OUT)
        os.makedirs(OUT)

        print('源文件 %d 个，编译中…' % len(sources))
        r = subprocess.run(
            [JAVAC, '-encoding', 'UTF-8', '-nowarn', '-d', OUT] + sources,
            capture_output=True)
        err = ((r.stdout or b'') + (r.stderr or b'')).decode('gbk', 'replace')
        if r.returncode != 0:
            print('[ERROR] 编译失败')
            print(err[:3000])
            return 1

        is_api = lambda rel: len(rel.split('/')) == 4 and rel.startswith('org/')

        os.makedirs(LIBS, exist_ok=True)
        api_jar = os.path.join(LIBS, 'leveldb-java-api.jar')
        impl_jar = os.path.join(LIBS, 'leveldb-java.jar')

        na, sa = pack(api_jar, is_api, OUT)
        nj, sj = pack(impl_jar, lambda rel: not is_api(rel), OUT)

        print('leveldb-java-api.jar : %3d class  %6.1f KB' % (na, sa / 1024))
        print('leveldb-java.jar     : %3d class  %6.1f KB' % (nj, sj / 1024))

        # 确认没有混进 Guava
        for jar in (api_jar, impl_jar):
            with zipfile.ZipFile(jar) as z:
                bad = [n for n in z.namelist() if 'com/google' in n]
            if bad:
                print('[ERROR] %s 里混进了 Guava: %s'
                      % (os.path.basename(jar), bad[:3]))
                return 1

        print('完成。记得跑一次逐键 SHA-256 验证（见文件头注释）。')
        return 0

    finally:
        if is_tmp and os.path.isdir(clone_dir):
            shutil.rmtree(clone_dir, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
