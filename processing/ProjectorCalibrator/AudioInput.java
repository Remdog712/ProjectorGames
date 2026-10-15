import javax.sound.sampled.*;

/** Live default-microphone level only. Audio is never saved or transmitted. */
public final class AudioInput {
  public volatile float level;
  public volatile String status="Microphone off";
  private volatile boolean running;
  private volatile TargetDataLine line;
  private Thread thread;
  public synchronized boolean enabled(){return running;}
  public synchronized void start(){
    if(thread!=null&&thread.isAlive())return;
    running=true;status="Opening microphone...";
    thread=new Thread(()->{
      TargetDataLine capture=null;
      try{
        AudioFormat format=new AudioFormat(16000,16,1,true,false);
        capture=(TargetDataLine)AudioSystem.getLine(new DataLine.Info(TargetDataLine.class,format));line=capture;
        capture.open(format,4096);if(!running)return;capture.start();status="Microphone live";
        byte[] buffer=new byte[1024];while(running){int n=capture.read(buffer,0,buffer.length);if(n>0){float target=Math.min(1,rms(buffer,n)*8);level=level*.65f+target*.35f;}}
      }catch(Exception e){if(running)status="Microphone unavailable: "+e.getMessage();}
      finally{if(capture!=null){capture.stop();capture.close();}line=null;running=false;level=0;}
    },"Microphone level");thread.setDaemon(true);thread.start();
  }
  public void stop(){running=false;TargetDataLine capture=line;if(capture!=null){capture.stop();capture.close();}level=0;status="Microphone off";}
  public static float rms(byte[] bytes,int length){double sum=0;int samples=Math.min(length,bytes.length)/2;if(samples==0)return 0;for(int i=0;i<samples;i++){int v=(short)((bytes[i*2]&255)|(bytes[i*2+1]<<8));double f=v/32768.0;sum+=f*f;}return (float)Math.sqrt(sum/samples);}
}
