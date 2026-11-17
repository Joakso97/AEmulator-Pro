 
 
 
 
 
 
 
 
#define LOG_TAG "composer@2.1-impl"

#include <android/hardware/graphics/composer/2.1/IComposer.h>
#include <composer-hal/2.1/Composer.h>
#include <composer-passthrough/2.1/HwcHal.h>
#include <hardware/hardware.h>
#include <hardware/hwcomposer.h>
#include <hardware/hwcomposer2.h>
#include <log/log.h>
#include <string.h>

using android::hardware::graphics::composer::V2_1::IComposer;
using android::hardware::graphics::composer::V2_1::passthrough::HwcHal;
namespace hal = android::hardware::graphics::composer::V2_1::hal;

extern "C" IComposer* HIDL_FETCH_IComposer(const char*  ) {
    const hw_module_t* module = nullptr;
    int error = hw_get_module(HWC_HARDWARE_MODULE_ID, &module);
    if (error || !module) {
        ALOGE("failed to get the hwcomposer module");
        return nullptr;
    }
    hw_device_t* device = nullptr;
    error = module->methods->open(module, HWC_HARDWARE_COMPOSER, &device);
    if (error || !device) {
        ALOGE("failed to open hwcomposer device: %s", strerror(-error));
        return nullptr;
    }
    if (((device->version >> 24) & 0xf) != 2) {
        ALOGE("the hwcomposer module is not an HWC2 device (version 0x%x), no adapter in this build", device->version);
        return nullptr;
    }
    auto hal = std::make_unique<HwcHal>();
     
    if (!hal->initWithDevice(reinterpret_cast<hwc2_device_t*>(device), true)) return nullptr;
    return hal::Composer::create(std::move(hal)).release();
}
