 
 
 
 
 
 
 
 
 
#include <android/hardware/graphics/composer/2.1/IComposer.h>
#include <composer-passthrough/2.1/HwcLoader.h>

using android::hardware::graphics::composer::V2_1::IComposer;
using android::hardware::graphics::composer::V2_1::passthrough::HwcLoader;

extern "C" IComposer* HIDL_FETCH_IComposer(const char*  ) {
    return HwcLoader::load();
}
