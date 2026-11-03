# Benchmarks

Java 8 and 25, a 512 MiB heap, a 3-second warmup, and 7 samples per JVM. Results are medians across 3 JVM runs per condition.

Cells show **µs/op / B/op** (lower is better). B/op measures allocated bytes per operation, not peak memory usage.

|Java|Operation / input|Yomu|Jackson|json-io|ZSON|
|---|---|---:|---:|---:|---:|
|8|String → configuration object|0.72 / 1,624|1.00 / 1,904|1.66 / 3,288|—|
|8|String → large array|285.47 / 580,912|350.98 / 670,816|400.94 / 624,832|—|
|8|String → Java object (distinct strings)|29.02 / 45,416|21.03 / 24,744|36.67 / 43,360|—|
|8|UTF-8 → long escaped strings|201.29 / 210,120|167.35 / 67,032|218.63 / 109,056|—|
|25|String → configuration object|0.75 / 1,576|0.90 / 1,904|1.48 / 3,072|7.20 / 5,680|
|25|String → large array|301.80 / 564,648|254.44 / 654,648|342.99 / 590,512|3,289.84 / 4,859,072|
|25|String → Java object (distinct strings)|27.13 / 44,200|18.48 / 23,200|32.36 / 41,072|291.08 / 438,368|
|25|UTF-8 → long escaped strings|223.69 / 210,512|109.93 / 99,928|205.51 / 142,248|721.15 / 1,290,848|

Versions: Yomu 0.1.0-SNAPSHOT, Jackson 2.22.4, json-io 4.116.0, and ZSON 0.6.0-dev.5. The standard ZSON JAR was used; its downgraded Java 8 JAR was not measured.

Measured on Windows 11 with an AMD Ryzen AI Max+ 395. Jackson was configured with permissive options to accept the shared input format.
