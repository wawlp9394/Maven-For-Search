#!/usr/bin/env bash
# Maven For Search 插件开发环境一键配置 (Linux/macOS)
#
# 检测并搭建缺失环境: JDK 21, Gradle 依赖缓存, 国内镜像加速。
# 所有产物离开系统盘, 由用户指定安装根目录。
# 幂等: 重复运行不重复下载。
#
# 使用: bash scripts/setup.sh

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_PATH=""

# ---------- 颜色输出 ----------
step() { printf "\n\033[36m[STEP] %s\033[0m\n" "$1"; }
ok()   { printf "  \033[32m[OK] %s\033[0m\n" "$1"; }
warn() { printf "  \033[33m[WARN] %s\033[0m\n" "$1"; }
err()  { printf "  \033[31m[ERR] %s\033[0m\n" "$1"; }

# ---------- 1. 确认安装根目录 (使用脚本执行的当前目录) ----------
get_install_root() {
    step "确认安装根目录"
    local root
    root="$(pwd)"
    echo "  将使用当前目录作为安装根目录: $root"
    echo "  会在此目录下创建: jdk21/ (JDK), gradle-home/ (Gradle 依赖缓存)"

    # 拒绝系统目录 (/usr, /etc, /bin, /sbin, /boot, /lib)
    case "$root" in
        /usr*|/etc*|/bin*|/sbin*|/boot*|/lib*|/proc*|/sys*)
            err "当前目录在系统目录, 不允许存放环境产物: $root"
            err "请切换到用户目录后重跑: cd ~/some/dir; bash setup.sh"
            exit 1
            ;;
    esac

    read -rp "确认在此目录安装? (回车继续, Ctrl+C 取消): " confirm
    echo "$root"
}

# ---------- 2. 检测 / 安装 JDK 21 ----------
test_jdk21() {
    local hint_dir="$1"
    # 优先查 hint 目录
    if [[ -n "$hint_dir" && -x "$hint_dir/bin/java" ]]; then
        local ver
        ver="$("$hint_dir/bin/java" -version 2>&1 | head -n1)"
        if [[ "$ver" == *'"21.'* ]]; then
            ok "检测到 JDK 21: $hint_dir ($ver)"
            JDK_PATH="$hint_dir"
            return 0
        fi
    fi
    # 查 JAVA_HOME
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        local ver
        ver="$("$JAVA_HOME/bin/java" -version 2>&1 | head -n1)"
        if [[ "$ver" == *'"21.'* ]]; then
            ok "检测到 JAVA_HOME 指向 JDK 21: $JAVA_HOME"
            JDK_PATH="$JAVA_HOME"
            return 0
        fi
    fi
    return 1
}

install_jdk21() {
    local root="$1"
    local jdk_dir="$root/jdk21"
    if test_jdk21 "$jdk_dir"; then return; fi

    warn "未检测到 JDK 21, 开始下载 (华为云镜像)..."
    # 判断架构: x64 还是 aarch64
    local arch url
    arch="$(uname -m)"
    case "$arch" in
        x86_64|amd64) arch="x64";;
        aarch64|arm64) arch="aarch64";;
        *)
            err "不支持的架构: $arch, 请手动安装 JDK 21"
            exit 1
            ;;
    esac

    # OpenJDK 21 通用下载 URL (华为云镜像)
    url="https://mirrors.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_linux-${arch}_bin.tar.gz"
    local tarball="$root/openjdk21.tar.gz"

    echo "  下载: $url"
    if ! curl -fSL -o "$tarball" "$url"; then
        err "下载失败"
        err "请手动下载 Temurin 21 并解压, 然后用环境变量 JAVA_HOME 指向它, 重跑本脚本"
        exit 1
    fi

    echo "  解压..."
    tar -xzf "$tarball" -C "$root"
    # 解压后目录名形如 jdk-21.0.2
    local extracted
    extracted="$(find "$root" -maxdepth 1 -type d -name 'jdk-21*' | head -n1)"
    if [[ -z "$extracted" ]]; then
        err "解压后未找到 jdk-21* 目录"
        exit 1
    fi
    rm -rf "$jdk_dir"
    mv "$extracted" "$jdk_dir"
    rm -f "$tarball"

    if ! test_jdk21 "$jdk_dir"; then
        err "安装后仍检测失败"
        exit 1
    fi
}

