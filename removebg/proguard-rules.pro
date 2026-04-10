# Keep the public API and JNI bridge names stable if library shrinking is enabled later.
-keep class dev.eren.removebg.Remover { *; }
-keep class dev.eren.removebg.NcnnRemover { *; }
