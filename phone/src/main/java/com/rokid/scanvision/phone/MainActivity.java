package com.rokid.scanvision.phone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.view.PreviewView;

import com.rokid.cxr.Caps;
import com.rokid.cxr.link.CXRLink;
import com.rokid.cxr.link.callbacks.ICXRLinkCbk;
import com.rokid.cxr.link.callbacks.ICXRSessionCbk;
import com.rokid.cxr.link.callbacks.IImageStreamCbk;
import com.rokid.cxr.link.callbacks.ICustomCmdCbk;
import com.rokid.cxr.link.callbacks.IGlassAppCbk;
import com.rokid.cxr.link.utils.CxrDefs;
import com.rokid.cxr.link.utils.GlassInfo;
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult;
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper;
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final String CHANNEL="rokid_scan_vision_state";
    private static final String TAG="ScanVision";
    private static final int REQ_AUTH=902;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CXRLink link;
    private volatile boolean connected=false,sessionReady=false,appStartRequested=false,glassesVisionRunning=false,glassesPhotoPending=false;
    private TextView status;
    private Button glassesCamera;
    private VisionController vision;
    private volatile long photoRequestId=0,framesReceived=0;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        link=new CXRLink(this); configureLink(); setContentView(buildUi()); requestPermissionsIfNeeded();
    }

    private LinearLayout buildUi(){
        int green=Color.rgb(79,255,159),soft=Color.rgb(174,244,202);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(36,60,36,36); root.setBackgroundColor(Color.BLACK);
        TextView title=txt("ROKID // SCAN VISION",26,green); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView version=txt("VISION HUD  v0.2.3",14,soft); version.setGravity(Gravity.CENTER); version.setPadding(0,8,0,36); root.addView(version);
        status=txt("PHONE READY // GLASSES DISCONNECTED",16,green); status.setPadding(0,0,0,24); root.addView(status);
        Button connect=new Button(this); connect.setText("CONNECT THROUGH HI ROKID"); connect.setOnClickListener(v->authorize()); root.addView(connect,new LinearLayout.LayoutParams(-1,-2));
        Button test=new Button(this); test.setText("SEND TEST TARGETS"); test.setOnClickListener(v->worker.execute(this::sendTestPacket)); root.addView(test,new LinearLayout.LayoutParams(-1,-2));
        PreviewView preview=new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(-1,0,1f); previewParams.setMargins(0,24,0,24); root.addView(preview,previewParams);
        vision=new VisionController(this,preview,(width,height,detections)->worker.execute(()->sendDetections(width,height,detections)),this::setStatus);
        glassesCamera=new Button(this); glassesCamera.setText("START GLASSES CAMERA VISION"); glassesCamera.setOnClickListener(v->{
            setGlassesVisionRunning(!glassesVisionRunning);
            if(glassesVisionRunning) requestGlassesPhoto();
        }); root.addView(glassesCamera,new LinearLayout.LayoutParams(-1,-2));
        Button camera=new Button(this); camera.setText("PHONE CAMERA // DIAGNOSTIC MODE"); camera.setOnClickListener(v->{
            if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) vision.start();
            else requestPermissions(new String[]{Manifest.permission.CAMERA},903);
        }); root.addView(camera,new LinearLayout.LayoutParams(-1,-2));
        TextView note=txt("Normal mode requests images from the camera built into the Rokid glasses, processes them on this phone, then returns normalized detection boxes to the HUD. The phone camera remains available only for diagnostics.",15,soft); note.setPadding(0,24,0,24); root.addView(note);
        Button hi=new Button(this); hi.setText("OPEN HI ROKID"); hi.setOnClickListener(v->{Intent i=getPackageManager().getLaunchIntentForPackage("com.rokid.sprite.global.aiapp");if(i!=null)startActivity(i);else setStatus("HI ROKID APP NOT FOUND");});root.addView(hi,new LinearLayout.LayoutParams(-1,-2));
        return root;
    }

    private TextView txt(String s,float sp,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);return t;}
    private void setStatus(String s){Log.i(TAG,s);runOnUiThread(()->status.setText(s));}

    private void setGlassesVisionRunning(boolean running){
        glassesVisionRunning=running;
        if(!running){glassesPhotoPending=false;photoRequestId++;}
        runOnUiThread(()->glassesCamera.setText(running?"STOP GLASSES CAMERA VISION":"START GLASSES CAMERA VISION"));
        if(!running)setStatus("GLASSES VISION // STOPPED");
    }

    private void requestPermissionsIfNeeded(){
        if(Build.VERSION.SDK_INT>=31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},901);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==903 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) vision.start();
    }

    private void authorize(){
        try {
            if(!AuthorizationHelper.INSTANCE.isRequiredHiRokidInstalled(this)){setStatus("COMPATIBLE HI ROKID APP REQUIRED");return;}
            GlassPermission[] permissions={GlassPermission.CAMERA};
            Pair<Integer,Intent> immediate=AuthorizationHelper.INSTANCE.requestAuthorization(this,permissions,REQ_AUTH);
            setStatus("HI ROKID // AUTH REQUESTED");
            if(immediate!=null) handleAuthorization(immediate.first,immediate.second);
        }
        catch(Throwable t){ setStatus("HI ROKID AUTH // "+t.getClass().getSimpleName()); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_AUTH) handleAuthorization(resultCode,data);
    }

    private void handleAuthorization(int resultCode,Intent data){
        try{
            AuthResult result=AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode,data);
            if(result instanceof AuthResult.AuthSuccess){
                if(!AuthorizationHelper.INSTANCE.hasGlassPermission(GlassPermission.CAMERA)){setStatus("HI ROKID // CAMERA PERMISSION DENIED");return;}
                setStatus("HI ROKID // CAMERA AUTHORIZED");link.connect(((AuthResult.AuthSuccess)result).getToken());
            }
            else if(result instanceof AuthResult.AuthCancel)setStatus("HI ROKID // AUTH CANCELLED");
            else setStatus("HI ROKID // AUTH FAILED");
        }catch(Throwable t){setStatus("AUTH RESULT // ERROR");}
    }

    private void configureLink(){
        CxrDefs.CXRSession session=new CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP,"com.rokid.scanvision.glasses");
        link.configCXRSession(session,new ICXRSessionCbk(){
            @Override public void onSessionAvailable(CxrDefs.CXRSessionReason reason){startGlassesApp();}
            @Override public void onSessionStart(CxrDefs.CXRSessionReason reason){sessionReady=true;connected=true;setStatus("SCAN VISION // LINK READY");if(glassesVisionRunning)requestGlassesPhoto();}
            @Override public void onSessionPause(CxrDefs.CXRSessionReason reason){sessionReady=false;glassesPhotoPending=false;setStatus("SCAN VISION // SESSION PAUSED");}
            @Override public void onSessionUnavailable(CxrDefs.CXRSessionReason reason){sessionReady=false;appStartRequested=false;glassesPhotoPending=false;setStatus("SCAN VISION // SESSION UNAVAILABLE");}
        });
        link.setCXRCustomCmdCbk(new ICustomCmdCbk(){@Override public void onCustomCmdResult(String c,byte[] d){}});
        link.setCXRImageCbk(new IImageStreamCbk(){
            @Override public void onImageReceived(byte[] data){
                glassesPhotoPending=false;
                if(!glassesVisionRunning)return;
                framesReceived++;
                setStatus("GLASSES FRAME // "+framesReceived+" // "+(data==null?0:data.length)+" BYTES");
                vision.analyzeJpeg(data,MainActivity.this::requestNextGlassesPhoto);
            }
            @Override public void onImageError(int code,String message){
                glassesPhotoPending=false;
                setStatus("GLASSES CAMERA ERROR // "+code);
                requestNextGlassesPhoto(1000L);
            }
        });
        link.setCXRLinkCbk(new ICXRLinkCbk(){
            @Override public void onCXRLConnected(boolean value){setStatus(value?"HI ROKID LINK // CONNECTED":"HI ROKID LINK // DISCONNECTED");}
            @Override public void onGlassBtConnected(boolean value){connected=value;if(value){setStatus("GLASSES // CONNECTED");link.getGlassDeviceInfo();}else{sessionReady=false;appStartRequested=false;setGlassesVisionRunning(false);setStatus("GLASSES // DISCONNECTED");}}
            @Override public void onGlassDeviceInfo(GlassInfo info){}
            @Override public void onGlassWearingStatus(boolean wearing){}
            @Override public void onGlassAiAssistStart(){}
            @Override public void onGlassAiAssistStop(){}
            @Override public void onGlassAiInterrupt(boolean interrupted){}
            @Override public void onGlassLauncherResume(){}
        });
    }

    private void startGlassesApp(){
        if(appStartRequested)return; appStartRequested=true;
        link.appStart("com.rokid.scanvision.glasses.MainActivity",new IGlassAppCbk(){
            @Override public void onInstallAppResult(boolean success){}
            @Override public void onUnInstallAppResult(boolean success){}
            @Override public void onStopAppResult(boolean success){}
            @Override public void onQueryAppResult(boolean installed){}
            @Override public void onOpenAppResult(boolean success){appStartRequested=success;if(success)setStatus("GLASSES HUD // OPEN // WAITING FOR MEDIA SESSION");else setStatus("GLASSES HUD // LAUNCH FAILED");}
            @Override public void onGlassAppResume(boolean resumed){if(resumed)setStatus("GLASSES HUD // RESUMED // WAITING FOR MEDIA SESSION");}
        });
    }

    private synchronized void requestGlassesPhoto(){
        if(!glassesVisionRunning||glassesPhotoPending)return;
        if(!sessionReady){setStatus("GLASSES CAMERA // WAITING FOR SESSION");return;}
        if(!AuthorizationHelper.INSTANCE.hasGlassPermission(GlassPermission.CAMERA)){
            setGlassesVisionRunning(false);
            setStatus("GLASSES CAMERA PERMISSION MISSING // REAUTHORIZE");
            return;
        }
        try{
            glassesPhotoPending=true;
            long requestId=++photoRequestId;
            boolean accepted=link.takePhoto(1024,768,80);
            if(!accepted){glassesPhotoPending=false;setStatus("GLASSES CAMERA // REQUEST REJECTED // "+link.getCXRSessionState());requestNextGlassesPhoto(1000L);}
            else {
                setStatus("GLASSES CAMERA // REQUEST ACCEPTED");
                status.postDelayed(()->onPhotoTimeout(requestId),5000L);
            }
        }catch(Throwable t){glassesPhotoPending=false;setStatus("GLASSES CAMERA // "+t.getClass().getSimpleName());requestNextGlassesPhoto(1000L);}
    }

    private synchronized void onPhotoTimeout(long requestId){
        if(!glassesVisionRunning||!glassesPhotoPending||requestId!=photoRequestId)return;
        glassesPhotoPending=false;
        setStatus("GLASSES CAMERA // NO FRAME RETURNED");
        requestNextGlassesPhoto(1000L);
    }

    private void requestNextGlassesPhoto(){
        requestNextGlassesPhoto(180L);
    }

    private void requestNextGlassesPhoto(long delayMs){
        if(status!=null)status.postDelayed(this::requestGlassesPhoto,delayMs);
    }

    private void sendTestPacket(){
        if(!sessionReady){setStatus("WAITING FOR GLASSES SESSION");return;}
        try{
            JSONObject root=new JSONObject();root.put("type","scan_state");root.put("frameWidth",640);root.put("frameHeight",480);
            JSONArray a=new JSONArray();
            a.put(det("PERSON",.96,.17,.20,.48,.83));
            a.put(det("VEHICLE",.91,.57,.39,.92,.70));
            root.put("detections",a);
            Caps caps=new Caps();caps.write(root.toString());
            link.sendCustomCmd(CHANNEL,caps);
            setStatus("TEST TARGETS // SENT");
        }catch(Exception e){setStatus("SEND FAILED // "+e.getClass().getSimpleName());}
    }

    private void sendDetections(int width,int height,JSONArray detections){
        if(!sessionReady) return;
        try{
            JSONObject root=new JSONObject(); root.put("type","scan_state"); root.put("frameWidth",width); root.put("frameHeight",height); root.put("detections",detections);
            Caps caps=new Caps(); caps.write(root.toString());
            link.sendCustomCmd(CHANNEL,caps);
        }catch(Exception e){setStatus("VISION SEND // "+e.getClass().getSimpleName());}
    }

    @Override protected void onDestroy(){
        if(vision!=null) vision.close();
        setGlassesVisionRunning(false);
        glassesPhotoPending=false;
        try{link.disconnect();}catch(Exception ignored){}
        worker.shutdownNow();
        super.onDestroy();
    }

    private JSONObject det(String label,double confidence,double l,double t,double r,double b)throws Exception{
        JSONObject o=new JSONObject();o.put("label",label);o.put("confidence",confidence);o.put("left",l);o.put("top",t);o.put("right",r);o.put("bottom",b);return o;
    }
}
