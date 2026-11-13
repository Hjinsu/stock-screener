#!/usr/bin/env bash
# Linux 빌드(Ubuntu 24.04 / JDK 17). build.ps1 과 같은 의존성·같은 SHA-256 고정값을 사용한다.
# 사용: deploy/linux/build.sh [--skip-tests]
set -euo pipefail
cd "$(dirname "$0")/../.."

declare -a DEPS=(
  "org/xerial|sqlite-jdbc|3.47.1.0|4164d95347aeab42b754cabb7945b93e54f29ddb2eded967dbe979b4045cb685"
  "org/slf4j|slf4j-api|1.7.36|d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0"
  "org/slf4j|slf4j-nop|1.7.36|c214958b07816cb4412b30c7bdbd4308ffdc6ba2a83767b8f3a9229cbd9274d6"
  "org/mariadb/jdbc|mariadb-java-client|3.5.8|6127dc7858047b3d4482899139640b0e2ab2b8abdeb708cfb8c011117771cddf"
)
mkdir -p lib
for d in "${DEPS[@]}"; do
  IFS='|' read -r g a v sha <<<"$d"
  jar="lib/$a-$v.jar"
  if [ ! -f "$jar" ]; then
    echo "다운로드: $a-$v"
    curl -fsSL -o "$jar" "https://repo1.maven.org/maven2/$g/$a/$v/$a-$v.jar"
  fi
  echo "$sha  $jar" | sha256sum -c --quiet || { rm -f "$jar"; echo "SHA-256 불일치(변조 또는 손상 의심): $jar" >&2; exit 1; }
done
echo "의존성 SHA-256 확인 완료"

CP=$(ls lib/*.jar | tr '\n' ':')
rm -rf build && mkdir -p build/classes build/test-classes
find src/main/java -name '*.java' > build/sources.txt
javac -encoding UTF-8 --release 17 -d build/classes -cp "$CP" @build/sources.txt
cp -r src/main/resources/* build/classes/
find src/test/java -name '*.java' > build/test-sources.txt
javac -encoding UTF-8 --release 17 -d build/test-classes -cp "build/classes:$CP" @build/test-sources.txt
{
  echo "Main-Class: screener.App"
  echo "Class-Path: $(ls lib/*.jar | tr '\n' ' ')"
} > build/MANIFEST.MF
jar --create --file screener.jar --manifest build/MANIFEST.MF -C build/classes .
echo "빌드 완료: screener.jar"

if [ "${1:-}" != "--skip-tests" ]; then
  java -Dfile.encoding=UTF-8 -cp "build/test-classes:build/classes:$CP" screener.TestMain
fi
