import java.util.*;

/** Immutable frame snapshots shared from capture threads to Processing's draw loop. */
public final class Frames {
  public final int width,height;
  public final int[] argb;
  public final float[] depthMm;
  public final long timeNanos;
  public final int sequence;
  public Frames(int w,int h,int[] image,float[] depth,int seq) {width=w;height=h;argb=image;depthMm=depth;sequence=seq;timeNanos=System.nanoTime();}
  public boolean fresh(){return System.nanoTime()-timeNanos<1_000_000_000L;}
  public float sample(int x,int y) {
    if(depthMm==null||x<0||x>=width||y<0||y>=height)return Float.NaN;
    float[] values=new float[25];int count=0;
    for(int yy=Math.max(0,y-2);yy<=Math.min(height-1,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(width-1,x+2);xx++){
      float z=depthMm[yy*width+xx];if(Float.isFinite(z)&&z>0)values[count++]=z;
    }
    if(count<3)return Float.NaN;
    Arrays.sort(values,0,count);return count%2==1?values[count/2]:(values[count/2-1]+values[count/2])*.5f;
  }
  public Frames mirrored() {
    int[] image=new int[argb.length];float[] depth=depthMm==null?null:new float[depthMm.length];
    for(int y=0;y<height;y++)for(int x=0;x<width;x++){int src=y*width+width-1-x,dst=y*width+x;image[dst]=argb[src];if(depth!=null)depth[dst]=depthMm[src];}
    return new Frames(width,height,image,depth,sequence);
  }
  public static int heat(float mm) {
    if(!Float.isFinite(mm)||mm<=0)return 0xff000000;
    float t=Math.min(1,mm/6000f);int r=(int)(255*Math.max(0,Math.min(1,1.5-Math.abs(4*t-3))));
    int g=(int)(255*Math.max(0,Math.min(1,1.5-Math.abs(4*t-2))));int b=(int)(255*Math.max(0,Math.min(1,1.5-Math.abs(4*t-1))));
    return 0xff000000|(r<<16)|(g<<8)|b;
  }
}
