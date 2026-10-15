import processing.core.*;
import processing.data.*;
import java.util.*;
import java.nio.file.*;
import java.awt.GraphicsEnvironment;
import javax.swing.*;
import org.opencv.videoio.Videoio;

/** Small Processing UI: manual grid homography and independent depth point measurements. */
public final class Calibrator {
  final PApplet p;
  final AstraDepth depth=new AstraDepth();final ColorCamera color=new ColorCamera();
  final List<double[]> clicks=new ArrayList<>();
  volatile boolean ready=false,connecting=false,pendingLoad=false;
  volatile String message="Loading native image library...";
  volatile int grid=3;volatile int selected=0;volatile double[] probe;
  volatile ProjectorView projector;
  int display=GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length>1?2:1;
  boolean colorView=true,mirrorColor=false,mirrorDepth=false,frozen=false,demo=false,testing=false;volatile boolean calibrating=false;
  Frames frozenFrame,displayFrame,demoCache;PImage image;
  int lastSequence=-1;boolean lastMirror=false;String imageKind="";
  volatile Mapping mapping;String mapSpace="COLOR";int mapWidth=0,mapHeight=0,colorIndex=0,backend=0;
  float measurement=Float.NaN;int measureX=-1,measureY=-1;
  float imageX=20,imageY=165,imageW=900,imageH=675;
  String runtime="";final Path data;
  static final int BG=0xff111923,PANEL=0xff1c2938,INK=0xffeaf2fb,ACCENT=0xff62ffe0;

