
package app.aemu.ui

class VmActivity1 : VmActivity()
class VmActivity2 : VmActivity()
class VmActivity3 : VmActivity()


class VmOverlayActivity : VmActivity() { override val overlayVariant get() = true }
class VmOverlayActivity1 : VmActivity() { override val overlayVariant get() = true }
class VmOverlayActivity2 : VmActivity() { override val overlayVariant get() = true }
class VmOverlayActivity3 : VmActivity() { override val overlayVariant get() = true }
