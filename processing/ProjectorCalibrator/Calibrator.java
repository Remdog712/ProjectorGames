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
  volatile int step=0;
  final BubbleGame game=new BubbleGame();
  Frames surfaceFrame,colorReference;
  Mapping depthMapping;
  final List<double[]> depthClicks=new ArrayList<>();
  float lowMm=Float.NaN,highMm=Float.NaN,noiseMm=20;
  int minSamples=12;
  long captureAt=0;
  boolean referenceView=false;
  volatile boolean gameRunning=false;
  volatile int testColor=0;
  void captureHigh(){captureAt=System.currentTimeMillis()+5000;frozen=false;invalidateImage();message="Move in front of the surface. Capturing in 5 seconds.";}
  void updateInteraction(){
    if(captureAt>0&&System.currentTimeMillis()>=captureAt){Frames f=demo?demoFrame():depth.latest;captureAt=0;if(f!=null&&(demo||f.fresh())){frozenFrame=f;frozen=true;invalidateImage();message="Captured. Return to the PC and click your chest, hand or raised foot.";}else message="No fresh depth frame. Retry capture.";}
    if(step==9&&gameRunning){Frames f=demo?demoFrame():depth.latest;if(f==null||(!demo&&!f.fresh()))game.clearContacts();else game.update(mirrorDepth?f.mirrored():f,surfaceFrame,depthMapping,lowMm-highMm,noiseMm,minSamples);}
  }
  void saveRange(){try{JSONObject j=new JSONObject();j.setFloat("surface_mm",lowMm);j.setFloat("high_point_mm",highMm);j.setFloat("noise_mm",noiseMm);j.setInt("min_samples",minSamples);if(depthMapping!=null){JSONArray h=new JSONArray();for(double v:depthMapping.h)h.append(v);j.setJSONArray("depth_homography",h);j.setInt("depth_width",surfaceFrame.width);j.setInt("depth_height",surfaceFrame.height);j.setBoolean("mirror_depth",mirrorDepth);}p.saveJSONObject(j,data.resolve("interaction.json").toString());}catch(Exception e){message="Could not save range: "+e.getMessage();}}

  final Object cameraSwitchLock=new Object();
  volatile int cameraRequest=0;
  boolean cameraEnabled=false;
  final String[] steps={"Connect camera","Check preview","Place projector","Click targets","Verify mapping","Projection color test","Measure surface / low","Choose high point","Align depth interaction","Pop bubbles"};
  boolean probeChecked=false;
  String guidance(){switch(step){
    case 0:return "Choose the folder containing OpenNI2.dll, then Connect. Wait for a live color frame. Demo lets you try these steps without hardware.";
    case 1:return "Check that the wall or floor is visible in Color. Switch to Depth to check that stream too. Adjust Mirror for each view if needed. Next uses Color for calibration.";
    case 2:return "Choose the projector display, then Show grid. Place the numbered grid on your wall or floor. Choose 9, 16 or 25 points. Keep the camera and projector still.";
    case 3:return clicks.size()<grid*grid?"Click target "+(clicks.size()+1)+" of "+grid*grid+" in this COLOR preview. Match the highlighted number on the projector. Undo corrects your last click.":"All targets selected. Next fits the mapping. If the fit fails, undo and correct the points.";
    case 4:return "Click several places in the COLOR preview. Check that the projector crosshair lands at the same places on the wall/floor. If it is off, go Back to correct points. Next saves the mapping.";
    case 5:return "Check the projected color bars. Use Next color for red, green, blue and white. Check the full projected area is visible, then Next.";
    case 6:return "Click a solid spot on the wall or floor in this DEPTH preview. The distance appears below. If no depth is available, check the camera connection. Keep the surface empty: this frame becomes the background and the clicked distance is the low bound.";
    case 7:return "Click Capture in 5s, stand closer to the camera, then return and click ONE point on your body in the frozen Depth image. This is the high bound. It must be closer than the surface. Next resumes live depth.";
    case 8:return "Match the numbered projector targets to their locations in DEPTH. Color reference shows the saved color view for guidance. Only Depth accepts clicks. Use surface edges/landmarks or temporary depth-visible markers if projected dots are invisible in Depth. Select all points, then Next.";
    default:return "Move your body into the selected depth layer to pop projected bubbles. The mouse cannot pop them. Pause / Play controls the game. Noise removes small depth changes; Min area removes small blobs. Back returns to depth alignment.";
  }}
  boolean freshColor(){Frames f=demo?demoFrame():color.latest;return f!=null&&(demo||f.fresh());}
  void nextStep(){
    if(step==0){if(!ready||connecting||!freshColor()){message="Wait for a live color image, or select Demo. Check Color camera number/backend if it is missing.";return;}setView(true);step=1;}
    else if(step==1){setView(true);step=2;}
    else if(step==2){if(!freshColor()){message="A live color frame is required.";return;}setView(true);reset();Frames f=demo?demoFrame():color.latest;mapSpace="COLOR";mapWidth=f.width;mapHeight=f.height;calibrating=true;openProjector();step=3;}
    else if(step==3){fit();if(mapping==null)return;calibrating=false;testing=true;probeChecked=false;step=4;message="Click the preview to check the projected crosshair.";}
    else if(step==4){if(!probeChecked){message="Click the preview and check the projected crosshair before continuing.";return;}if(displayFrame==null){message="Wait for the color preview before continuing.";return;}if(!saveCalibration())return;colorReference=displayFrame;step=5;openProjector();}
    else if(step==5){setView(false);lowMm=Float.NaN;highMm=Float.NaN;surfaceFrame=null;depthMapping=null;measurement=Float.NaN;measureX=measureY=-1;step=6;}
    else if(step==6){setView(false);if(!Float.isFinite(lowMm)||surfaceFrame==null){message="Click a valid point on the empty surface first.";return;}step=7;highMm=Float.NaN;captureAt=0;}
    else if(step==7){if(!Float.isFinite(highMm)||lowMm-highMm<=noiseMm){message="Capture and choose a point closer than the surface by more than the noise margin.";return;}frozen=false;invalidateImage();depthClicks.clear();probe=null;selected=0;step=8;referenceView=false;openProjector();}
    else if(step==8){if(depthClicks.size()!=grid*grid){message="Select all depth alignment points.";return;}try{depthMapping=Mapping.fit(depthClicks,Mapping.grid(grid));}catch(Exception e){message=e.getMessage();return;}saveRange();if(projector!=null&&projector.height>0)game.setAspect(projector.width/(double)projector.height);game.reset();gameRunning=true;step=9;message="Move into the layer to pop bubbles. Only live depth controls the game.";openProjector();}
  }
  void backStep(){
    if(step==0)return;
    if(step>=6){gameRunning=false;captureAt=0;frozen=false;referenceView=false;step--;setView(step==5);invalidateImage();return;}
    if(step==4){step=3;setView(true);calibrating=true;testing=false;probeChecked=false;return;}
    step--;calibrating=false;testing=false;
    if(step<=2)setView(true);
    if(step==4){setView(true);testing=true;}
    if(step>=6)setView(false);
  }
  void undoPoint(){if(!clicks.isEmpty())clicks.remove(clicks.size()-1);selected=clicks.size();mapping=null;probeChecked=false;}
  void drawStepControls(){
    if(step==0){button("Runtime folder",20,50,120,false);button(connecting?"Connecting...":"Connect",150,50,90,false);button("Color #"+colorIndex,250,50,95,false);button(backend==0?"Backend: AUTO":backend==Videoio.CAP_DSHOW?"DirectShow":"Media Foundation",355,50,140,false);button(demo?"Demo ON":"Demo",505,50,90,demo);}
    if(step==1||step==6){button("Color",20,50,90,colorView);button("Depth",120,50,90,!colorView);}
    if(step==1){button("Mirror",220,50,90,colorView?mirrorColor:mirrorDepth);}
    if((step==1||step==6)&&!colorView)button(connecting?"Retrying...":"Retry depth",330,50,140,false);
    if(step==2){button("Display "+display,20,50,120,false);button("Show grid",150,50,120,false);button(grid+" x "+grid+" points",280,50,120,false);}
    if(step==3){button("Undo point",20,50,120,false);button(frozen?"Resume":"Freeze",150,50,120,frozen);}
    if(step==5){button("Next color",20,50,140,false);button("Show projector",180,50,150,false);}
    if(step==7){button("Capture in 5s",20,50,150,false);}
    if(step==8){button(referenceView?"Return to Depth":"Color reference",20,50,150,referenceView);button("Undo depth",180,50,130,false);}
    if(step==9){button(gameRunning?"Pause":"Play",20,50,100,gameRunning);button("Restart bubbles",130,50,140,false);button("Noise: "+(int)noiseMm+" mm",280,50,160,false);button("Min area: "+minSamples*16+" px",450,50,170,false);button("Show game",640,50,140,false);}
    if(step==4){button("Show projector",20,50,150,false);}
    p.fill(0xff9cb1c8);p.textAlign(PApplet.LEFT,PApplet.TOP);p.textSize(13);p.text(guidance(),20,96,890,62);
  }
  void stepControl(int x){
    if(step==5){if(x<160)testColor=(testColor+1)%5;else if(x>=180&&x<330)openProjector();return;}
    if(step==7){if(x<170)captureHigh();return;}
    if(step==8){if(x<170){referenceView=!referenceView;invalidateImage();}else if(x<310&&!depthClicks.isEmpty()){depthClicks.remove(depthClicks.size()-1);selected=depthClicks.size();}return;}
    if(step==9){if(x<120){gameRunning=!gameRunning;game.clearContacts();}else if(x<270)game.reset();else if(x<440){noiseMm=noiseMm>=Math.min(50,lowMm-highMm-5)?5:noiseMm+5;game.clearContacts();saveRange();}else if(x<620){minSamples=minSamples>=48?4:minSamples+4;game.clearContacts();saveRange();}else if(x>=640&&x<780)openProjector();return;}
    if(step==0){if(x<140)chooseRuntime();else if(x<240)connect();else if(x<345){String value=JOptionPane.showInputDialog("Color camera number (usually 0 or 1):",colorIndex);if(value!=null)try{int n=Integer.parseInt(value);if(n<0||n>20)throw new NumberFormatException();colorIndex=n;reset();saveSettings();}catch(NumberFormatException e){message="Choose a number from 0 to 20.";}}else if(x<495){backend=backend==0?Videoio.CAP_DSHOW:backend==Videoio.CAP_DSHOW?Videoio.CAP_MSMF:0;reset();saveSettings();}else if(x<595){demo=!demo;reset();setView(true);invalidateImage();if(cameraEnabled)switchCamera();}return;}
    if(step==1||step==6){if(x>=330&&x<470&&!colorView){retryDepth();return;}if(x<110)setView(true);else if(x<210)setView(false);else if(step==1&&x<310){if(colorView)mirrorColor=!mirrorColor;else mirrorDepth=!mirrorDepth;reset();invalidateImage();saveSettings();}return;}
    if(step==2){if(x<140){display=display%GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length+1;}else if(x<270)openProjector();else if(x<400){grid=grid==5?3:grid+1;reset();saveSettings();}return;}
    if(step==3){if(x<140)undoPoint();else if(x<270){if(!frozen){Frames frame=demo?demoFrame():color.latest;if(frame==null||(!demo&&!frame.fresh())){message="No fresh color frame to freeze.";return;}frozenFrame=frame;}frozen=!frozen;invalidateImage();}return;}
    if(step==4&&x<170)openProjector();
  }
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
    p=parent;p.frameRate(30);p.getSurface().setResizable(true);data=Paths.get(p.sketchPath("data"));loadSettings();
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
    updateInteraction();
    if(pendingLoad){pendingLoad=false;loadCalibration();}
    p.background(BG);p.pushMatrix();p.scale(uiScale());p.textAlign(PApplet.LEFT,PApplet.TOP);p.fill(INK);p.textSize(22);p.text("Step "+(step+1)+" / "+steps.length+" — "+steps[step],20,13);
    drawStepControls();
    Frames raw=demo?demoFrame():colorView?color.latest:depth.latest;
    if(frozen)raw=frozenFrame;
    if(step==8&&referenceView)raw=colorReference;
    boolean valid=raw!=null&&(demo||frozen||(step==8&&referenceView)||raw.fresh());
    p.fill(0xff050a10);p.rect(20,165,900,675);
    if(valid){
      boolean mirror=(step==8&&referenceView)?false:colorView?mirrorColor:mirrorDepth;String kind=(step==8&&referenceView)?"REFERENCE":colorView?"COLOR":"DEPTH";
      if(image==null||raw.sequence!=lastSequence||mirror!=lastMirror||!kind.equals(imageKind)||raw.width!=image.width||raw.height!=image.height){
        displayFrame=mirror?raw.mirrored():raw;
        if(image==null||image.width!=raw.width||image.height!=raw.height)image=p.createImage(raw.width,raw.height,PApplet.ARGB);
        image.loadPixels();System.arraycopy(displayFrame.argb,0,image.pixels,0,image.pixels.length);image.updatePixels();
        lastSequence=raw.sequence;lastMirror=mirror;imageKind=kind;
      }
      float scale=Math.min(900f/image.width,675f/image.height);imageW=image.width*scale;imageH=image.height*scale;imageX=20+(900-imageW)/2;imageY=165+(675-imageH)/2;
      p.image(image,imageX,imageY,imageW,imageH);
      if(demo)drawDemoTargets();
      if(step==8&&!referenceView){for(int i=0;i<depthClicks.size();i++){double[] uv=depthClicks.get(i);p.fill(ACCENT);p.textSize(18);p.text(i+1,imageX+(float)uv[0]*imageW,imageY+(float)uv[1]*imageH);}}
      if(kind.equals(mapSpace)||calibrating||(step==8&&referenceView)){
        for(int i=0;i<clicks.size();i++){double[] pt=clicks.get(i);p.stroke(ACCENT);p.fill(0xff10211b);p.ellipse(imageX+(float)pt[0]*imageW,imageY+(float)pt[1]*imageH,22,22);p.noStroke();p.fill(INK);p.textAlign(PApplet.CENTER,PApplet.CENTER);p.text(i+1,imageX+(float)pt[0]*imageW,imageY+(float)pt[1]*imageH);}
      }
      if(!colorView&&measureX>=0&&displayFrame!=null){float x=imageX+(measureX+.5f)/displayFrame.width*imageW,y=imageY+(measureY+.5f)/displayFrame.height*imageH;p.stroke(ACCENT);p.line(x-10,y,x+10,y);p.line(x,y-10,x,y+10);p.noStroke();}
    }else{displayFrame=null;p.fill(0xff90a5bf);p.textAlign(PApplet.CENTER,PApplet.CENTER);p.textSize(16);p.text("No fresh "+(colorView?"color":"depth")+" frame",470,400);p.textAlign(PApplet.LEFT,PApplet.TOP);p.textSize(15);p.text(colorView?color.status:depth.status,65,450,810,230);if(!colorView)p.text("Runtime: "+(runtime.isEmpty()?"not selected — go Back to Connect camera and choose the OpenNI2.dll folder":runtime)+"\n\nColor capture is paused while Depth is selected. Close other depth viewers, then click Retry depth.",65,690,810,130);}
    p.fill(PANEL);p.rect(940,165,280,675,8);p.fill(ACCENT);p.textAlign(PApplet.LEFT,PApplet.TOP);p.textSize(16);p.text(steps[step],958,185);
    p.fill(INK);p.textSize(13);p.text("Points: "+(step==8?depthClicks.size():clicks.size())+" / "+(grid*grid),958,220);
    p.text(guidance(),958,252,242,155);
    p.fill(ACCENT);p.textSize(23);p.text(step==9?game.state().score+" popped":Float.isNaN(measurement)?"-- mm":String.format(java.util.Locale.ROOT,"%.0f mm",measurement),958,410);p.textSize(14);p.text(step==9?"Depth-controlled bubbles":Float.isNaN(measurement)?"":String.format(java.util.Locale.ROOT,"%.3f m from camera",measurement/1000),958,445);
    p.fill(0xffa7bad1);p.textSize(12);p.text("Depth and color are different views. Measure distance in Depth.\n\nKeep the camera and projector fixed after calibration.",958,485,242,85);
    p.fill(INK);p.text((step>=6?"Surface low: "+(int)lowMm+" mm\nHigh point: "+(Float.isFinite(highMm)?(int)highMm+" mm":"not selected")+"\n":"")+(captureAt>0?"Capture in "+Math.max(1,(captureAt-System.currentTimeMillis()+999)/1000)+" seconds":message),958,580,242,100);p.fill(0xffa7bad1);p.text((demo?"SYNTHETIC DEMO\n":depth.status+"\n"+color.status),958,680,245,50);
    if(step>0)button("Back",958,735,90,false);
    if(step<9)button(step==4?"Save & next":"Next",1060,735,140,true);

    p.popMatrix();

  }
  void drawDemoTargets(){
    List<double[]> targets=Mapping.grid(grid);p.textAlign(PApplet.CENTER,PApplet.CENTER);p.textSize(15);
    for(int i=0;i<targets.size();i++){double[] xy=targets.get(i);float x=imageX+(float)xy[0]*imageW,y=imageY+(float)xy[1]*imageH;p.stroke(0xffffffff);p.fill(0xff111923);p.ellipse(x,y,28,28);p.noStroke();p.fill(0xffffffff);p.text(i+1,x,y);}
  }
  Frames demoFrame(){int key=grid*10+(colorView?1:2);if(demoCache!=null&&demoCache.sequence==key)return demoCache;int w=640,h=480;int[] pixels=new int[w*h];float[] values=new float[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x;values[i]=1800+x*.5f+y*.25f;pixels[i]=colorView?0xff576475:Frames.heat(values[i]);}demoCache=new Frames(w,h,pixels,values,key);return demoCache;}
  public void mousePressed(int x,int y){
    x=Math.round(x/uiScale());y=Math.round(y/uiScale());
    if(y>=735&&y<769&&x>=958){if(x<1048)backStep();else if(x>=1060&&x<1200)nextStep();return;}

    if(y>=50&&y<84&&x>=20){stepControl(x);return;}
    if(step<3)return;
    if(step==9||step==5||step==7&&!frozen||step==8&&referenceView)return;
    if(displayFrame==null||!inside(x,y,imageX,imageY,imageW,imageH))return;
    double u=(x-imageX)/imageW,v=(y-imageY)/imageH;
    if(step==8){if(depthClicks.size()<grid*grid){depthClicks.add(new double[]{u,v});selected=depthClicks.size();}return;}
    if(calibrating){
      if(displayFrame.width!=mapWidth||displayFrame.height!=mapHeight){message="Frame dimensions changed. Restart calibration.";return;}
      if(clicks.size()<grid*grid){clicks.add(new double[]{u,v});selected=clicks.size();mapping=null;message=clicks.size()==grid*grid?"All points selected. Click Fit.":"Next target: "+(clicks.size()+1);}
    }else if(testing&&mapping!=null&&mapSpace.equals(colorView?"COLOR":"DEPTH")){
      if(displayFrame.width!=mapWidth||displayFrame.height!=mapHeight){message="Frame resolution changed. Recalibrate before testing.";return;}
      probe=mapping.apply(u,v);probeChecked=true;openProjector();message="Mapped crosshair sent to projector.";
    }else if(!colorView){measureX=Math.min(displayFrame.width-1,(int)(u*displayFrame.width));measureY=Math.min(displayFrame.height-1,(int)(v*displayFrame.height));measurement=displayFrame.sample(measureX,measureY);if(Float.isFinite(measurement)&&step==6){lowMm=measurement;surfaceFrame=displayFrame;highMm=Float.NaN;depthMapping=null;}
      if(Float.isFinite(measurement)&&step==7){if(measurement<lowMm-noiseMm)highMm=measurement;else{highMm=Float.NaN;message="Choose a body point closer to the camera than the surface.";return;}}
      message=Float.isNaN(measurement)?"No reliable depth here. Click a nearby solid point.":"Sample at pixel ("+measureX+", "+measureY+").";}
    else if(mapping!=null&&mapSpace.equals("COLOR")&&displayFrame.width==mapWidth&&displayFrame.height==mapHeight){probe=mapping.apply(u,v);probeChecked=true;openProjector();message="Mapped crosshair sent to projector.";}
  }
  void setView(boolean value){if(colorView!=value){if(calibrating)calibrating=false;colorView=value;frozen=false;testing=false;invalidateImage();if(cameraEnabled&&!demo)switchCamera();}}
  void invalidateImage(){image=null;displayFrame=null;lastSequence=-1;}
  void reset(){surfaceFrame=null;depthMapping=null;lowMm=highMm=Float.NaN;depthClicks.clear();gameRunning=false;captureAt=0;game.clearContacts();clicks.clear();selected=0;mapping=null;probe=null;measurement=Float.NaN;measureX=measureY=-1;calibrating=false;testing=false;message="Points reset. Start Calibrate to select a new grid.";}
  void fit(){if(!ready){message="Image library not ready.";return;}try{if(clicks.size()!=grid*grid)throw new IllegalArgumentException("Select all "+grid*grid+" points.");mapping=Mapping.fit(clicks,Mapping.grid(grid));int w=projector==null?1024:projector.width,h=projector==null?768:projector.height;message=String.format(java.util.Locale.ROOT,"Fit RMS: %.2f projector pixels. Click Save to keep it.",mapping.rms(clicks,Mapping.grid(grid),w,h));}catch(Exception e){message=e.getMessage();}}
  void chooseRuntime(){JFileChooser chooser=new JFileChooser(runtime.isEmpty()?System.getProperty("user.home"):runtime);chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);chooser.setDialogTitle("Choose folder containing OpenNI2.dll");if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION){runtime=chooser.getSelectedFile().getAbsolutePath();reset();saveSettings();message="Runtime selected: "+runtime+". Click Connect.";}}
  void connect(){if(!ready||connecting)return;cameraEnabled=true;demo=false;frozen=false;invalidateImage();saveSettings();switchCamera();}
  void retryDepth(){if(!ready||connecting)return;cameraEnabled=true;demo=false;setView(false);frozen=false;invalidateImage();switchCamera();}
  /** Serialize stop/start operations: Astra RGB and depth never capture together. */
  void switchCamera(){
    final int request=++cameraRequest;
    final boolean wantColor=colorView,wantDemo=demo;
    final String folder=runtime;
    final int index=colorIndex,api=backend;
    connecting=true;
    new Thread(()->{
      synchronized(cameraSwitchLock){
        try{
          if(request!=cameraRequest)return;
          color.stop();depth.stop();
          if(request!=cameraRequest)return;
          if(!wantDemo){if(wantColor)color.start(index,api);else depth.start(folder);}
          message=wantDemo?"Demo selected; cameras paused.":wantColor?"Color active; depth paused. Select Depth to switch streams.":"Depth active; color paused. Waiting for depth frames.";
        }catch(Throwable e){message="Camera switch failed: "+e.getMessage();System.err.println(message);}
        finally{if(request==cameraRequest)connecting=false;}
      }
    },"Switch camera stream").start();
  }
  void openProjector(){if(projector!=null&&projector.screen==display){projector.getSurface().setVisible(true);projector.loop();return;}if(projector!=null){projector.noLoop();projector.getSurface().setVisible(false);}projector=new ProjectorView(this,display);PApplet.runSketch(new String[]{"Projector grid"},projector);}
  JSONObject settings(){JSONObject j=new JSONObject();j.setString("runtime",runtime);j.setInt("color_index",colorIndex);j.setInt("backend",backend);j.setBoolean("mirror_color",mirrorColor);j.setBoolean("mirror_depth",mirrorDepth);j.setInt("grid",grid);return j;}
  void saveSettings(){try{Files.createDirectories(data);p.saveJSONObject(settings(),data.resolve("settings.json").toString());}catch(Exception e){message="Cannot save settings: "+e.getMessage();}}
  void loadSettings(){try{Path file=data.resolve("settings.json");if(!Files.exists(file)){Path old=Paths.get(p.sketchPath()).resolve("../../.local/camera.json").normalize();if(Files.exists(old)){JSONObject j=p.loadJSONObject(old.toString());runtime=j.getString("runtime","");colorIndex=j.getInt("color_index",0);String b=j.getString("color_backend","auto");backend=b.equals("dshow")?Videoio.CAP_DSHOW:b.equals("msmf")?Videoio.CAP_MSMF:0;mirrorColor=j.getBoolean("mirror_color",false);mirrorDepth=j.getBoolean("mirror_depth",false);}return;}JSONObject j=p.loadJSONObject(file.toString());runtime=j.getString("runtime","");colorIndex=j.getInt("color_index",0);backend=j.getInt("backend",0);mirrorColor=j.getBoolean("mirror_color",false);mirrorDepth=j.getBoolean("mirror_depth",false);grid=j.getInt("grid",3);if(grid<3||grid>5)grid=3;}catch(Exception e){message="Settings could not be read. Choose runtime and camera again.";}}
  boolean saveCalibration(){if(mapping==null){message="Fit a valid mapping before saving.";return false;}try{JSONObject j=settings();j.setString("space",mapSpace);j.setInt("width",mapWidth);j.setInt("height",mapHeight);JSONArray values=new JSONArray();for(double v:mapping.h)values.append(v);j.setJSONArray("homography",values);JSONArray points=new JSONArray();for(double[] pt:clicks){JSONArray a=new JSONArray();a.append(pt[0]);a.append(pt[1]);points.append(a);}j.setJSONArray("points",points);Files.createDirectories(data);p.saveJSONObject(j,data.resolve("calibration.json").toString());message="Saved to data/calibration.json.";return true;}catch(Exception e){message="Save failed: "+e.getMessage();return false;}}
  void loadCalibration(){try{Path file=data.resolve("calibration.json");if(!Files.exists(file))return;JSONObject j=p.loadJSONObject(file.toString());if(j.getInt("color_index",colorIndex)!=colorIndex||j.getInt("backend",backend)!=backend||!j.getString("runtime",runtime).equals(runtime))throw new IllegalArgumentException("Saved camera settings differ. Select the matching camera/runtime or recalibrate.");JSONArray a=j.getJSONArray("homography");double[] values=new double[9];for(int i=0;i<9;i++)values[i]=a.getDouble(i);Mapping restored=new Mapping(values);JSONArray pts=j.getJSONArray("points");int g=j.getInt("grid");if(g<3||g>5||pts.size()!=g*g)throw new IllegalArgumentException("Invalid point count.");clicks.clear();for(int i=0;i<pts.size();i++){JSONArray pt=pts.getJSONArray(i);clicks.add(new double[]{pt.getDouble(0),pt.getDouble(1)});}grid=g;mapSpace=j.getString("space");mapWidth=j.getInt("width");mapHeight=j.getInt("height");mirrorColor=j.getBoolean("mirror_color",false);mirrorDepth=j.getBoolean("mirror_depth",false);mapping=restored;selected=clicks.size();calibrating=false;invalidateImage();message="Saved "+clicks.size()+"-point "+mapSpace.toLowerCase()+" mapping loaded.";}catch(Exception e){message="Cannot load calibration: "+e.getMessage();}}
  public void keyPressed(char key,int code){if(step==3&&(key=='z'||key=='Z'))undoPoint();}

  public void close(){cameraRequest++;cameraEnabled=false;synchronized(cameraSwitchLock){try{depth.stop();}catch(Exception ignored){}try{color.stop();}catch(Exception ignored){}}}
}
