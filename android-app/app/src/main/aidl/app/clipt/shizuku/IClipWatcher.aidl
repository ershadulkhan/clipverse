package app.clipt.shizuku;

/** Runs inside Shizuku as the shell user. */
interface IClipWatcher {
    /** Shizuku calls this to stop the service. */
    void destroy() = 16777114;
    /** Where to upload copies. Safe to call again with new values. */
    void configure(String serverUrl, String token, String deviceId, String deviceName) = 1;
    /** Human-readable state, for the Settings screen. */
    String status() = 2;
}
