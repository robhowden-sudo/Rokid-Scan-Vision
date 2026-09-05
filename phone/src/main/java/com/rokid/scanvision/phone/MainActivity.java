package com.rokid.scanvision.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rokid.cxr.Caps;
import com.rokid.cxr.link.CXRLink;
import com.rokid.cxr.link.callbacks.ICXRLinkCbk;
import com.rokid.cxr.link.callbacks.ICXRSessionCbk;
import com.rokid.cxr.link.callbacks.ICustomCmdCbk;
import com.rokid.cxr.link.callbacks.IGlassAppCbk;
import com.rokid.cxr.link.utils.CxrDefs;
import com.rokid.cxr.link.utils.GlassInfo;
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult;
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String CHANNEL="rokid_scan_vision_state";
    private static final int REQ_AUTH=902;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CXRLink link;
    private volatile boolean connected=false,sessionReady=false;
    private TextView status;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        link=new CXRLink(this); configureLink(); setContentView(buildUi()); requestBtPermissions();
    }

    private LinearLayout buildUi(){
        int green=Color.rgb(79,255,159),soft=Color.rgb(174,244,202);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(36,60,36,36); root.setBackgroundColor(Color.BLACK);
        TextView title=txt("ROKID // SCAN VISION",26,green); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView version=txt("VISION HUD  v0.1",14,soft); version.setGravity(Gravity.CENTER); version.setPadding(0,8,0,36); root.addView(version);
        status=txt("PHONE READY // GLASSES DISCONNECTED",16,green); status.setPadding(0,0,0,24); root.addView(status);
        Button connect=new Button(this); connect.setText("CONNECT THROUGH HI ROKID"); connect.setOnClickListener(v->authorize()); root.addView(connect,new LinearLayout.LayoutParams(-1,-2));
        Button test=new Button(this); test.setText("SEND TEST TARGETS"); test.setOnClickListener(v->worker.execute(this::sendTestPacket)); root.addView(test,new LinearLayout.LayoutParams(-1,-2));
        TextView note=txt("v0.1 validates the Rokid link and green HUD first. SEND TEST TARGETS should place moving-style detection boxes on the glasses. Live camera vision comes next once this transport layer is proven on your hardware.",15,soft); note.setPadding(0,36,0,24); root.addView(note);
        Button hi=new Button(this); hi.setText("OPEN HI ROKID"); hi.setOnClickListener(v->{Intent i=getPackageManager().getLaunchIntentForPackage("com.rokid.sprite.global.aiapp");if(i!=null)startActivity(i);else setStatus("HI ROKID APP NOT FOUND");});root.addView(hi,new LinearLayout.LayoutParams(-1,-2));
        return root;
    }

    private TextView txt(String s,float sp,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);return t;}
    private void setStatus(String s){runOnUiThread(()->status.setText(s));}

    private void requestBtPermissions(){
        if(Build.VERSION.SDK_INT>=31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED))
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},901);
    }

    private void authorize(){
        try { AuthorizationHelper.getInstance().requestAuthorization(this,REQ_AUTH); setStatus("HI ROKID // AUTH REQUESTED"); }
        catch(Throwable t){ setStatus("HI ROKID AUTH // "+t.getClass().getSimpleName()); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_AUTH){ try { AuthResult result=AuthorizationHelper.getInstance().parseAuthorizationResult(data); if(result!=null){setStatus("HI ROKID // AUTHORIZED");connectLink();}else setStatus("HI ROKID // AUTH FAILED"); } catch(Throwable t){setStatus("AUTH RESULT // ERROR");} }
    }

    private void configureLink(){
        link.setCXRCustomCmdCbk(new ICustomCmdCbk(){@Override public void onCustomCmdResult(String c,byte[] d){}});
        link.setCXRSessionCbk(new ICXRSessionCbk(){
            @Override public void onSessionReady(){sessionReady=true;setStatus("SCAN VISION // LINK READY");}
            @Override public void onSessionDisconnected(){sessionReady=false;setStatus("SCAN VISION // SESSION LOST");}
        });
        link.setCXRGlassAppCbk(new IGlassAppCbk(){
            @Override public void onGlassAppStart(String p){setStatus("GLASSES HUD // STARTED");}
            @Override public void onGlassAppStop(String p){setStatus("GLASSES HUD // STOPPED");}
        });
        link.setCXRLinkCbk(new ICXRLinkCbk(){
            @Override public void onConnect(GlassInfo info){connected=true;setStatus("GLASSES // CONNECTED");}
            @Override public void onDisconnect(){connected=false;sessionReady=false;setStatus("GLASSES // DISCONNECTED");}
            @Override public void onError(int code,String msg){setStatus("CXR ERROR // "+code);}
        });
    }

    private void connectLink(){
        worker.execute(()->{try{link.connect();}catch(Throwable t){setStatus("CXR CONNECT // "+t.getClass().getSimpleName());}});
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
            link.sendMessage(CHANNEL,caps,root.toString().getBytes(StandardCharsets.UTF_8));
            setStatus("TEST TARGETS // SENT");
        }catch(Exception e){setStatus("SEND FAILED // "+e.getClass().getSimpleName());}
    }

    private JSONObject det(String label,double confidence,double l,double t,double r,double b)throws Exception{
        JSONObject o=new JSONObject();o.put("label",label);o.put("confidence",confidence);o.put("left",l);o.put("top",t);o.put("right",r);o.put("bottom",b);return o;
    }
}
