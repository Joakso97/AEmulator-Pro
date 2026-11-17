 
 
 
 
 
 
#define EXPORT __attribute__((visibility("default")))

struct CameraInfo { int facing, orientation; };

EXPORT int HAL_getNumberOfCameras(void) { return 0; }

EXPORT void HAL_getCameraInfo(int id, struct CameraInfo *info) {
    (void)id;
    if (info) { info->facing = 0; info->orientation = 0; }
}

 
EXPORT void HAL_openCameraHardware(void **result, int id) {
    (void)id;
    *result = 0;
}
