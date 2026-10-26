# tls-bench

JMH benchmark comparing TLS 1.3 in Java: the JDK's built-in TLS (SunJSSE) vs netty-tcnative (BoringSSL), on Java 8, 11
and 25.

- Cipher: `TLS_AES_128_GCM_SHA256`, P-256 certificate (BoringSSL ignores the TLS 1.3 cipher setting but picks
  AES-128-GCM by default; the benchmark checks the negotiated cipher)
- Client and server `SSLEngine`s talk in memory; each network hop sleeps `latencyMs` (default 100 ms) to simulate
  latency, until the client is able to send application data

## Benchmarks

| Benchmark                  | What it measures                                          |
|----------------------------|-----------------------------------------------------------|
| `HandshakeBench.handshake` | Full TLS handshake, new session each time (no resumption) |

Params:

- `impl`: `jdk` (SunJSSE) or `tcnative` (BoringSSL)
- `latencyMs`: simulated latency per hop

## Requirements

- JDK 8, 11, 25 (auto-detected from sdkman or `/Library/Java/JavaVirtualMachines`, or set `JDK8`, `JDK11`, `JDK25`)
- Maven

### Setup with SDKMAN

Install [SDKMAN](https://sdkman.io):

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

Install Zulu JDKs (Zulu has native Apple Silicon builds for Java 8) and Maven. Pick the latest version of each line
from `sdk list java | grep zulu`, e.g.:

```bash
sdk install java 8.0.462-zulu
sdk install java 11.0.28-zulu
sdk install java 25.0.1-zulu
sdk install maven
```

`run.sh` finds them in `~/.sdkman/candidates/java/` automatically; no need to switch the default JDK.

## Run

```bash
#-wi 2	        -- 2 warmup iterations
#-w 1s	        -- each warmup iteration lasts 1s
#-i 3	        -- 3 measurement iterations
#-r 2s	        -- each measurement iteration lasts 2s
#-p latencyMs=0 -- no processing latency 
./run.sh -wi 2 -w 1s -i 3 -r 2s -p latencyMs=0
```

## Results

- `results/jdkN.txt` – raw JMH output per JDK
- `results/jdkN.csv` – JMH CSV per JDK
- `results/summary.txt` – combined report: JSSE vs tcnative per Java version

Note: with 100 ms latency, the sleep dominates the result; use `latencyMs=0` to see the TLS implementation cost.

## Results on M4 max, macos 27.0.1

```
Full handshake (new session, no resumption), latency 0 ms per hop  [ms/op]
                     JSSE        tcnative   comparison
  ----------------------------------------------------------------
  Java 8      2.39 ± 0.31   0.150 ± 0.014   tcnative 15.91x faster
  Java 11     1.38 ± 0.50   0.152 ± 0.048   tcnative 9.09x faster
  Java 25   0.804 ± 0.223   0.145 ± 0.005   tcnative 5.53x faster
```