# Removebg - Background Removal Android Library

[![Release](https://jitpack.io/v/com.github.erenalpaslan/removebg.svg)](https://jitpack.io/#com.github.erenalpaslan/removebg)  [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)<br>

The "removebg" Android library simplifies background removal from images on Android. It currently includes:

- `NcnnRemover`: offline U2Net/NCNN-based background removal.
- `AutoRemover`: ML Kit Subject Segmentation when the model is ready, with offline NCNN (U2NetP) fallback. The Demo defaults to Auto and also offers U2NetP mode for offline comparison.

| Original Image | Background Removed Image |
| -------------- | ------------------------- |
| ![Original Image](previews/1695711470810.jpg) | ![Background Removed Image](previews/1695711470815.jpg) |
| ![Original Image](previews/1695711470819.jpg) | ![Background Removed Image](previews/1695711470823.jpg) |
| ![Original Image](previews/1695711470828.jpg) | ![Background Removed Image](previews/1695711470832.jpg) |


## Android Integration

The current SDK requires Android 7.0 (API 24) or later and includes native NCNN libraries for `arm64-v8a` and `armeabi-v7a`.

### Local AAR

With JDK 17 or 21, build the SDK from the repository root with `./gradlew :removebg:assembleRelease`. Put the generated `removebg/build/outputs/aar/removebg-sdk-1.0.5.aar` in the host app's `app/libs/` directory. Make sure the host's dependency repositories include `google()` and `mavenCentral()`.

Add the AAR and its runtime dependencies in the host app's `build.gradle.kts`. A standalone AAR imported from `libs/` does not resolve its transitive dependencies automatically.

```kotlin
dependencies {
    implementation(files("libs/removebg-sdk-1.0.5.aar"))
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
}
```

If you publish this SDK to a Maven repository with dependency metadata, the host can use that published coordinate and Gradle will resolve the transitive dependencies. The JitPack badge above points to the upstream project. Use an artifact built from this repository to access `AutoRemover`.

### Optional Model Predownload

To let Google Play services request the subject segmentation model after installation from Google Play, add this entry inside the **host app's** `<application>` in `AndroidManifest.xml`:

```xml
<meta-data
    android:name="com.google.mlkit.vision.DEPENDENCIES"
    android:value="subject_segment" />
```

If the host already declares `com.google.mlkit.vision.DEPENDENCIES`, append `subject_segment` to its existing comma-separated value. Keep a single entry. If a dependency declares that same key with a different value, set the complete list in the host entry and add `tools:replace="android:value"` with `xmlns:tools="http://schemas.android.com/tools"` on the host `<manifest>`.

### Remove a Background

`AutoRemover` tries ML Kit when the model is ready. When the model is unavailable, inference fails, or ML Kit finds no usable foreground, it falls back to the bundled NCNN model. If Google Play services is available but the model is not installed, the SDK requests it in the background and uses NCNN for the current image. This runtime path also works without the predownload entry.

Call from a coroutine and reuse the remover while the screen or app needs it:

```kotlin
import dev.eren.removebg.AutoRemover
import kotlinx.coroutines.flow.first

val remover = AutoRemover(context)
val output = remover.clearBackground(bitmap).first()
```

`output` is a `Bitmap?`. A `null` result means neither engine produced an image. Use `remover.getMlKitStatus()` to check readiness or `remover.clearBackground(bitmap, preferMlKit = false)` to force NCNN. Call `remover.close()` when you no longer need it.

## Contributions and Issues

Contributions and issue reports are welcome! Feel free to contribute to the development of this library or report any problems you encounter.

## License

 * MIT ([LICENSE](LICENSE) or https://opensource.org/license/mit/)

