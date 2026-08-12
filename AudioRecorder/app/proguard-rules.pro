# Room entities/DAOs use reflection via annotation processing (KSP generates
# code at compile time so this is mostly a safety net for reflection-based
# libraries used elsewhere).
-keep class com.example.audiorecorder.RecordingEntity { *; }
-keep class com.example.audiorecorder.** { *; }
-dontwarn kotlinx.coroutines.**
