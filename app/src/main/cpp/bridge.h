// Button indices used by nativeSetButton() on the Kotlin side. Values are
// arbitrary (only need to be internally consistent with the MAP_BUTTON
// setup in jni_bridge.cpp) but follow the same order snes9x's own libretro
// port uses, for familiarity.
#ifndef _S9X_DECO_BRIDGE_H_
#define _S9X_DECO_BRIDGE_H_

enum S9xDecoButton {
	BTN_B = 0, BTN_Y = 1, BTN_SELECT = 2, BTN_START = 3,
	BTN_UP = 4, BTN_DOWN = 5, BTN_LEFT = 6, BTN_RIGHT = 7,
	BTN_A = 8, BTN_X = 9, BTN_L = 10, BTN_R = 11
};

#endif
