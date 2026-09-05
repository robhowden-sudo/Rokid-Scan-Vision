package com.rokid.scanvision.glasses;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    private static final float TARGET_VERTICAL_OFFSET=-.20f;
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
            float headerBottom=40f,telemetryBottom=124f,targetListTop=142f;
            long tick=SystemClock.uptimeMillis()/90L;
            float glitch=(tick%41==0)?6f:((tick%23==0)?-3f:0f);
            drawHeader(c,w,glitch);

            drawRails(c,w,h,tick);
            drawBearing(c,w*.14f,h*.48f,Math.min(w,h)*.085f,tick);
            drawCompass(c,w*.86f,h*.48f,Math.min(w,h)*.085f,tick);

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
            drawAssessment(c,w,copy,tick,headerBottom,telemetryBottom);
            drawCodeBank(c,w,h,tick);
            text.setTextAlign(Paint.Align.LEFT);text.setTextSize(Math.max(10f,w*.024f));
            c.drawText("DETECTED TARGETS",16,targetListTop,text);
            c.drawLine(16,targetListTop+6,Math.min(w*.56f,360),targetListTop+6,dim);
            if(copy.isEmpty())c.drawText("-- ACQUIRING --",16,targetListTop+26,text);
            for(int i=0;i<Math.min(6,copy.size());i++){
                Detection d=copy.get(i);
                float listX=i<3?16:w*.29f;
                float listY=targetListTop+26+(i%3)*19;
                c.drawText(String.format(Locale.US,"%02d  %-11s %3d%%",i+1,d.label,Math.round(d.conf*100f)),listX,listY,text);
            }
            for(int i=0;i<copy.size();i++){
                Detection d=copy.get(i);
                float targetX=((d.l+d.r)*.5f)*w;
                float targetY=Math.max(0f,Math.min(1f,(d.t+d.b)*.5f+TARGET_VERTICAL_OFFSET))*h;
                float objectWidth=Math.max(0f,(d.r-d.l)*w);
                float objectHeight=Math.max(0f,(d.b-d.t)*h);
                float targetRadius=Math.max(12f,Math.min(25f,Math.min(objectWidth,objectHeight)*.22f));

                // The detector's bounding box is used only to locate the object. The HUD
                // deliberately exposes no box: each object gets a numbered circular marker
                // matching its entry in DETECTED TARGETS.
                c.drawCircle(targetX,targetY,targetRadius,line);
                c.drawCircle(targetX,targetY,targetRadius+4f,dim);
                c.drawLine(targetX-targetRadius-7f,targetY,targetX-targetRadius+3f,targetY,line);
                c.drawLine(targetX+targetRadius-3f,targetY,targetX+targetRadius+7f,targetY,line);
                c.drawLine(targetX,targetY-targetRadius-7f,targetX,targetY-targetRadius+3f,line);
                c.drawLine(targetX,targetY+targetRadius-3f,targetX,targetY+targetRadius+7f,line);

                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(Math.max(12f,w*.029f));
                Paint.FontMetrics fm=text.getFontMetrics();
                float numberBaseline=targetY-(fm.ascent+fm.descent)*.5f;
                c.drawText(String.format(Locale.US,"%02d",i+1),targetX,numberBaseline,text);
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

        private void drawHeader(Canvas c,float w,float glitch){
            text.setTextSize(Math.max(9f,w*.016f));
            int save=c.save();
            c.clipRect(16,0,w*.64f,40);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("CYBERDYNE SYSTEMS // LOCAL OPTICAL CORE",16+glitch,27,text);
            c.restoreToCount(save);
            save=c.save();
            c.clipRect(w*.65f,0,w-16,40);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(status,w-16,27,text);
            c.restoreToCount(save);
            c.drawLine(16,36,w-16,36,dim);
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

        private void drawAssessment(Canvas c,float w,List<Detection> copy,long tick,float top,float bottom){
            text.setTextSize(Math.max(7f,w*.014f));
            int save=c.save();
            c.clipRect(28,top,w*.48f,bottom);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("// VISUAL ASSESSMENT",34,top+14,text);
            String[] keys={"SCAN","LEVEL","IMAGE","TRACK","MASK"};
            for(int i=0;i<keys.length;i++)c.drawText(String.format(Locale.US,"%-6s %04X %04X",keys[i],(tick*31+i*977)&0xFFFF,(tick*17+i*313)&0xFFFF),34,top+28+i*12,text);
            c.restoreToCount(save);
            save=c.save();
            c.clipRect(w*.52f,top,w-28,bottom);
            text.setTextAlign(Paint.Align.RIGHT);c.drawText("OBJECT CLASS BANK",w-34,top+14,text);
            if(copy.isEmpty())c.drawText("00 NO CLASSIFICATION",w-34,top+28,text);
            for(int i=0;i<Math.min(5,copy.size());i++)c.drawText(String.format(Locale.US,"%02d %-10s %03d",i+1,copy.get(i).label,Math.round(copy.get(i).conf*100)),w-34,top+28+i*12,text);
            c.restoreToCount(save);
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
