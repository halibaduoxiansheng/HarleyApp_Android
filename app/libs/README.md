# 本地二进制依赖说明

`sherpa-onnx-static-link-onnxruntime-1.13.7.aar` 来自 k2-fsa 官方 `sherpa-onnx` v1.13.7 发布资产，用于 Android 端本地 ASR/VAD 推理。该变体把自身使用的 ONNX Runtime 静态链接进 sherpa JNI，使 M2M100 可独立使用 Maven ONNX Runtime 1.28.0。

- 来源：<https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7>
- 文件：`sherpa-onnx-static-link-onnxruntime-1.13.7.aar`
- 精确大小：`37,809,521` bytes
- SHA-256：`220d7cb25ac6e57ec34082bc2551ebd7ec7d4d96cbfea520839e208f92347837`
- 许可：Apache License 2.0
- 随应用保留的许可：`app/src/main/assets/third_party_licenses/APACHE_LICENSE_2_0.txt`

官方资产的 32 位 `x86` 目录仍包含共享 `libonnxruntime.so`，因此应用明确不打包过时的 `x86` ABI，只保留 `arm64-v8a`、`armeabi-v7a` 与 `x86_64`。更新版本时必须同时复核每个 ABI 的原生条目、文件名、精确大小、固定校验值和第三方许可说明；不要使用未经校验的同名 AAR 覆盖该文件。
