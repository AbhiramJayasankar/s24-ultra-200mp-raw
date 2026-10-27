param(
    [string]$Sdk = '<path-to-android-sdk>',
    [string]$Libjxl = 'third_party/libjxl',
    [string]$JavaHome = 'C:/Program Files/Java/jdk-25',
    [int]$Jobs = 6
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$cmake = "$Sdk/cmake/4.1.2/bin/cmake.exe"
Push-Location $repo
try {
    & $cmake -S bench -B build/jxl-tuning/host -G 'Visual Studio 17 2022' -A x64 `
        "-DLIBJXL_DIR=$Libjxl" "-DJAVA_HOME=$JavaHome" '-DCMAKE_POLICY_VERSION_MINIMUM=3.5'
    if ($LASTEXITCODE) { throw 'Host configure failed' }
    & $cmake --build build/jxl-tuning/host --config Release --target jxl_tile_bench --parallel $Jobs
    if ($LASTEXITCODE) { throw 'Host build failed' }
    & $cmake --build build/jxl-tuning/host/app-native --config Release --target s24jxl --parallel $Jobs
    if ($LASTEXITCODE) { throw 'Host JNI build failed' }
    & $cmake -S bench -B build/jxl-tuning/android -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$Sdk/cmake/4.1.2/bin/ninja.exe" `
        "-DCMAKE_TOOLCHAIN_FILE=$Sdk/ndk/30.0.16248370/build/cmake/android.toolchain.cmake" `
        '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-34' '-DANDROID_STL=c++_static' `
        '-DCMAKE_BUILD_TYPE=Release' "-DLIBJXL_DIR=$Libjxl" '-DCMAKE_POLICY_VERSION_MINIMUM=3.5'
    if ($LASTEXITCODE) { throw 'Android configure failed' }
    & $cmake --build build/jxl-tuning/android --target jxl_tile_bench --parallel $Jobs
    if ($LASTEXITCODE) { throw 'Android build failed' }
} finally {
    Pop-Location
}
