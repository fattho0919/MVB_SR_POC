package com.example.sr_poc.utils;

public final class Constants {
    
    private Constants() {
        // Prevent instantiation
    }
    
    // Memory thresholds
    public static final int LOW_MEMORY_WARNING_MB = 100;
    public static final double MEMORY_USAGE_THRESHOLD = 0.5;
    
    // UI dimensions
    public static final int LABEL_HEIGHT = 40;
    public static final int LABEL_PADDING = 12;
    public static final int LABEL_TEXT_SIZE = 22;
    
    // Image processing
    public static final int DEFAULT_OVERLAP_PIXELS = 32;
    public static final int MIN_VIEW_HEIGHT = 300;
    public static final int LARGE_IMAGE_PIXEL_THRESHOLD = 1_000_000;
    public static final int MAX_CONVERSION_THREADS = 8;
    
    // File paths
    public static final String IMAGES_PATH = "images/";
    public static final String CONFIG_FILE_PATH = "config/sr_config.json";
    
    // Performance
    public static final float RGB_TO_FLOAT_MULTIPLIER = 0.003921569f; // 1/255.0f
    
    // Colors
    public static final int COLOR_WHITE = 0xFFFFFFFF;
    public static final int COLOR_SEMI_TRANSPARENT_BLACK = 0x80000000;
    public static final int COLOR_LIGHT_SHADOW = 0x40000000;
    public static final int COLOR_DARK_SHADOW = 0x80000000;
    
    // Thread pool settings
    public static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5;
}