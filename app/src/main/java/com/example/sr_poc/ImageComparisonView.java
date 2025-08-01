package com.example.sr_poc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.example.sr_poc.utils.Constants;

public class ImageComparisonView extends View {
    private static final String TAG = "ImageComparisonView";
    
    private Bitmap originalBitmap;
    private Bitmap processedBitmap;
    private float dividerPosition = 0.5f; // 0.0 = 完全顯示原圖, 1.0 = 完全顯示處理後的圖
    private Paint linePaint;
    private Paint textPaint;
    private Paint backgroundPaint;
    private Paint shadowPaint;
    private boolean isDragging = false;
    
    
    public ImageComparisonView(Context context) {
        super(context);
        init();
    }
    
    public ImageComparisonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public ImageComparisonView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // 分割線畫筆
        linePaint = new Paint();
        linePaint.setColor(0xFFFFFFFF); // 白色
        linePaint.setStrokeWidth(6f);
        linePaint.setAntiAlias(true);
        linePaint.setShadowLayer(4f, 0f, 2f, 0x80000000); // 陰影效果
        
        // 文字畫筆
        textPaint = new Paint();
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(Constants.LABEL_TEXT_SIZE);
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setShadowLayer(3f, 0f, 1f, 0x80000000);
        
        // 標籤背景畫筆
        backgroundPaint = new Paint();
        backgroundPaint.setColor(0x80000000); // 半透明黑色
        backgroundPaint.setAntiAlias(true);
        
        // 陰影畫筆
        shadowPaint = new Paint();
        shadowPaint.setColor(0x40000000); // 淡陰影
        shadowPaint.setAntiAlias(true);
        
        setLayerType(LAYER_TYPE_SOFTWARE, null); // 啟用陰影效果
    }
    
    public void setOriginalBitmap(Bitmap bitmap) {
        this.originalBitmap = bitmap;
        invalidate();
    }
    
    public void setProcessedBitmap(Bitmap bitmap) {
        this.processedBitmap = bitmap;
        invalidate();
    }
    
    public void setDividerPosition(float position) {
        this.dividerPosition = Math.max(0f, Math.min(1f, position));
        invalidate();
    }
    
    public float getDividerPosition() {
        return dividerPosition;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        
        if (width == 0 || height == 0) return;
        
        // 計算實際的分割線位置
        int dividerX = (int) (width * dividerPosition);
        
        // 繪製原圖（左側）
        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            drawBitmapSection(canvas, originalBitmap, 0, 0, dividerX, height, true);
        }
        
        // 繪製處理後的圖（右側）
        if (processedBitmap != null && !processedBitmap.isRecycled()) {
            drawBitmapSection(canvas, processedBitmap, dividerX, 0, width - dividerX, height, false);
        }
        
        // 繪製分割線
        canvas.drawLine(dividerX, 0, dividerX, height, linePaint);
        
        // 繪製圓形拖動把手
        float handleRadius = 24f;
        float handleY = height / 2f;
        
        // 繪製把手陰影
        canvas.drawCircle(dividerX + 2, handleY + 2, handleRadius, shadowPaint);
        // 繪製把手
        canvas.drawCircle(dividerX, handleY, handleRadius, linePaint);
        
        // 繪製標籤
        drawLabels(canvas, width, height, dividerX);
    }
    
    private void drawBitmapSection(Canvas canvas, Bitmap bitmap, int x, int y, int width, int height, boolean isLeft) {
        if (bitmap == null || bitmap.isRecycled()) return;
        
        // 計算圖片的縮放比例以fit到view中
        float bitmapWidth = bitmap.getWidth();
        float bitmapHeight = bitmap.getHeight();
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        
        float scaleX = viewWidth / bitmapWidth;
        float scaleY = viewHeight / bitmapHeight;
        float scale = Math.min(scaleX, scaleY);
        
        float scaledWidth = bitmapWidth * scale;
        float scaledHeight = bitmapHeight * scale;
        
        // 計算居中位置
        float offsetX = (viewWidth - scaledWidth) / 2f;
        float offsetY = (viewHeight - scaledHeight) / 2f;
        
        // 設置裁剪區域
        canvas.save();
        canvas.clipRect(x, y, x + width, y + height);
        
        // 繪製圖片
        RectF destRect = new RectF(offsetX, offsetY, offsetX + scaledWidth, offsetY + scaledHeight);
        canvas.drawBitmap(bitmap, null, destRect, null);
        
        canvas.restore();
    }
    
    private void drawLabels(Canvas canvas, int width, int height, int dividerX) {
        // 左側標籤 (原圖)
        if (dividerX > 100) { // 只有當左側有足夠空間時才繪製
            String leftLabel = "原圖";
            float leftTextWidth = textPaint.measureText(leftLabel);
            float leftLabelX = (dividerX - leftTextWidth) / 2f;
            float labelY = Constants.LABEL_HEIGHT / 2f + Constants.LABEL_TEXT_SIZE / 3f;
            
            // 繪製標籤背景
            RectF leftBgRect = new RectF(
                leftLabelX - Constants.LABEL_PADDING,
                Constants.LABEL_PADDING,
                leftLabelX + leftTextWidth + Constants.LABEL_PADDING,
                Constants.LABEL_HEIGHT
            );
            canvas.drawRoundRect(leftBgRect, 12f, 12f, backgroundPaint);
            
            // 繪製文字
            canvas.drawText(leftLabel, leftLabelX, labelY, textPaint);
        }
        
        // 右側標籤 (SR)
        if (width - dividerX > 100) { // 只有當右側有足夠空間時才繪製
            String rightLabel = "SR 結果";
            float rightTextWidth = textPaint.measureText(rightLabel);
            float rightLabelX = dividerX + (width - dividerX - rightTextWidth) / 2f;
            float labelY = Constants.LABEL_HEIGHT / 2f + Constants.LABEL_TEXT_SIZE / 3f;
            
            // 繪製標籤背景
            RectF rightBgRect = new RectF(
                rightLabelX - Constants.LABEL_PADDING,
                Constants.LABEL_PADDING,
                rightLabelX + rightTextWidth + Constants.LABEL_PADDING,
                Constants.LABEL_HEIGHT
            );
            canvas.drawRoundRect(rightBgRect, 12f, 12f, backgroundPaint);
            
            // 繪製文字
            canvas.drawText(rightLabel, rightLabelX, labelY, textPaint);
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDragging = true;
                updateDividerPosition(x);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
                
            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    updateDividerPosition(x);
                }
                return true;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        
        return super.onTouchEvent(event);
    }
    
    private void updateDividerPosition(float x) {
        float newPosition = x / getWidth();
        setDividerPosition(newPosition);
        Log.d(TAG, "Divider position updated: " + dividerPosition);
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        
        // 確保視圖有合適的最小高度
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        
        if (height < Constants.MIN_VIEW_HEIGHT) {
            height = Constants.MIN_VIEW_HEIGHT;
        }
        
        setMeasuredDimension(width, height);
    }
}