# ---------- 3. 配置环境变量 ----------
set_env_vars() {
    local jdk_home="$1"
    local gradle_home="$2"
    step "配置环境变量 (写入 ~/.bashrc)"

    local rc_file="$HOME/.bashrc"
    local marker="# >>> maven-for-search-env >>>"
    local marker_end="# <<< maven-for-search-env <<<"
    # 旧版标记名 (兼容已运行过旧脚本的用户, 清理后写入新标记)
    local old_marker="# >>> maven-search-env >>>"
    local old_marker_end="# <<< maven-search-env <<<"

    # 移除旧的标记块 (新旧都清理)
    for pair in "$marker $marker_end" "$old_marker $old_marker_end"; do
        local m="${pair%% *}"
        local me="${pair##* }"
        if grep -q "$m" "$rc_file" 2>/dev/null; then
            sed -i.bak "/$m/,/$me/d" "$rc_file"
            rm -f "$rc_file.bak"
        fi
    done

    # 追加新的
    {
        echo ""
        echo "$marker"
        echo "export JAVA_HOME=\"$jdk_home\""
        echo "export GRADLE_USER_HOME=\"$gradle_home\""
        echo 'export PATH="$JAVA_HOME/bin:$PATH"'
        echo "$marker_end"
    } >> "$rc_file"

    # 当前会话生效
    export JAVA_HOME="$jdk_home"
    export GRADLE_USER_HOME="$gradle_home"
    export PATH="$jdk_home/bin:$PATH"

    ok "已写入 $rc_file"
    ok "JAVA_HOME = $jdk_home"
    ok "GRADLE_USER_HOME = $gradle_home"
}

# ---------- 4. 改写项目配置文件 ----------
update_project_config() {
    local jdk_home="$1"
    step "改写项目配置文件"

    # 4.1 gradle-wrapper.properties: distributionUrl 改腾讯云镜像
    local wrapper_file="$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties"
    if [[ -f "$wrapper_file" ]]; then
        if grep -q 'services.gradle.org/distributions' "$wrapper_file"; then
            sed -i.bak 's|https://services.gradle.org/distributions/|https://mirrors.cloud.tencent.com/gradle/|g' "$wrapper_file"
            rm -f "$wrapper_file.bak"
            ok "gradle-wrapper.properties: 已切换到腾讯云镜像"
        else
            ok "gradle-wrapper.properties: 已是镜像, 跳过"
        fi
    else
        warn "未找到 $wrapper_file"
    fi

    # 4.2 gradle.properties: org.gradle.java.installations.paths 改为实际 JDK 路径
    local gp_file="$PROJECT_ROOT/gradle.properties"
    if [[ -f "$gp_file" ]]; then
        if grep -q '^org.gradle.java.installations.paths=' "$gp_file"; then
            sed -i.bak "s|^org.gradle.java.installations.paths=.*|org.gradle.java.installations.paths=$jdk_home|" "$gp_file"
            rm -f "$gp_file.bak"
            ok "gradle.properties: installations.paths = $jdk_home"
        else
            warn "gradle.properties 未找到 installations.paths 配置项"
        fi
    else
        warn "未找到 $gp_file"
    fi
}

# ---------- 主流程 ----------
main() {
    printf "\033[36m========================================\033[0m\n"
    printf "\033[36m  Maven For Search 环境配置 (Linux)   \033[0m\n"
    printf "\033[36m========================================\033[0m\n"

    local root gradle_home
    root="$(get_install_root)"
    install_jdk21 "$root"
    gradle_home="$root/gradle-home"
    mkdir -p "$gradle_home"

    set_env_vars "$JDK_PATH" "$gradle_home"
    update_project_config "$JDK_PATH"

    step "完成"
    printf "  \033[32m环境配置完成。请重新打开终端或执行: source ~/.bashrc\033[0m\n"
    printf "  \033[32m然后执行:\033[0m\n"
    printf "  \033[32m    cd %s\033[0m\n" "$PROJECT_ROOT"
    printf "  \033[32m    ./gradlew buildPlugin -x test\033[0m\n"
    printf "\n"
    printf "  \033[33m注意: 首次构建会下载 IntelliJ Platform SDK (~800MB), 无国内镜像, 耐心等待。\033[0m\n"
}

# 仅在直接执行时运行主流程 (source 时只加载函数, 不执行)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main
fi
