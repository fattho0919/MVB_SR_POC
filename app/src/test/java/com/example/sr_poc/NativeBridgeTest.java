package com.example.sr_poc;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;

/**
 * Unit test for NativeBridge JNI functionality.
 * Note: These tests require the native library to be loaded,
 * which may not work in standard unit test environment.
 * Consider using instrumented tests for full validation.
 */
public class NativeBridgeTest {
    
    private NativeBridge nativeBridge;
    
    @Before
    public void setUp() {
        try {
            nativeBridge = new NativeBridge();
        } catch (UnsatisfiedLinkError e) {
            // Expected in unit test environment
            System.out.println("Native library not available in unit test environment");
        }
    }
    
    @After
    public void tearDown() {
        if (nativeBridge != null) {
            nativeBridge.release();
        }
    }
    
    @Test
    public void testNativeBridgeCreation() {
        assertNotNull("NativeBridge should be created", nativeBridge);
    }
    
    @Test
    public void testIsAvailable() {
        // In unit test environment, library may not be available
        // This test mainly checks that the method doesn't throw
        boolean available = nativeBridge.isAvailable();
        System.out.println("Native library available: " + available);
    }
    
    @Test
    public void testGetVersion() {
        String version = nativeBridge.getVersion();
        assertNotNull("Version should not be null", version);
        // In unit test environment, might return "N/A - Library not loaded"
        System.out.println("Native version: " + version);
    }
    
    @Test
    public void testBenchmarkWithInvalidLibrary() {
        // When library is not loaded, should return -1
        if (!nativeBridge.isAvailable()) {
            long result = nativeBridge.benchmark(100);
            assertEquals("Benchmark should return -1 when library not loaded", 
                        -1, result);
        }
    }
    
    @Test
    public void testDirectBufferWithInvalidLibrary() {
        // When library is not loaded, should return false
        if (!nativeBridge.isAvailable()) {
            boolean result = nativeBridge.testDirectBuffer();
            assertFalse("DirectBuffer test should return false when library not loaded", 
                       result);
        }
    }
    
    @Test
    public void testInitializeWithInvalidLibrary() {
        // When library is not loaded, should return false
        if (!nativeBridge.isAvailable()) {
            boolean result = nativeBridge.initialize("test_model.tflite", 4);
            assertFalse("Initialize should return false when library not loaded", 
                       result);
        }
    }
    
    @Test
    public void testProcessImageWithInvalidParameters() {
        ByteBuffer input = ByteBuffer.allocateDirect(1024);
        ByteBuffer output = ByteBuffer.allocateDirect(1024);
        
        // Should handle uninitialized engine gracefully
        boolean result = nativeBridge.processImage(input, output, 100, 100);
        
        if (!nativeBridge.isAvailable()) {
            assertFalse("Process should fail when library not loaded", result);
        } else if (!nativeBridge.isInitialized()) {
            assertFalse("Process should fail when engine not initialized", result);
        }
    }
    
    @Test
    public void testMultipleReleases() {
        // Should handle multiple releases gracefully
        nativeBridge.release();
        nativeBridge.release(); // Second release should not crash
        
        assertFalse("Should not be initialized after release", 
                   nativeBridge.isInitialized());
    }
}