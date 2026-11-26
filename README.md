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


## Installation

To include "removebg" in your Android project, add the following to your Gradle files:

```gradle
allprojects {
    repositories {
      ...
      maven { url 'https://jitpack.io' }
    }
}

dependencies {
    implementation 'com.github.erenalpaslan:removebg:1.0.4'
}
```

## Usage

The current source requires Android 7.0 (API 24) or later. Google Play services delivers the optional ML Kit model. `AutoRemover` requests the model in the background when needed and uses NCNN for the current image.

``` kotlin
val remover = AutoRemover(context)

remover.clearBackground(inputImage.value).collect { output ->
    outputImage.value = output
}
```

Call `remover.close()` when you no longer need the remover.

## Contributions and Issues

Contributions and issue reports are welcome! Feel free to contribute to the development of this library or report any problems you encounter.

## License

 * MIT ([LICENSE](LICENSE) or https://opensource.org/license/mit/)

