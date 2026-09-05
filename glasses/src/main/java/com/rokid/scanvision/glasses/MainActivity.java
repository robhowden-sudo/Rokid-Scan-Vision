package com.rokid.scanvision.glasses;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.ComponentActivity;

import com.rokid.cxr.CXRServiceBridge;
import com.rokid.cxr.Caps;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final String CHANNEL = "rokid_scan_vision_state";
    private ScanHudView hud;
    private CXRServiceBridge bridge;
    private OnDeviceVision vision;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        hud = new ScanHudView();
        setContentView(hud);
        startReceiver();
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startLocalVision();
        else requestPermissions(new String[]{Manifest.permission.CAMERA},700);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==700&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startLocalVision();
        else if(requestCode==700)hud.setStatus("CAMERA // PERMISSION REQUIRED");
    }

    private void startLocalVision(){vision=new OnDeviceVision(this,hud::setLocalDetections);vision.start();}
    void setVisionStatus(String status){hud.setStatus(status);}

    private void startReceiver() {
        try {
            bridge = new CXRServiceBridge();
            bridge.setStatusListener(new CXRServiceBridge.StatusListener() {
                @Override public void onConnecting(String d,String m,int t){ hud.setStatus("LINK // CONNECTING"); }
                @Override public void onConnected(String d,String m,int t){ hud.setStatus("LINK // ONLINE"); }
                @Override public void onDisconnected(){ hud.setStatus("LINK // WAITING"); }
                @Override public void onARTCStatus(float q,boolean h){}
                @Override public void onRokidAccountChanged(String a){}
                @Override public void onAudioNoise(float n){}
            });
            int result = bridge.subscribe(CHANNEL, new CXRServiceBridge.MsgCallback() {
                @Override public void onReceive(String channel, Caps args, byte[] data) {
                    try {
                        String json = null;
                        for(int i=0;i<args.size();i++) {
                            Caps.Value v=args.at(i);
                            if(v!=null && v.type()==Caps.Value.TYPE_STRING){ json=v.getString(); break; }
                        }
                        if(json!=null) hud.apply(new JSONObject(json));
                    } catch(Exception ignored) {}
                }
            });
            hud.setStatus(result==0 ? "LINK // WAITING" : "LINK // ERROR "+result);
        } catch(Throwable t) { hud.setStatus("CXR // UNAVAILABLE"); }
    }

    private class ScanHudView extends View {
        private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Detection> detections=new ArrayList<>();
        private String status="BOOT // INITIALISING";
        private float scanY=0f;
        private boolean scanDown=true;
        private long frames=0;

        ScanHudView(){
            super(MainActivity.this);
            setBackgroundColor(Color.BLACK);
            line.setColor(Color.rgb(78,255,145)); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(2f);
            text.setColor(Color.rgb(156,255,190)); text.setStyle(Paint.Style.FILL); text.setTypeface(android.graphics.Typeface.MONOSPACE);
            dim.setColor(Color.argb(120,56,220,120)); dim.setStyle(Paint.Style.STROKE); dim.setStrokeWidth(1f);
            post(animator);
        }

        private final Runnable animator=new Runnable(){ public void run(){
            float step=Math.max(2f,getHeight()/180f);
            scanY += scanDown?step:-step;
            if(scanY>=getHeight()){scanY=getHeight();scanDown=false;}
            if(scanY<=0){scanY=0;scanDown=true;}
            invalidate(); postDelayed(this,33);
        }};

        void setStatus(String s){ post(()->{status=s;invalidate();}); }

        void setLocalDetections(List<Detection> next){
            post(()->{synchronized(detections){detections.clear();detections.addAll(next);}frames++;status="VISION // ON-GLASSES";invalidate();});
        }

        void apply(JSONObject o){
            if(!"scan_state".equals(o.optString("type"))) return;
            List<Detection> next=new ArrayList<>();
            JSONArray arr=o.optJSONArray("detections");
            if(arr!=null) for(int i=0;i<arr.length();i++){
                JSONObject d=arr.optJSONObject(i); if(d==null) continue;
                Detection x=new Detection();
                x.label=d.optString("label","OBJECT").toUpperCase(Locale.ROOT);
                x.conf=(float)d.optDouble("confidence",0);
                x.l=(float)d.optDouble("left",0); x.t=(float)d.optDouble("top",0);
                x.r=(float)d.optDouble("right",1); x.b=(float)d.optDouble("bottom",1);
                next.add(x);
            }
            post(()->{ synchronized(detections){detections.clear();detections.addAll(next);} frames++; status="VISION // ACTIVE"; invalidate(); });
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float w=getWidth(),h=getHeight(),cx=w/2f,cy=h/2f;
            long tick=SystemClock.uptimeMillis()/90L;
            float glitch=(tick%41==0)?6f:((tick%23==0)?-3f:0f);
            text.setTextSize(Math.max(10f,w*.026f)); text.setTextAlign(Paint.Align.LEFT);
            c.drawText("CYBERDYNE SYSTEMS // LOCAL OPTICAL CORE",16+glitch,30,text);
            text.setTextAlign(Paint.Align.RIGHT); c.drawText(status,w-16,30,text);

            drawRails(c,w,h,tick);
            drawBearing(c,w*.14f,h*.48f,Math.min(w,h)*.085f,tick);
            drawCompass(c,w*.86f,h*.48f,Math.min(w,h)*.085f,tick);

            text.setTextSize(Math.max(9f,w*.022f)); text.setTextAlign(Paint.Align.LEFT);
            String[] left={"MODE  // AUTONOMOUS","CPU   // NEURAL NET","OPTIC // CXR-S CAM","RANGE // CALCULATING","TRACK // MULTI TARGET","MEM   // "+hex(tick*7919)};
            for(int i=0;i<left.length;i++)c.drawText(left[i],16+((tick+i)%47==0?4:0),62+i*18,text);
            text.setTextAlign(Paint.Align.RIGHT);
            String[] right={"SYS 120.7","KERNEL ONLINE","MOTION VECTOR","DEPTH ESTIMATE","ID "+hex(tick*3571),"T+"+String.format(Locale.US,"%06d",tick)};
            for(int i=0;i<right.length;i++)c.drawText(right[i],w-16,62+i*18,text);

            // central targeting reticle
            float r=Math.min(w,h)*.075f;
            c.drawCircle(cx,cy,r,line);
            c.drawLine(cx-r*1.6f,cy,cx-r*.45f,cy,line); c.drawLine(cx+r*.45f,cy,cx+r*1.6f,cy,line);
            c.drawLine(cx,cy-r*1.6f,cx,cy-r*.45f,line); c.drawLine(cx,cy+r*.45f,cx,cy+r*1.6f,line);
            c.drawCircle(cx,cy,2.5f,text);
            c.drawLine(cx-8,cy-8,cx+8,cy+8,dim); c.drawLine(cx+8,cy-8,cx-8,cy+8,dim);

            // scanning line
            c.drawLine(12,scanY,w-12,scanY,dim);
            for(int i=1;i<=3;i++) c.drawLine(24,scanY-i*5,w-24,scanY-i*5,dim);

            List<Detection> copy; synchronized(detections){copy=new ArrayList<>(detections);}
            drawAssessment(c,w,copy,tick);
            drawCodeBank(c,w,h,tick);
            text.setTextAlign(Paint.Align.LEFT);text.setTextSize(Math.max(10f,w*.024f));
            c.drawText("DETECTED TARGETS",16,190,text);
            c.drawLine(16,196,Math.min(w*.42f,250),196,dim);
            if(copy.isEmpty())c.drawText("-- ACQUIRING --",16,216,text);
            for(int i=0;i<Math.min(4,copy.size());i++){
                Detection d=copy.get(i);
                c.drawText(String.format(Locale.US,"%02d  %-14s %3d%%",i+1,d.label,Math.round(d.conf*100f)),16,216+i*19,text);
            }
            for(Detection d:copy){
                float l=d.l*w,t=d.t*h,rr=d.r*w,b=d.b*h;
                RectF box=new RectF(l,t,rr,b);
                c.drawRect(box,dim);
                float corner=Math.min(18f,(rr-l)*.18f);
                c.drawLine(l,t,l+corner,t,line); c.drawLine(l,t,l,t+corner,line);
                c.drawLine(rr,t,rr-corner,t,line); c.drawLine(rr,t,rr,t+corner,line);
                c.drawLine(l,b,l+corner,b,line); c.drawLine(l,b,l,b-corner,line);
                c.drawLine(rr,b,rr-corner,b,line); c.drawLine(rr,b,rr,b-corner,line);
                text.setTextAlign(Paint.Align.LEFT); text.setTextSize(Math.max(13f,w*.032f));
                String label=d.label+" // "+Math.round(d.conf*100f)+"%";
                c.drawText(label,l,Math.max(48,t-7),text);
                text.setTextSize(Math.max(9f,w*.022f));
                c.drawText("TGT-"+hex((long)(d.l*99991+d.t*7717)),l,Math.min(h-52,b+15),text);
                c.drawLine(l+(rr-l)*.25f,t,l+(rr-l)*.75f,b,dim);
            }

            text.setTextSize(Math.max(13f,w*.032f)); text.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.format(Locale.US,"OBJECTS // %02d",copy.size()),16,h-42,text);
            text.setTextAlign(Paint.Align.RIGHT); c.drawText(String.format(Locale.US,"FRAME // %06d",frames),w-16,h-42,text);
            text.setTextAlign(Paint.Align.CENTER); c.drawText(copy.isEmpty()?"SCANNING // NO TARGET":"TRACKING // TARGETS ACQUIRED",cx,h-16,text);
            if(tick%67<5){
                text.setTextSize(Math.max(12f,w*.03f));
                c.drawText("// VISUAL BUFFER RESYNC //",cx+glitch,cy-r*2.1f,text);
            }
        }

        private void drawRails(Canvas c,float w,float h,long tick){
            float top=46,bottom=h-64;
            c.drawLine(12,top,12,bottom,line);c.drawLine(w-12,top,w-12,bottom,line);
            for(int i=0;i<=16;i++){
                float y=top+(bottom-top)*i/16f;float len=i%4==0?13:7;
                c.drawLine(12,y,12+len,y,dim);c.drawLine(w-12-len,y,w-12,y,dim);
            }
            float cursor=top+(bottom-top)*(tick%160)/160f;
            c.drawLine(8,cursor,28,cursor,line);c.drawLine(w-28,cursor,w-8,cursor,line);
        }

        private void drawBearing(Canvas c,float x,float y,float r,long tick){
            text.setTextAlign(Paint.Align.CENTER);text.setTextSize(Math.max(9f,getWidth()*.02f));
            String[] p={"N","NE","E","SE","S","SW","W","NW"};
            for(int i=0;i<8;i++){double a=Math.PI*2*i/8-Math.PI/2;float ex=x+(float)Math.cos(a)*r,ey=y+(float)Math.sin(a)*r;c.drawLine(x,y,ex,ey,dim);c.drawText(p[i],x+(float)Math.cos(a)*(r+12),y+4+(float)Math.sin(a)*(r+12),text);}
            double a=Math.PI*2*(tick%80)/80-Math.PI/2;c.drawLine(x,y,x+(float)Math.cos(a)*r,y+(float)Math.sin(a)*r,line);c.drawCircle(x,y,3,text);
        }

        private void drawCompass(Canvas c,float x,float y,float r,long tick){
            c.drawCircle(x,y,r,line);c.drawLine(x-r-12,y,x+r+12,y,dim);c.drawLine(x,y-r-12,x,y+r+12,dim);
            text.setTextSize(Math.max(9f,getWidth()*.02f));text.setTextAlign(Paint.Align.CENTER);
            c.drawText("000",x,y-r-7,text);c.drawText("090",x+r+18,y+4,text);c.drawText("180",x,y+r+15,text);c.drawText("270",x-r-18,y+4,text);
            float a=(tick%360)*(float)Math.PI/180f;c.drawLine(x,y,x+(float)Math.sin(a)*r,y-(float)Math.cos(a)*r,line);
        }

        private void drawAssessment(Canvas c,float w,List<Detection> copy,long tick){
            text.setTextAlign(Paint.Align.LEFT);text.setTextSize(Math.max(8f,w*.018f));
            c.drawText("// VISUAL ASSESSMENT",34,54,text);
            String[] keys={"SCAN","LEVEL","IMAGE","TRACK","MASK"};
            for(int i=0;i<keys.length;i++)c.drawText(String.format(Locale.US,"%-6s %04X %04X",keys[i],(tick*31+i*977)&0xFFFF,(tick*17+i*313)&0xFFFF),34,68+i*12,text);
            text.setTextAlign(Paint.Align.RIGHT);c.drawText("OBJECT CLASS BANK",w-34,54,text);
            for(int i=0;i<Math.min(6,copy.size());i++)c.drawText(String.format(Locale.US,"%02d %-10s %03d",i+1,copy.get(i).label,Math.round(copy.get(i).conf*100)),w-34,68+i*12,text);
        }

        private void drawCodeBank(Canvas c,float w,float h,long tick){
            text.setTextAlign(Paint.Align.LEFT);text.setTextSize(Math.max(7f,w*.016f));float x=w*.31f,y=h-102;
            for(int i=0;i<5;i++)c.drawText(String.format(Locale.US,"%02d  %-5s EQU %03X    ADDR %06X",29+i,"VIEW",(tick+i*19)&0xFFF,(tick*7919+i*4093)&0xFFFFFF),x,y+i*11,text);
        }

        private String hex(long value){return String.format(Locale.US,"%06X",value&0xFFFFFFL);}
    }

    @Override protected void onDestroy(){if(vision!=null)vision.close();super.onDestroy();}

    static class Detection { String label; float conf,l,t,r,b; }
}