  public Calibrator(PApplet parent) {
    p=parent;p.frameRate(30);p.getSurface().setResizable(true);java.awt.Rectangle bounds=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();p.getSurface().setSize(Math.min(1240,bounds.width-50),Math.min(850,bounds.height-90));data=Paths.get(p.sketchPath("data"));loadSettings();
    p.registerMethod("dispose",this);
    new Thread(()->{try{nu.pattern.OpenCV.loadLocally();ready=true;message="Choose runtime, connect, then calibrate or measure. No web server needed.";pendingLoad=true;}catch(Throwable e){message="Library load failed: "+e.getMessage();}},"Load OpenCV").start();
    if(Boolean.getBoolean("projectorgames.demo"))demo=true;
  }
  public void dispose(){close();}
  void button(String text,float x,float y,float w,boolean active) {
    p.stroke(active?ACCENT:0xff3d5065);p.fill(active?0xff22463f:PANEL);p.rect(x,y,w,34,6);p.noStroke();p.fill(INK);p.textAlign(PApplet.CENTER,PApplet.CENTER);p.textSize(12);p.text(text,x+w/2,y+17);
  }
  boolean inside(int x,int y,float bx,float by,float bw,float bh){return x>=bx&&x<bx+bw&&y>=by&&y<by+bh;}
  float uiScale(){return Math.min(p.width/1240f,p.height/850f);}
  public void draw(){
    if(pendingLoad){pendingLoad=false;loadCalibration();}
    p.background(BG);p.pushMatrix();p.scale(uiScale());p.textAlign(PApplet.LEFT,PApplet.TOP);p.fill(INK);p.textSize(22);p.text("ProjectorGames | manual calibration",20,13);
    button("Runtime folder",20,50,120,false);button(connecting?"Connecting...":"Connect",150,50,90,false);
    button("Color #"+colorIndex,250,50,95,false);button(backend==0?"Backend: AUTO":backend==Videoio.CAP_DSHOW?"DirectShow":"Media Foundation",355,50,140,false);
    button("Color",505,50,70,colorView);button("Depth",585,50,70,!colorView);button("Mirror",665,50,75,colorView?mirrorColor:mirrorDepth);button(frozen?"Resume":"Freeze",750,50,80,frozen);
    button("Projector",840,50,95,false);button("Display "+display,945,50,95,false);button(demo?"Demo ON":"Demo",1050,50,80,demo);
    button(grid+" x "+grid+" grid",20,96,100,false);button("Calibrate",130,96,100,calibrating);button("Undo",240,96,65,false);button("Fit",315,96,60,false);button("Save",385,96,65,false);button("Load",460,96,65,false);
    button("Measure depth",535,96,135,!calibrating&&!colorView);button("Test mapping",680,96,120,!calibrating&&colorView);button("Reset points",810,96,115,false);
    p.fill(0xff9cb1c8);p.textAlign(PApplet.LEFT,PApplet.TOP);p.textSize(11);p.text("Runtime: "+(runtime.isEmpty()?"not selected":runtime),20,137);
    Frames raw=demo?demoFrame():colorView?color.latest:depth.latest;
    if(frozen)raw=frozenFrame;
    boolean valid=raw!=null&&(demo||frozen||raw.fresh());
    p.fill(0xff050a10);p.rect(20,165,900,675);
    if(valid){
      boolean mirror=colorView?mirrorColor:mirrorDepth;String kind=colorView?"COLOR":"DEPTH";
      if(image==null||raw.sequence!=lastSequence||mirror!=lastMirror||!kind.equals(imageKind)||raw.width!=image.width||raw.height!=image.height){
        displayFrame=mirror?raw.mirrored():raw;
        if(image==null||image.width!=raw.width||image.height!=raw.height)image=p.createImage(raw.width,raw.height,PApplet.ARGB);
        image.loadPixels();System.arraycopy(displayFrame.argb,0,image.pixels,0,image.pixels.length);image.updatePixels();
        lastSequence=raw.sequence;lastMirror=mirror;imageKind=kind;
      }
      float scale=Math.min(900f/image.width,675f/image.height);imageW=image.width*scale;imageH=image.height*scale;imageX=20+(900-imageW)/2;imageY=165+(675-imageH)/2;
      p.image(image,imageX,imageY,imageW,imageH);
      if(demo)drawDemoTargets();
      if(kind.equals(mapSpace)||calibrating){
        for(int i=0;i<clicks.size();i++){double[] pt=clicks.get(i);p.stroke(ACCENT);p.fill(0xff10211b);p.ellipse(imageX+(float)pt[0]*imageW,imageY+(float)pt[1]*imageH,22,22);p.noStroke();p.fill(INK);p.textAlign(PApplet.CENTER,PApplet.CENTER);p.text(i+1,imageX+(float)pt[0]*imageW,imageY+(float)pt[1]*imageH);}
      }
      if(!colorView&&measureX>=0&&displayFrame!=null){float x=imageX+(measureX+.5f)/displayFrame.width*imageW,y=imageY+(measureY+.5f)/displayFrame.height*imageH;p.stroke(ACCENT);p.line(x-10,y,x+10,y);p.line(x,y-10,x,y+10);p.noStroke();}
    }else{displayFrame=null;p.fill(0xff90a5bf);p.textAlign(PApplet.CENTER,PApplet.CENTER);p.textSize(16);p.text("No fresh "+(colorView?"color":"depth")+" frame",470,490);}
    p.fill(PANEL);p.rect(940,165,280,675,8);p.fill(ACCENT);p.textAlign(PApplet.LEFT,PApplet.TOP);p.textSize(16);p.text(calibrating?"CALIBRATION":"POINT MEASUREMENT",958,185);
    p.fill(INK);p.textSize(13);p.text("Points: "+clicks.size()+" / "+(grid*grid),958,220);
    String instruction=calibrating?(clicks.size()<grid*grid?"Click target "+(clicks.size()+1)+" in the camera image.\nFollow the numbered projector grid, row by row.":"All points selected. Click Fit, check the error, then Save."):"Color: click to test the fitted mapping.\n\nDepth: click a wall/floor point to measure camera depth.";
    p.text(instruction,958,252,242,155);
    p.fill(ACCENT);p.textSize(23);p.text(Float.isNaN(measurement)?"-- mm":String.format(java.util.Locale.ROOT,"%.0f mm",measurement),958,410);p.textSize(14);p.text(Float.isNaN(measurement)?"":String.format(java.util.Locale.ROOT,"%.3f m from camera",measurement/1000),958,445);
    p.fill(0xffa7bad1);p.textSize(12);p.text("Depth uses a 5 x 5 median of valid pixels. It is camera-axis depth, not perpendicular wall clearance.\n\nColor and depth are separate image spaces. A color click is never silently used as a depth pixel.\n\nNo closest-distance feature. No automatic body calibration.",958,485,242,175);
    p.fill(INK);p.text(message,958,665,242,95);p.fill(0xffa7bad1);p.text((demo?"SYNTHETIC DEMO\n":depth.status+"\n"+color.status)+"\nDraw FPS: "+Math.round(p.frameRate),958,765,245,70);p.popMatrix();
  }
  void drawDemoTargets(){
    List<double[]> targets=Mapping.grid(grid);p.textAlign(PApplet.CENTER,PApplet.CENTER);p.textSize(15);
    for(int i=0;i<targets.size();i++){double[] xy=targets.get(i);float x=imageX+(float)xy[0]*imageW,y=imageY+(float)xy[1]*imageH;p.stroke(0xffffffff);p.fill(0xff111923);p.ellipse(x,y,28,28);p.noStroke();p.fill(0xffffffff);p.text(i+1,x,y);}
  }
  Frames demoFrame(){int key=grid*10+(colorView?1:2);if(demoCache!=null&&demoCache.sequence==key)return demoCache;int w=640,h=480;int[] pixels=new int[w*h];float[] values=new float[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x;values[i]=1800+x*.5f+y*.25f;pixels[i]=colorView?0xff576475:Frames.heat(values[i]);}demoCache=new Frames(w,h,pixels,values,key);return demoCache;}
  public void mousePressed(int x,int y){
    x=Math.round(x/uiScale());y=Math.round(y/uiScale());
    if(y>=50&&y<84){
      if(x>=20&&x<140){chooseRuntime();return;}if(x>=150&&x<240){connect();return;}
      if(x>=250&&x<345){String value=JOptionPane.showInputDialog("Color camera number (usually 0 or 1):",colorIndex);if(value!=null)try{int n=Integer.parseInt(value);if(n<0||n>20)throw new NumberFormatException();colorIndex=n;reset();saveSettings();}catch(NumberFormatException e){message="Choose a number from 0 to 20.";}return;}
      if(x>=355&&x<495){backend=backend==0?Videoio.CAP_DSHOW:backend==Videoio.CAP_DSHOW?Videoio.CAP_MSMF:0;reset();saveSettings();message="Backend changed. Click Connect to apply.";return;}
      if(x>=505&&x<575){setView(true);return;}if(x>=585&&x<655){setView(false);return;}
      if(x>=665&&x<740){if(colorView)mirrorColor=!mirrorColor;else mirrorDepth=!mirrorDepth;reset();invalidateImage();saveSettings();return;}
      if(x>=750&&x<830){if(!frozen){Frames frame=demo?demoFrame():colorView?color.latest:depth.latest;if(frame==null||(!demo&&!frame.fresh())){message="No fresh frame to freeze.";return;}frozenFrame=frame;}frozen=!frozen;invalidateImage();return;}
      if(x>=840&&x<935){openProjector();return;}if(x>=945&&x<1040){display=display%GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length+1;message="Next projector window opens on display "+display;return;}
      if(x>=1050&&x<1130){demo=!demo;reset();frozen=false;invalidateImage();return;}
    }
    if(y>=96&&y<130){
      if(x>=20&&x<120){grid=grid==5?3:grid+1;reset();saveSettings();return;}
      if(x>=130&&x<230){if(displayFrame==null){message="Connect a camera or use Demo first.";return;}reset();calibrating=true;mapSpace=colorView?"COLOR":"DEPTH";mapWidth=displayFrame.width;mapHeight=displayFrame.height;openProjector();message="Click all "+grid*grid+" numbered target centers in order.";return;}
      if(x>=240&&x<305){if(!clicks.isEmpty())clicks.remove(clicks.size()-1);selected=clicks.size();mapping=null;return;}
      if(x>=315&&x<375){fit();return;}if(x>=385&&x<450){saveCalibration();return;}if(x>=460&&x<525){loadCalibration();return;}
      if(x>=535&&x<670){setView(false);calibrating=false;testing=false;message="Click a wall/floor point in Depth.";return;}
      if(x>=680&&x<800){setView(mapSpace.equals("COLOR"));calibrating=false;testing=true;message="Click the calibrated image to place a projector crosshair.";return;}
      if(x>=810&&x<925){reset();return;}
    }
    if(displayFrame==null||!inside(x,y,imageX,imageY,imageW,imageH))return;
    double u=(x-imageX)/imageW,v=(y-imageY)/imageH;
    if(calibrating){
      if(displayFrame.width!=mapWidth||displayFrame.height!=mapHeight){message="Frame dimensions changed. Restart calibration.";return;}
      if(clicks.size()<grid*grid){clicks.add(new double[]{u,v});selected=clicks.size();mapping=null;message=clicks.size()==grid*grid?"All points selected. Click Fit.":"Next target: "+(clicks.size()+1);}
    }else if(testing&&mapping!=null&&mapSpace.equals(colorView?"COLOR":"DEPTH")){
      if(displayFrame.width!=mapWidth||displayFrame.height!=mapHeight){message="Frame resolution changed. Recalibrate before testing.";return;}
      probe=mapping.apply(u,v);openProjector();message="Mapped crosshair sent to projector.";
    }else if(!colorView){measureX=Math.min(displayFrame.width-1,(int)(u*displayFrame.width));measureY=Math.min(displayFrame.height-1,(int)(v*displayFrame.height));measurement=displayFrame.sample(measureX,measureY);message=Float.isNaN(measurement)?"No reliable depth here. Click a nearby solid point.":"Sample at pixel ("+measureX+", "+measureY+").";}
    else if(mapping!=null&&mapSpace.equals("COLOR")&&displayFrame.width==mapWidth&&displayFrame.height==mapHeight){probe=mapping.apply(u,v);openProjector();message="Mapped crosshair sent to projector.";}
  }
  void setView(boolean value){if(colorView!=value){if(calibrating)calibrating=false;colorView=value;frozen=false;testing=false;invalidateImage();}}
  void invalidateImage(){image=null;displayFrame=null;lastSequence=-1;}
  void reset(){clicks.clear();selected=0;mapping=null;probe=null;measurement=Float.NaN;measureX=measureY=-1;calibrating=false;testing=false;message="Points reset. Start Calibrate to select a new grid.";}
  void fit(){if(!ready){message="Image library not ready.";return;}try{if(clicks.size()!=grid*grid)throw new IllegalArgumentException("Select all "+grid*grid+" points.");mapping=Mapping.fit(clicks,Mapping.grid(grid));int w=projector==null?1024:projector.width,h=projector==null?768:projector.height;message=String.format(java.util.Locale.ROOT,"Fit RMS: %.2f projector pixels. Click Save to keep it.",mapping.rms(clicks,Mapping.grid(grid),w,h));}catch(Exception e){message=e.getMessage();}}
  void chooseRuntime(){JFileChooser chooser=new JFileChooser(runtime.isEmpty()?System.getProperty("user.home"):runtime);chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);chooser.setDialogTitle("Choose folder containing OpenNI2.dll");if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION){runtime=chooser.getSelectedFile().getAbsolutePath();reset();saveSettings();}}
  void connect(){if(!ready||connecting)return;connecting=true;frozen=false;invalidateImage();new Thread(()->{try{depth.stop();color.stop();depth.start(runtime);color.start(colorIndex,backend);demo=false;saveSettings();message="Opening independent depth and color streams.";}catch(Throwable e){message=e.getMessage();}finally{connecting=false;}},"Connect cameras").start();}
  void openProjector(){if(projector!=null&&projector.screen==display){projector.getSurface().setVisible(true);projector.loop();return;}if(projector!=null){projector.noLoop();projector.getSurface().setVisible(false);}projector=new ProjectorView(this,display);PApplet.runSketch(new String[]{"Projector grid"},projector);}
  JSONObject settings(){JSONObject j=new JSONObject();j.setString("runtime",runtime);j.setInt("color_index",colorIndex);j.setInt("backend",backend);j.setBoolean("mirror_color",mirrorColor);j.setBoolean("mirror_depth",mirrorDepth);j.setInt("grid",grid);return j;}
  void saveSettings(){try{Files.createDirectories(data);p.saveJSONObject(settings(),data.resolve("settings.json").toString());}catch(Exception e){message="Cannot save settings: "+e.getMessage();}}
  void loadSettings(){try{Path file=data.resolve("settings.json");if(!Files.exists(file)){Path old=Paths.get(p.sketchPath()).resolve("../../.local/camera.json").normalize();if(Files.exists(old)){JSONObject j=p.loadJSONObject(old.toString());runtime=j.getString("runtime","");colorIndex=j.getInt("color_index",0);String b=j.getString("color_backend","auto");backend=b.equals("dshow")?Videoio.CAP_DSHOW:b.equals("msmf")?Videoio.CAP_MSMF:0;mirrorColor=j.getBoolean("mirror_color",false);mirrorDepth=j.getBoolean("mirror_depth",false);}return;}JSONObject j=p.loadJSONObject(file.toString());runtime=j.getString("runtime","");colorIndex=j.getInt("color_index",0);backend=j.getInt("backend",0);mirrorColor=j.getBoolean("mirror_color",false);mirrorDepth=j.getBoolean("mirror_depth",false);grid=j.getInt("grid",3);if(grid<3||grid>5)grid=3;}catch(Exception e){message="Settings could not be read. Choose runtime and camera again.";}}
  void saveCalibration(){if(mapping==null){message="Fit a valid mapping before saving.";return;}try{JSONObject j=settings();j.setString("space",mapSpace);j.setInt("width",mapWidth);j.setInt("height",mapHeight);JSONArray values=new JSONArray();for(double v:mapping.h)values.append(v);j.setJSONArray("homography",values);JSONArray points=new JSONArray();for(double[] pt:clicks){JSONArray a=new JSONArray();a.append(pt[0]);a.append(pt[1]);points.append(a);}j.setJSONArray("points",points);Files.createDirectories(data);p.saveJSONObject(j,data.resolve("calibration.json").toString());message="Saved to data/calibration.json.";}catch(Exception e){message="Save failed: "+e.getMessage();}}
  void loadCalibration(){try{Path file=data.resolve("calibration.json");if(!Files.exists(file))return;JSONObject j=p.loadJSONObject(file.toString());if(j.getInt("color_index",colorIndex)!=colorIndex||j.getInt("backend",backend)!=backend||!j.getString("runtime",runtime).equals(runtime))throw new IllegalArgumentException("Saved camera settings differ. Select the matching camera/runtime or recalibrate.");JSONArray a=j.getJSONArray("homography");double[] values=new double[9];for(int i=0;i<9;i++)values[i]=a.getDouble(i);Mapping restored=new Mapping(values);JSONArray pts=j.getJSONArray("points");int g=j.getInt("grid");if(g<3||g>5||pts.size()!=g*g)throw new IllegalArgumentException("Invalid point count.");clicks.clear();for(int i=0;i<pts.size();i++){JSONArray pt=pts.getJSONArray(i);clicks.add(new double[]{pt.getDouble(0),pt.getDouble(1)});}grid=g;mapSpace=j.getString("space");mapWidth=j.getInt("width");mapHeight=j.getInt("height");mirrorColor=j.getBoolean("mirror_color",false);mirrorDepth=j.getBoolean("mirror_depth",false);mapping=restored;selected=clicks.size();calibrating=false;invalidateImage();message="Saved "+clicks.size()+"-point "+mapSpace.toLowerCase()+" mapping loaded.";}catch(Exception e){message="Cannot load calibration: "+e.getMessage();}}
  public void keyPressed(char key,int code){if(key=='z'||key=='Z'){if(!clicks.isEmpty())clicks.remove(clicks.size()-1);selected=clicks.size();mapping=null;}if(key=='s'||key=='S')saveCalibration();}
  public void close(){try{depth.stop();}catch(Exception ignored){}try{color.stop();}catch(Exception ignored){}}
}
