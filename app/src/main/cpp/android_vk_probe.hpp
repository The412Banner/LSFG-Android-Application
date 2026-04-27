#pragma once

#include <string>

namespace lsfg_android {

constexpr int kProbeNoVulkan = -10;
constexpr int kProbeMissingSpirv = -11;
constexpr int kProbeDriverRejected = -12;

// Creates a headless Vulkan device and runs vkCreateShaderModule over every
// cached SPIR-V blob. Returns kOk iff all shaders are accepted.
int probe_shaders_on_device(const std::string &cacheDir);

} // namespace lsfg_android
