package com.thoraim.app;

interface IAimService {
    String start(String configText) = 1;
    void stop() = 2;
    void reconfigure(String configText) = 3;
    boolean isRunning() = 4;
    String status() = 5;
    String probe() = 6;
    String diagnose() = 7;
    String testDrag(String mode, float width, float height, int displayId) = 8;
    void setMode(String mode) = 9;
    // Shizuku calls this transaction to shut a user service down. Its id is
    // fixed by Shizuku, so every method needs an explicit one alongside it.
    void destroy() = 16777114;
}
