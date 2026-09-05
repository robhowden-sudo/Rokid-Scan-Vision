package com.rokid.scanvision.phone;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.util.Size;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class VisionController implements AutoCloseable {
    interface DetectionSink { void accept(int width,int height,JSONArray detections); }

    private final MainActivity activity;
    private final PreviewView previewView;
    private final DetectionSink sink;
    private final Consumer<String> status;
    private final ExecutorService cameraExecutor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing=new AtomicBoolean(false);
    private final ObjectDetector detector;
    private ProcessCameraProvider provider;

    VisionController(MainActivity activity,PreviewView previewView,DetectionSink sink,Consumer<String> status){
        this.activity=activity; this.previewView=previewView; this.sink=sink; this.status=status;
        ObjectDetectorOptions options=new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .build();
        detector=ObjectDetection.getClient(options);
    }

    void start(){
        status.accept("VISION // CAMERA STARTING");
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(activity);
        future.addListener(()->{
            try { provider=future.get(); bind(); }
            catch(Exception e){status.accept("CAMERA ERROR // "+e.getClass().getSimpleName());}
        },ContextCompat.getMainExecutor(activity));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bind(){
        Preview preview=new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageAnalysis analysis=new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640,480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor,this::analyze);
        provider.unbindAll();
        provider.bindToLifecycle(activity,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);
        status.accept("VISION // CAMERA ACTIVE");
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(ImageProxy proxy){
        Image mediaImage=proxy.getImage();
        if(mediaImage==null || !processing.compareAndSet(false,true)){proxy.close();return;}
        int rotation=proxy.getImageInfo().getRotationDegrees();
        int width=(rotation==90||rotation==270)?proxy.getHeight():proxy.getWidth();
        int height=(rotation==90||rotation==270)?proxy.getWidth():proxy.getHeight();
        InputImage input=InputImage.fromMediaImage(mediaImage,rotation);
        detector.process(input)
                .addOnSuccessListener(objects->publish(width,height,objects))
                .addOnFailureListener(e->status.accept("VISION ERROR // "+e.getClass().getSimpleName()))
                .addOnCompleteListener(task->{processing.set(false);proxy.close();});
    }

    void analyzeJpeg(byte[] jpeg,Runnable complete){
        if(jpeg==null||jpeg.length==0){status.accept("GLASSES CAMERA // EMPTY IMAGE");complete.run();return;}
        if(!processing.compareAndSet(false,true)){complete.run();return;}
        Bitmap bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
        if(bitmap==null){processing.set(false);status.accept("GLASSES CAMERA // DECODE FAILED");complete.run();return;}
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        detector.process(InputImage.fromBitmap(bitmap,0))
                .addOnSuccessListener(objects->publish(width,height,objects))
                .addOnFailureListener(e->status.accept("VISION ERROR // "+e.getClass().getSimpleName()))
                .addOnCompleteListener(task->{bitmap.recycle();processing.set(false);complete.run();});
    }

    private void publish(int width,int height,List<DetectedObject> objects){
        JSONArray array=new JSONArray();
        for(DetectedObject object:objects){
            try{
                Rect box=object.getBoundingBox();
                String label="OBJECT"; float confidence=1f;
                if(!object.getLabels().isEmpty()){
                    DetectedObject.Label best=object.getLabels().get(0);
                    label=best.getText().isEmpty()?"OBJECT":best.getText().toUpperCase(Locale.ROOT);
                    confidence=best.getConfidence();
                }
                JSONObject d=new JSONObject(); d.put("label",label); d.put("confidence",confidence);
                d.put("left",clamp(box.left/(double)width)); d.put("top",clamp(box.top/(double)height));
                d.put("right",clamp(box.right/(double)width)); d.put("bottom",clamp(box.bottom/(double)height));
                if(object.getTrackingId()!=null) d.put("trackingId",object.getTrackingId());
                array.put(d);
            }catch(Exception ignored){}
        }
        sink.accept(width,height,array);
        status.accept("VISION // "+objects.size()+" TARGET"+(objects.size()==1?"":"S"));
    }

    private static double clamp(double value){return Math.max(0d,Math.min(1d,value));}

    @Override public void close(){
        if(provider!=null) provider.unbindAll();
        detector.close(); cameraExecutor.shutdownNow();
    }
}
