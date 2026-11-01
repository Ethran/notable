# Shrink and optimize, but keep names and line numbers so crash logs stay readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# lz4-java picks its compressor implementation by class name.
-keep class net.jpountz.** { *; }

# Onyx SDK: reflection into hidden APIs and its own classes; it ships no rules.
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**
# fastjson2, used by the Onyx SDK, (de)serializes by reflection.
-keep class com.alibaba.fastjson2.** { *; }
-dontwarn com.alibaba.fastjson2.**

# MuPDF: native code calls back into these classes.
-keep class com.artifex.mupdf.fitz.** { *; }
# Optional annotations joda-time (from the Onyx SDK) refers to.
-dontwarn org.joda.convert.**
# Ktor's debugger check uses JVM management classes Android doesn't have; it's never reached there.
-dontwarn java.lang.management.**
