#!/bin/bash
# Сборка app/libs/executorch-1.5.0-xnnpack.aar: libexecutorch.so для arm64 из исходников ExecuTorch v1.5.0
# поверх Java-классов готового AAR. Готовый AAR с ossci-android.s3.amazonaws.com не годится: в нём custom_ops
# (LLM-ядра) отдают -march=armv8.2-a+dotprod как PUBLIC-флаг (extension/llm/custom_ops/CMakeLists.txt), и весь
# JNI-слой собирается с inline LSE-атомиками ARMv8.1 — SIGILL на Cortex-A53/A73 (Snapdragon 680/685: Redmi Note
# 11/12/13 4G, Redmi 12, Poco M5s). Здесь LLM-ядра выключены, атомики через outline-хелперы с проверкой в рантайме.
#
# Нужны: NDK r28c (ANDROID_NDK), cmake ≥ 3.28, venv с executorch 1.5.0 (../venv-et), ~5 ГБ на диске, минут десять.
#   ANDROID_NDK=~/android-sdk/ndk-r28c tools/build_executorch_aar.sh /tmp/et-build
set -e
WORK=${1:?каталог сборки}; APP=$(cd "$(dirname "$0")/.." && pwd)
PY=${PYTHON_EXECUTABLE:-$APP/../venv-et/bin/python}; NDK=${ANDROID_NDK:?путь к NDK r28c}
AAR=$APP/app/libs/executorch-1.5.0-xnnpack.aar
mkdir -p "$WORK"; cd "$WORK"
[ -f executorch.aar ] || curl -sSL -o executorch.aar https://ossci-android.s3.amazonaws.com/executorch/release/1.5.0-xnnpack/executorch.aar
if [ ! -d executorch ]; then   # CMake требует, чтобы каталог назывался ровно executorch (issue 6475)
    git clone -q --depth 1 --branch v1.5.0 https://github.com/pytorch/executorch.git
    (cd executorch && git submodule update -q --init --depth 1 backends/xnnpack/third-party/FP16 backends/xnnpack/third-party/FXdiv \
        backends/xnnpack/third-party/XNNPACK backends/xnnpack/third-party/cpuinfo backends/xnnpack/third-party/pthreadpool \
        kernels/optimized/third-party/eigen third-party/flatbuffers third-party/flatcc third-party/gflags third-party/pybind11 \
        third-party/ao third-party/pocketfft third-party/json third-party/prelude shim)
fi
cd executorch
# Как scripts/build_android_library.sh, но без LLM: EXTENSION_LLM/LLM_RUNNER/ASR_RUNNER/KERNELS_LLM/LLAMA_JNI = OFF.
cmake . --preset android-arm64-v8a -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DPYTHON_EXECUTABLE="$PY" \
    -DCMAKE_INSTALL_PREFIX=cmake-out-android-arm64-v8a -DANDROID_PLATFORM=android-23 -DCMAKE_BUILD_TYPE=Release \
    -DEXECUTORCH_ENABLE_EVENT_TRACER=OFF -DEXECUTORCH_ANDROID_PROFILING=OFF \
    -DEXECUTORCH_BUILD_EXTENSION_LLM=OFF -DEXECUTORCH_BUILD_EXTENSION_LLM_RUNNER=OFF -DEXECUTORCH_BUILD_EXTENSION_ASR_RUNNER=OFF \
    -DEXECUTORCH_BUILD_KERNELS_LLM=OFF -DEXECUTORCH_BUILD_LLAMA_JNI=OFF -DEXECUTORCH_BUILD_EXTENSION_TRAINING=ON \
    -DEXECUTORCH_BUILD_NEURON=OFF -DEXECUTORCH_BUILD_QNN=OFF -DEXECUTORCH_BUILD_VULKAN=OFF -DXNNPACK_ENABLE_ARM_SME2=ON \
    -DFLATCC_ALLOW_WERROR=OFF -DSUPPORT_REGEX_LOOKAHEAD=ON -Bcmake-out-android-arm64-v8a
cmake --build cmake-out-android-arm64-v8a -j "$(nproc)" --target install --config Release
SO=$(ls cmake-out-android-arm64-v8a/extension/android/*.so)
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip "$SO"
# minSdk 23: символ с версией LIBC_N/O/... (например __pwrite_chk@LIBC_N при ANDROID_PLATFORM=android-26) — dlopen на
# Android 6 падает «cannot locate symbol», сервис вылетает на каждом синтезе.
if "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-nm -D --undefined-only "$SO" | grep '@LIBC_'; then echo "импорт libc новее API 23" >&2; exit 1; fi
# Готовый AAR минус его libexecutorch.so для arm64 (x86_64 остаётся из готового) плюс наш.
rm -rf "$WORK/aar" && mkdir -p "$WORK/aar/jni/arm64-v8a" && cd "$WORK/aar" && unzip -q ../executorch.aar
cp "$WORK/executorch/$SO" jni/arm64-v8a/libexecutorch.so
rm -f "$AAR" && zip -q -r "$AAR" . && ls -la "$AAR"
