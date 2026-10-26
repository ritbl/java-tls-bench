#!/usr/bin/env bash
# Usage: ./run.sh [jmh args...]   e.g. ./run.sh -f 1 -wi 3 -i 3
# JDK homes: JDK8 / JDK11 / JDK25 env vars, else auto-detected (sdkman, /Library/Java).
set -euo pipefail
cd "$(dirname "$0")"

find_jdk() {
  local v=$1 d
  for d in ~/.sdkman/candidates/java/"$v".* ~/.sdkman/candidates/java/"$v"-* \
           /Library/Java/JavaVirtualMachines/*-"$v".jdk/Contents/Home \
           /Library/Java/JavaVirtualMachines/*"$v".*/Contents/Home; do
    [[ -x "$d/bin/java" ]] && { echo "$d"; return; }
  done
}

JDK8=${JDK8:-$(find_jdk 8)}
JDK11=${JDK11:-$(find_jdk 11)}
JDK25=${JDK25:-$(find_jdk 25)}
[[ -n "$JDK25" ]] || { echo "JDK 25 not found (needed to build); set JDK25=..." >&2; exit 1; }

JAVA_HOME="$JDK25" mvn -q -B package -DskipTests
mkdir -p results

COMMON="-Xms1g -Xmx1g -XX:+UseG1GC -XX:+AlwaysPreTouch"
for v in 8 11 25; do
  home_var="JDK$v"; home=${!home_var}
  if [[ -z "$home" ]]; then echo "!! JDK $v not found, skipping (set $home_var=...)" >&2; continue; fi
  extra=""
  [[ $v == 25 ]] && extra="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
  echo "=== JDK $v: $home"
  "$home/bin/java" -jar target/benchmarks.jar \
    -jvmArgsAppend "$COMMON $extra" \
    -rf csv -rff "results/jdk$v.csv" "$@" | tee "results/jdk$v.txt"
done

"$JDK25/bin/java" -cp target/benchmarks.jar bench.Summary results/jdk*.csv | tee results/summary.txt
