#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace lsfg_android {

// Error codes returned to Kotlin via JNI. Keep kOk == 0.
constexpr int kOk = 0;
constexpr int kErrDllUnreadable = -1;
constexpr int kErrMissingResource = -2;
constexpr int kErrTranslationFailed = -3;
constexpr int kErrWriteFailed = -4;

// Parses Lossless.dll at [dllPath], extracts every RCDATA resource that LSFG
// uses, translates DXBC → SPIR-V, and writes one file per resource
// (<cacheDir>/<resId>.spv). [cacheDir] must already exist and be writable.
int extract_dll_to_spirv(const std::string &dllPath, const std::string &cacheDir);

// Reads a cached SPIR-V file back from disk. Returns an empty vector on
// missing/unreadable files — the caller decides whether that's a fatal error.
std::vector<uint8_t> load_cached_spirv(const std::string &cacheDir, uint32_t resId);

// Maps a framegen shader name (e.g. "p_mipmaps", "p_alpha[2]", "generate")
// to the DXBC resource ID (255..302) that lives on disk as <cacheDir>/<id>.spv.
// Returns 0 if the name is unknown.
//
// Mirror of Extract::nameIdxTable in lsfg-vk-android/src/extract/extract.cpp.
uint32_t shader_name_to_resource_id(const std::string &name);

} // namespace lsfg_android
