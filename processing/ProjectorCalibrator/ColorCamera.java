import org.opencv.core.*;
import org.opencv.videoio.*;

public final class ColorCamera {
  public volatile Frames latest;
  public volatile String status="Color not connected.";
  private volatile boolean running;
  private Thread thread;
  public synchronized void start(int index,int backend) {
    if(thread!=null&&thread.isAlive())throw new IllegalStateException("Color driver is still active.");
    running=true;latest=null;status="Connecting color...";
    thread=new Thread(()->capture(index,backend),"Astra color");thread.setDaemon(true);thread.start();
  }
  public synchronized void stop() {
    running=false;
    if(thread!=null)try{thread.join(1800);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    if(thread!=null&&thread.isAlive())throw new IllegalStateException("Color driver is still closing. Restart the sketch before retrying.");
    latest=null;status="Color disconnected.";
  }
  private void capture(int index,int backend) {
    VideoCapture camera=null;Mat mat=new Mat();
    try {
      int[] backends=backend==0?new int[]{Videoio.CAP_DSHOW,Videoio.CAP_MSMF}:new int[]{backend};
      boolean ok=false;
      for(int candidate:backends){
        camera=new VideoCapture(index,candidate);
        if(camera.isOpened()){camera.set(Videoio.CAP_PROP_FRAME_WIDTH,640);camera.set(Videoio.CAP_PROP_FRAME_HEIGHT,480);camera.set(Videoio.CAP_PROP_FPS,30);ok=camera.read(mat)&&!mat.empty();}
        if(ok)break;camera.release();camera=null;
      }
      if(!ok)throw new IllegalStateException("No color frames. Close Windows Camera, check number/backend and reconnect.");
      int sequence=0;status="Color connected.";
      while(running){
        if(mat.channels()!=3)throw new IllegalStateException("Expected 3-channel BGR color.");
        byte[] bytes=new byte[(int)(mat.total()*mat.channels())];mat.get(0,0,bytes);int[] pixels=new int[mat.rows()*mat.cols()];
        for(int i=0,j=0;i<pixels.length;i++,j+=3)pixels[i]=0xff000000|((bytes[j+2]&255)<<16)|((bytes[j+1]&255)<<8)|(bytes[j]&255);
        latest=new Frames(mat.cols(),mat.rows(),pixels,null,++sequence);
        if(!camera.read(mat)||mat.empty())throw new IllegalStateException("Color feed stopped.");
      }
    }catch(Throwable e){status="Color: "+e.getClass().getSimpleName()+": "+e.getMessage();System.err.println(status);}
    finally{if(camera!=null)camera.release();mat.release();running=false;latest=null;}
  }
}
