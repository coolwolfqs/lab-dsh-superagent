#!/usr/bin/env bash
# 在独立构建机上执行 Android 构建，避免在承载 DSH 服务的共享 PVE 主机/容器上
# 安装 Android SDK、跑 Gradle 或开模拟器（会挤占宿主资源，影响 PVE 稳定性）。
#
# 用法：
#   bash scripts/remote-build.sh [debug|release]
#
# 构建机连接信息来自环境变量或 ~/.config/dsh/build.env（不要提交到仓库）：
#   BUILD_HOST          必填，SSH 目标，例如 user@build-machine
#   BUILD_SSH_KEY       可选，SSH 私钥路径
#   BUILD_DIR           可选，构建机上的工作目录（相对 $HOME 或绝对路径），
#                       默认 dsh-build/lab-dsh-superagent
#   BUILD_JAVA_HOME     可选，构建机上的 JDK 目录，默认 $HOME/.local/jdk17
#   BUILD_ANDROID_HOME  可选，构建机上的 Android SDK 目录，
#                       默认 $HOME/Library/Android/sdk 或 $HOME/Android/Sdk
#
# 退出码：0 成功；1 构建失败；2 配置/参数错误。
set -euo pipefail

usage() {
  cat <<'EOF'
用法: bash scripts/remote-build.sh [debug|release]

配置项（环境变量或 ~/.config/dsh/build.env，路径可用 DSH_BUILD_ENV 覆盖）:
  BUILD_HOST         必填，构建机 SSH 目标，如 user@build-machine
  BUILD_SSH_KEY      可选，SSH 私钥文件
  BUILD_DIR          可选，构建机上的工作目录，默认 dsh-build/lab-dsh-superagent
  BUILD_JAVA_HOME    可选，构建机上的 JDK，默认 $HOME/.local/jdk17
  BUILD_ANDROID_HOME 可选，构建机上的 Android SDK，默认 $HOME/Library/Android/sdk

本机只负责同步源码、取回 APK；Gradle 构建在构建机上执行。
EOF
}

variant=debug
case "${1:-}" in
  '') ;;
  -h|--help) usage; exit 0 ;;
  debug|release) variant=$1 ;;
  *) echo "unknown variant: $1" >&2; usage >&2; exit 2 ;;
esac

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${DSH_BUILD_ENV:-$HOME/.config/dsh/build.env}
if [[ -f $ENV_FILE ]]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
fi

if [[ -z ${BUILD_HOST:-} ]]; then
  cat >&2 <<'EOF'
错误：没有配置构建机（BUILD_HOST）。
请把构建机连接信息写到 ~/.config/dsh/build.env，例如：
  BUILD_HOST=user@build-machine
  BUILD_SSH_KEY=$HOME/.ssh/build-key
不要在共享主机上退回本地构建（会安装 Android SDK、跑 Gradle，影响 PVE 稳定性）。
EOF
  exit 2
fi

REMOTE_DIR=${BUILD_DIR:-dsh-build/lab-dsh-superagent}
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15)
if [[ -n ${BUILD_SSH_KEY:-} ]]; then
  SSH_OPTS+=(-i "$BUILD_SSH_KEY")
fi

[[ -n $REMOTE_DIR && $REMOTE_DIR != / ]] || { echo "错误：BUILD_DIR 不合法: '$REMOTE_DIR'" >&2; exit 2; }

remote_dir_q=$(printf '%q' "$REMOTE_DIR")

echo "==> 同步源码到构建机 $BUILD_HOST:$REMOTE_DIR"
# 整目录重建：只删 build 目录会留下上一次同步的旧源码，源文件删除/改名后可能被误编译。
tar -C "$ROOT" -cf - \
  --exclude='android/build' \
  --exclude='android/app/build' \
  --exclude='android/.gradle' \
  --exclude='android/local.properties' \
  android | ssh "${SSH_OPTS[@]}" "$BUILD_HOST" \
  "set -e; rm -rf -- $remote_dir_q; mkdir -p -- $remote_dir_q; tar -C $remote_dir_q -xf -"

echo "==> 在构建机上执行 Gradle 构建（$variant）"
# ssh 会把参数拼成命令行：空参数会消失。用 %q 逐个加引号（空串变成 ''），保证远端收到的位置参数不串位。
remote_args=$(printf '%q ' "$REMOTE_DIR" "$variant" "${BUILD_JAVA_HOME:-}" "${BUILD_ANDROID_HOME:-}")
ssh "${SSH_OPTS[@]}" "$BUILD_HOST" "bash -s -- $remote_args" <<'REMOTE'
set -euo pipefail
dir=$1
variant=$2
java_home=${3:-}
android_home=${4:-}

expand_home() {
  case $1 in
    "~") printf '%s' "$HOME" ;;
    "~/"*) printf '%s/%s' "$HOME" "${1#\~/}" ;;
    *) printf '%s' "$1" ;;
  esac
}

dir=$(expand_home "$dir")
java_home=$(expand_home "$java_home")
android_home=$(expand_home "$android_home")
[[ -n $dir && $dir != / ]] || { echo "BUILD_DIR 不合法: $dir" >&2; exit 2; }
cd "$dir/android"

export JAVA_HOME="${java_home:-$HOME/.local/jdk17}"
if [[ -n $android_home ]]; then
  export ANDROID_HOME=$android_home
elif [[ -d $HOME/Library/Android/sdk ]]; then
  export ANDROID_HOME=$HOME/Library/Android/sdk
elif [[ -d $HOME/Android/Sdk ]]; then
  export ANDROID_HOME=$HOME/Android/Sdk
fi
export ANDROID_SDK_ROOT=${ANDROID_HOME:-}
export PATH="$JAVA_HOME/bin:$PATH"

case $variant in
  release) task=assembleRelease ;;
  *) task=assembleDebug ;;
esac

echo "构建机: host=$(hostname) os=$(uname -s) java=$JAVA_HOME sdk=${ANDROID_HOME:-unset}"
./gradlew --no-daemon --stacktrace testDebugUnitTest lintDebug "$task"
REMOTE

apk="android/app/build/outputs/apk/$variant/app-$variant.apk"
remote_apk_q=$(printf '%q' "$REMOTE_DIR/$apk")
mkdir -p "$ROOT/android/dist"
echo "==> 取回 APK"
target="$ROOT/android/dist/dsh-web-shell-$variant.apk"
# 先写临时文件再原子替换：传输中断时不会把已有的 dist APK 截断成空文件。
tmp=$(mktemp "${TMPDIR:-/tmp}/dsh-web-shell-$variant.XXXXXX.apk")
trap 'rm -f "$tmp"' EXIT
ssh "${SSH_OPTS[@]}" "$BUILD_HOST" "cat $remote_apk_q" > "$tmp"
[[ -s $tmp ]] || { echo "错误：取回的 APK 为空" >&2; exit 1; }
mv -f "$tmp" "$target"
trap - EXIT

if command -v sha256sum >/dev/null 2>&1; then
  digest=$(sha256sum "$target" | cut -d' ' -f1)
else
  digest=$(shasum -a 256 "$target" | cut -d' ' -f1)
fi
echo "==> 完成: $target"
echo "    size: $(wc -c < "$target") bytes"
echo "    sha256: $digest"
