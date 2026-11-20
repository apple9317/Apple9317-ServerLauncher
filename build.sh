#!/usr/bin/env bash
# 构建 MultiServerLauncher：编译 + 生成 jar-with-dependencies（内含 org.json，可直接 java -jar 运行）。
# 优先使用 Maven（pom.xml 中已把 maven-assembly-plugin 绑定到 package 阶段）；
# 没有 Maven 时退化为 javac + jar 的离线打包，产物路径与 Maven 构建保持一致。
set -euo pipefail
cd "$(dirname "$0")"

# 从 pom.xml 读取项目坐标，避免版本号变化后产物名对不上
pom_value() {
    sed -n "s:.*<$1>\([^<]*\)</$1>.*:\1:p" pom.xml | head -1
}

ARTIFACT_ID="$(pom_value artifactId)"
VERSION="$(pom_value version)"
FAT_JAR="target/${ARTIFACT_ID}-${VERSION}-jar-with-dependencies.jar"
MAIN_CLASS="$(pom_value mainClass)"

json_jar() {
    local candidate
    candidate="$(ls ~/.m2/repository/org/json/json/*/json-*.jar 2>/dev/null | head -1 || true)"
    if [ -z "$candidate" ]; then
        echo "未找到 org.json 依赖 jar（~/.m2/repository/org/json/json/...）" >&2
        echo "请先安装 Maven 并执行一次 mvn package，或手动提供 json-20210307.jar" >&2
        exit 1
    fi
    printf '%s\n' "$candidate"
}

if command -v mvn >/dev/null 2>&1; then
    echo "==> 使用 Maven 构建"
    mvn -B -q -DskipTests clean package
else
    echo "==> 未找到 Maven，使用 javac 离线打包"
    JSON_JAR="$(json_jar)"

    rm -rf target/classes
    mkdir -p target/classes

    # shellcheck disable=SC2046
    javac -encoding UTF-8 -d target/classes -cp "$JSON_JAR" $(find src/main/java -name '*.java')

    # 把 org.json 的 class 一起放进 fat jar
    rm -rf target/.deps && mkdir -p target/.deps
    (cd target/.deps && jar xf "$JSON_JAR")
    cp -r target/.deps/. target/classes/
    rm -rf target/.deps

    printf 'Main-Class: %s\n' "$MAIN_CLASS" > target/MANIFEST.MF
    jar cfm "$FAT_JAR" target/MANIFEST.MF -C target/classes .
    rm -f target/MANIFEST.MF
fi

if [ ! -f "$FAT_JAR" ]; then
    echo "构建失败：未生成 $FAT_JAR" >&2
    exit 1
fi

echo "==> 构建完成: $FAT_JAR"
echo "    运行: java -jar \"$FAT_JAR\" servers-config.json"
