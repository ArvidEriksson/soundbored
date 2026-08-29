# NewPipeExtractor uses reflection-free parsing, but Rhino (JS engine) needs its
# generated classes kept when shrinking.
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
