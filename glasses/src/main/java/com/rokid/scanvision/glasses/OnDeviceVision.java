package com.rokid.scanvision.glasses;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.media.Image;
import android.util.Size;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class OnDeviceVision implements AutoCloseable {
    interface Sink { void accept(List<MainActivity.Detection> detections); }

    private final MainActivity activity;
    private final Sink sink;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing=new AtomicBoolean(false);
    private final ObjectDetector detector=ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE).enableClassification().build());
    private ProcessCameraProvider provider;

    OnDeviceVision(MainActivity activity,Sink sink){this.activity=activity;this.sink=sink;}

    void start(){
        ProcessCameraProvider.getInstance(activity).addListener(()->{
            try{
                provider=ProcessCameraProvider.getInstance(activity).get();
                ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(640,480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(executor,this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(activity,CameraSelector.DEFAULT_BACK_CAMERA,analysis);
                activity.setVisionStatus("VISION // LOCAL CAMERA ACTIVE");
            }catch(Exception e){activity.setVisionStatus("CAMERA // "+e.getClass().getSimpleName());}
        },ContextCompat.getMainExecutor(activity));
    }

    @SuppressLint("UnsafeOptInUsageError") private void analyze(ImageProxy proxy){
        Image image=proxy.getImage();
        if(image==null||!processing.compareAndSet(false,true)){proxy.close();return;}
        int rotation=proxy.getImageInfo().getRotationDegrees();
        int width=(rotation==90||rotation==270)?proxy.getHeight():proxy.getWidth();
        int height=(rotation==90||rotation==270)?proxy.getWidth():proxy.getHeight();
        detector.process(InputImage.fromMediaImage(image,rotation))
                .addOnSuccessListener(objects->publish(objects,width,height))
                .addOnFailureListener(e->activity.setVisionStatus("VISION // "+e.getClass().getSimpleName()))
                .addOnCompleteListener(t->{processing.set(false);proxy.close();});
    }

    private void publish(List<DetectedObject> objects,int width,int height){
        List<MainActivity.Detection> out=new ArrayList<>();
        for(DetectedObject object:objects){
            Rect b=object.getBoundingBox();
            MainActivity.Detection d=new MainActivity.Detection();
            d.label="OBJECT"; d.conf=1f;
            if(!object.getLabels().isEmpty()){
                DetectedObject.Label label=object.getLabels().get(0);
                d.label=label.getText().isEmpty()?"OBJECT":label.getText().toUpperCase(Locale.ROOT);
                d.conf=label.getConfidence();
            }
            d.l=clamp(b.left/(float)width); d.t=clamp(b.top/(float)height); d.r=clamp(b.right/(float)width); d.b=clamp(b.bottom/(float)height);
            out.add(d);
        }
        sink.accept(out);
    }

    private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
    @Override public void close(){if(provider!=null)provider.unbindAll();detector.close();executor.shutdownNow();}
}
