package com.example.sr_poc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ImageManager {
    
    private static final String TAG = "ImageManager";
    private static final String IMAGES_PATH = "images/";
    
    private Context context;
    private List<String> imageNames;
    private int currentIndex;
    private Bitmap currentBitmap;
    
    public ImageManager(Context context) {
        this.context = context;
        this.currentIndex = 0;
        initializeImageList();
        loadCurrentImage();
    }
    
    private void initializeImageList() {
        imageNames = new ArrayList<>(Arrays.asList(
            "d1.png", "d2.png", "d3.png",
            "k1.png", "k2.png", "k3.png", 
            "o1.png", "o2.png", "o3.png",
            "w1.png", "w2.png", "w3.png"
        ));
        Log.d(TAG, "Initialized with " + imageNames.size() + " images");
    }
    
    public void switchToNext() {
        currentIndex = (currentIndex + 1) % imageNames.size();
        loadCurrentImage();
        Log.d(TAG, "Switched to image: " + getCurrentImageName());
    }
    
    public void switchToPrevious() {
        currentIndex = (currentIndex - 1 + imageNames.size()) % imageNames.size();
        loadCurrentImage();
        Log.d(TAG, "Switched to image: " + getCurrentImageName());
    }
    
    private void loadCurrentImage() {
        String imageName = imageNames.get(currentIndex);
        String imagePath = IMAGES_PATH + imageName;
        
        try (InputStream inputStream = context.getAssets().open(imagePath)) {
            currentBitmap = BitmapFactory.decodeStream(inputStream);
            
            if (currentBitmap != null) {
                Log.d(TAG, String.format("Loaded image: %s (%dx%d)", 
                    imageName, 
                    currentBitmap.getWidth(), 
                    currentBitmap.getHeight()));
            } else {
                Log.e(TAG, "Failed to decode image: " + imageName);
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to load image: " + imageName, e);
            currentBitmap = null;
        }
    }
    
    public Bitmap getCurrentBitmap() {
        return currentBitmap;
    }
    
    public String getCurrentImageName() {
        if (currentIndex >= 0 && currentIndex < imageNames.size()) {
            return imageNames.get(currentIndex);
        }
        return "unknown";
    }
    
    public int getCurrentIndex() {
        return currentIndex;
    }
    
    public int getTotalImages() {
        return imageNames.size();
    }
    
    public void setCurrentIndex(int index) {
        if (index >= 0 && index < imageNames.size()) {
            currentIndex = index;
            loadCurrentImage();
            Log.d(TAG, "Set current index to: " + index + " (" + getCurrentImageName() + ")");
        } else {
            Log.w(TAG, "Invalid index: " + index);
        }
    }
    
    public List<String> getAllImageNames() {
        return new ArrayList<>(imageNames);
    }
}