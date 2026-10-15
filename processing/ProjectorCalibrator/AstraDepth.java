import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.*;
import java.nio.file.*;

/** Direct OpenNI2 C API via JNA: no Python server or separately compiled JNI bridge. */
public final class AstraDepth {
  public interface Oni extends Library {
    int oniInitialize(int version);void oniShutdown();String oniGetExtendedError();
    int oniDeviceOpen(String uri,PointerByReference device);int oniDeviceClose(Pointer device);
    int oniDeviceCreateStream(Pointer device,int sensor,PointerByReference stream);
    int oniStreamStart(Pointer stream);void oniStreamStop(Pointer stream);void oniStreamDestroy(Pointer stream);
    int oniWaitForAnyStream(Pointer[] streams,int count,IntByReference ready,int timeoutMs);
    int oniStreamReadFrame(Pointer stream,PointerByReference frame);void oniFrameRelease(Pointer frame);
  }
  public interface Kernel extends StdCallLibrary {
    boolean SetDllDirectoryW(WString path);boolean SetEnvironmentVariableW(WString name,WString value);
  }
  @Structure.FieldOrder({"pixelFormat","resolutionX","resolutionY","fps"})
  public static class Mode extends Structure {public int pixelFormat,resolutionX,resolutionY,fps;}
  @Structure.FieldOrder({"dataSize","data","sensorType","timestamp","frameIndex","width","height","videoMode","croppingEnabled","cropOriginX","cropOriginY","stride"})
  public static class Frame extends Structure {
    public int dataSize;public Pointer data;public int sensorType;public long timestamp;
    public int frameIndex,width,height;public Mode videoMode=new Mode();
    public int croppingEnabled,cropOriginX,cropOriginY,stride;
    public Frame(){}public Frame(Pointer p){super(p);read();}
  }
  public volatile Frames latest;
  public volatile String status="Depth not connected.";
  private volatile boolean running;
  private Thread thread;
  public synchronized void start(String folder) {
    if(thread!=null&&thread.isAlive())throw new IllegalStateException("Disconnect depth before reconnecting.");
    running=true;latest=null;status="Connecting depth...";
    thread=new Thread(()->capture(folder),"Astra depth");thread.setDaemon(true);thread.start();
  }
  public synchronized void stop() {
    running=false;
    if(thread!=null)try{thread.join(1800);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    if(thread!=null&&thread.isAlive())throw new IllegalStateException("Depth driver is still closing. Restart the sketch before retrying.");
    latest=null;status="Depth disconnected.";
  }
  private void check(Oni api,int result,String operation) {if(result!=0)throw new IllegalStateException(operation+": "+api.oniGetExtendedError()+" ("+result+")");}
  private void capture(String folder) {
    Oni api=null;Pointer device=null,stream=null;boolean initialized=false;
    try {
      Path dll=Paths.get(folder).toAbsolutePath();if(Files.isDirectory(dll))dll=dll.resolve("OpenNI2.dll");
      if(!Files.isRegularFile(dll))throw new IllegalArgumentException("Choose the folder containing the x64 OpenNI2.dll.");
      Path root=dll.getParent();
      if(Platform.isWindows()){
        Kernel kernel=Native.load("kernel32",Kernel.class);
        if(!kernel.SetDllDirectoryW(new WString(root.toString())))throw new IllegalStateException("Cannot set runtime DLL search directory.");
        Path drivers=root.resolve("OpenNI2").resolve("Drivers");
        if(Files.isDirectory(drivers)&&!kernel.SetEnvironmentVariableW(new WString("OPENNI2_DRIVERS_PATH"),new WString(drivers.toString())))throw new IllegalStateException("Cannot set local OpenNI driver directory.");
      }
      api=Native.load(dll.toString(),Oni.class);
      check(api,api.oniInitialize(2002),"Initialize");initialized=true;
      PointerByReference out=new PointerByReference();check(api,api.oniDeviceOpen(null,out),"Open camera");device=out.getValue();
      check(api,api.oniDeviceCreateStream(device,2,out),"Create depth stream");stream=out.getValue();check(api,api.oniStreamStart(stream),"Start depth");
      int sequence=0;long lastFrame=System.currentTimeMillis();status="Depth stream started; waiting for frames...";
      while(running){
        int ready=api.oniWaitForAnyStream(new Pointer[]{stream},1,new IntByReference(),200);
        if(ready==102){if(System.currentTimeMillis()-lastFrame>5000)status="Depth stream opened but no frames arrived for 5 seconds. Close other depth viewers and retry; check the camera USB connection.";continue;}check(api,ready,"Wait for depth");
        PointerByReference framePointer=new PointerByReference();check(api,api.oniStreamReadFrame(stream,framePointer),"Read depth");
        try {
          Frame frame=new Frame(framePointer.getValue());
          if(frame.videoMode.pixelFormat!=100&&frame.videoMode.pixelFormat!=101)throw new IllegalStateException("Expected depth in millimeters or 100-micrometer units.");
          if(frame.width<1||frame.height<1||frame.stride<frame.width*2||frame.dataSize<frame.stride*frame.height)throw new IllegalStateException("Invalid OpenNI depth frame layout.");
          float scale=frame.videoMode.pixelFormat==101?.1f:1f;
          ByteBuffer bytes=frame.data.getByteBuffer(0,frame.dataSize).order(ByteOrder.nativeOrder());
          float[] depth=new float[frame.width*frame.height];int[] colors=new int[depth.length];
          for(int y=0;y<frame.height;y++)for(int x=0;x<frame.width;x++){
            int i=y*frame.width+x;float z=(bytes.getShort(y*frame.stride+x*2)&0xffff)*scale;depth[i]=z;colors[i]=Frames.heat(z);
          }
          latest=new Frames(frame.width,frame.height,colors,depth,++sequence);lastFrame=System.currentTimeMillis();status="Depth connected: "+frame.width+" x "+frame.height+".";
        } finally {api.oniFrameRelease(framePointer.getValue());}
      }
    } catch(Throwable e){status="Depth: "+e.getClass().getSimpleName()+": "+e.getMessage();System.err.println(status);}
    finally {
      if(api!=null){if(stream!=null){try{api.oniStreamStop(stream);}catch(Throwable ignored){}try{api.oniStreamDestroy(stream);}catch(Throwable ignored){}}if(device!=null)try{api.oniDeviceClose(device);}catch(Throwable ignored){}if(initialized)try{api.oniShutdown();}catch(Throwable ignored){}}
      running=false;latest=null;
    }
  }
}
