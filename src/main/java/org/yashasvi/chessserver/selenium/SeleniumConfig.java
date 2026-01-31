package org.yashasvi.chessserver.selenium;

public final class SeleniumConfig {
    private SeleniumConfig() {
    }

    public static boolean isRemote() {
        // Option A: JVM property
        String prop = System.getProperty("selenium.remote");
        if ("true".equalsIgnoreCase(prop)) return true;

        // Option B: program args passed as --selenium=remote
        String arg = System.getProperty("selenium.mode");
        return "remote".equalsIgnoreCase(arg);
    }

    public static String remoteUrl() {
        String envUrl = System.getenv("SELENIUM_REMOTE_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl;
        }
        return System.getProperty("selenium.remote.url", "http://127.0.0.1:4444/wd/hub");
    }
}
