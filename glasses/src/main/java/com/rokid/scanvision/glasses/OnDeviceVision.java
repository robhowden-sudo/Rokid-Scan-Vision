package com.rokid.scanvision.glasses;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Size;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class OnDeviceVision implements AutoCloseable {
    interface Sink { void accept(List<MainActivity.Detection> detections); }
    private static final long INFERENCE_INTERVAL_MS=250L;
    private static final float HUD_ASPECT_RATIO=480f/400f;
    // The camera sees a much wider scene than the 23-degree optical HUD. Scan
    // only the central portion so detections map to what the wearer can see.
    private static final float SCAN_WIDTH_FRACTION=.30f;
    // The camera sits above the wearer's optical display axis. Moving the scan
    // window down by a quarter of its height moves HUD markers up by about 25%.
    private static final float SCAN_VERTICAL_OFFSET=.25f;
    private final MainActivity activity;
    private final Sink sink;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing=new AtomicBoolean(false);
    private ObjectDetector detector;
    private ProcessCameraProvider provider;
    private long lastInferenceMs=0L;

    OnDeviceVision(MainActivity activity,Sink sink){this.activity=activity;this.sink=sink;}

    void start(){
        try{
            BaseOptions base=BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build();
            ObjectDetector.ObjectDetectorOptions options=ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(base).setMaxResults(6).setScoreThreshold(.50f).build();
            detector=ObjectDetector.createFromOptions(activity,options);
        }catch(RuntimeException e){activity.setVisionStatus("MODEL // "+e.getClass().getSimpleName());return;}
        ProcessCameraProvider.getInstance(activity).addListener(()->{
            try{
                provider=ProcessCameraProvider.getInstance(activity).get();
                ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(640,480))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(executor,this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(activity,CameraSelector.DEFAULT_BACK_CAMERA,analysis);
                activity.setVisionStatus("VISION // 80-CLASS LOCAL");
            }catch(Exception e){activity.setVisionStatus("CAMERA // "+e.getClass().getSimpleName());}
        },ContextCompat.getMainExecutor(activity));
    }

    private void analyze(ImageProxy proxy){
        long now=SystemClock.uptimeMillis();
        if(detector==null||now-lastInferenceMs<INFERENCE_INTERVAL_MS||!processing.compareAndSet(false,true)){proxy.close();return;}
        lastInferenceMs=now;
        try{
            Bitmap bitmap=rgbaBitmap(proxy);
            int rotation=proxy.getImageInfo().getRotationDegrees();
            if(rotation!=0){
                Matrix matrix=new Matrix();matrix.postRotate(rotation);
                bitmap=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
            }
            Bitmap scanRegion=centerScanRegion(bitmap);
            if(scanRegion!=bitmap)bitmap.recycle();
            MPImage image=new BitmapImageBuilder(scanRegion).build();
            ObjectDetectorResult result=detector.detect(image);
            publish(result,image.getWidth(),image.getHeight());
            image.close();
            scanRegion.recycle();
        }catch(RuntimeException e){activity.setVisionStatus("VISION // "+e.getClass().getSimpleName());}
        finally{proxy.close();processing.set(false);}
    }

    private Bitmap rgbaBitmap(ImageProxy proxy){
        ImageProxy.PlaneProxy plane=proxy.getPlanes()[0];
        int width=proxy.getWidth(),height=proxy.getHeight();
        int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride();
        int paddedWidth=width+Math.max(0,rowStride-pixelStride*width)/pixelStride;
        Bitmap padded=Bitmap.createBitmap(paddedWidth,height,Bitmap.Config.ARGB_8888);
        ByteBuffer buffer=plane.getBuffer();buffer.rewind();padded.copyPixelsFromBuffer(buffer);
        if(paddedWidth==width)return padded;
        Bitmap cropped=Bitmap.createBitmap(padded,0,0,width,height);padded.recycle();return cropped;
    }

    private Bitmap centerScanRegion(Bitmap source){
        int sourceWidth=source.getWidth(),sourceHeight=source.getHeight();
        int cropWidth=Math.max(1,Math.round(sourceWidth*SCAN_WIDTH_FRACTION));
        int cropHeight=Math.max(1,Math.round(cropWidth/HUD_ASPECT_RATIO));
        if(cropHeight>sourceHeight){
            cropHeight=sourceHeight;
            cropWidth=Math.max(1,Math.round(cropHeight*HUD_ASPECT_RATIO));
        }
        cropWidth=Math.min(cropWidth,sourceWidth);
        cropHeight=Math.min(cropHeight,sourceHeight);
        int left=(sourceWidth-cropWidth)/2;
        int centeredTop=(sourceHeight-cropHeight)/2;
        int top=centeredTop+Math.round(cropHeight*SCAN_VERTICAL_OFFSET);
        top=Math.max(0,Math.min(sourceHeight-cropHeight,top));
        return Bitmap.createBitmap(source,left,top,cropWidth,cropHeight);
    }

    private void publish(ObjectDetectorResult result,int width,int height){
        List<MainActivity.Detection> out=new ArrayList<>();
        for(Detection detection:result.detections()){
            if(detection.categories().isEmpty())continue;
            Category category=detection.categories().get(0);
            RectF b=detection.boundingBox();
            MainActivity.Detection d=new MainActivity.Detection();
            d.label=category.categoryName().isEmpty()?"OBJECT":category.categoryName().toUpperCase(Locale.ROOT);
            d.conf=category.score();
            d.l=clamp(b.left/width);d.t=clamp(b.top/height);d.r=clamp(b.right/width);d.b=clamp(b.bottom/height);
            out.add(d);
        }
        sink.accept(out);
    }

    private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
    @Override public void close(){if(provider!=null)provider.unbindAll();if(detector!=null)detector.close();executor.shutdownNow();}
}
