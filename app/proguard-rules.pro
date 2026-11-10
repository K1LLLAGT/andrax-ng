# Termux terminal libraries
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# commons-compress optional dependencies we do not ship
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.annotation.bundle.**
-dontwarn javax.annotation.**

# Our own code that is looked up by name
-keep class net.k1lllagt.andraxng.** { *; }
