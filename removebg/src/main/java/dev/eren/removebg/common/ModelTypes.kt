package dev.eren.removebg.common

/**
 * Created by erenalpaslan on 18.08.2023
 */
enum class ModelTypes(
    val fileName: String,
    val paramFileName: String = "",
    val binFileName: String = "",
    val onnxFileName: String = ""
) {
    U2NET("u2net.ptl", "u2net.param", "u2net.bin", "u2net.onnx"),
    U2NETP("u2netp.ptl", "u2netp.param", "u2netp.bin", "u2netp.onnx")
}