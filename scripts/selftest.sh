#!/usr/bin/env bash
# 외부 라이브러리 없이 자체 테스트 실행 (JDK 17+, python3 필요)
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG=C.UTF-8 LC_ALL=C.UTF-8 PYTHONIOENCODING=UTF-8
rm -rf out/test && mkdir -p out/test
javac -encoding UTF-8 --release 17 -d out/test $(find src/main/java src/test/java -name "*.java")
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp out/test com.assignment.eai.SelfTest
