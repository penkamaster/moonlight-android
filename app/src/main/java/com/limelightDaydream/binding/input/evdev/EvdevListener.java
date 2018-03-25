package com.limelightDaydream.binding.input.evdev;

public interface EvdevListener {
    int BUTTON_LEFT = 1;
    int BUTTON_MIDDLE = 2;
    int BUTTON_RIGHT = 3;

    void mouseMove(int deltaX, int deltaY);
    void mouseButtonEvent(int buttonId, boolean down);
    void mouseScroll(byte amount);
    void keyboardEvent(boolean buttonDown, short keyCode);
}
