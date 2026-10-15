import java.util.*;
/** Follow the illuminated pad with any stable body part. */
public final class TouchPads {
  public static final double[][] CENTERS={{.25,.3},{.75,.3},{.25,.72},{.75,.72}};
  private double aspect=1;
  public synchronized void setAspect(double value){if(Double.isFinite(value)&&value>0)aspect=value;}
  private int target,score;private long lastHit=Long.MIN_VALUE/2;
  public synchronized void reset(){target=0;score=0;lastHit=Long.MIN_VALUE/2;}
  public synchronized int target(){return target;}public synchronized int score(){return score;}
  public synchronized void update(BubbleGame.State state,long now){
    if(now-lastHit<600)return;double[] center=CENTERS[target];
    for(List<double[]> points:Arrays.asList(state.outline,state.contacts))for(double[] uv:points)if(Math.hypot((uv[0]-center[0])*aspect,uv[1]-center[1])<.12){score++;target=(target+1)%4;lastHit=now;return;}
  }
}
