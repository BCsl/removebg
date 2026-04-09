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
    U2NET("u2net.ptl", "u2net.ncnn.param", "u2net.ncnn.bin", "u2net.onnx"),
    U2NETP("u2netp.ptl", "u2netp.ncnn.param", "u2netp.ncnn.bin", "u2netp.onnx")
}