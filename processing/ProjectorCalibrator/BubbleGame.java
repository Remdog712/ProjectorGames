import java.util.*;

/** Depth-only interaction. Coordinates use an explicitly fitted depth homography. */
public final class BubbleGame {
  public static final class Bubble {public final double x,y,r;Bubble(double x,double y,double r){this.x=x;this.y=y;this.r=r;}}
  public static final class State {public final List<Bubble> bubbles;public final List<double[]> contacts,outline;public final int score;State(List<Bubble>b,List<double[]>c,List<double[]>o,int s){bubbles=b;contacts=c;outline=o;score=s;}}
  private final Random random=new Random(712);
  private final List<Bubble> bubbles=new ArrayList<>();
  private List<double[]> previous=new ArrayList<>(),contacts=new ArrayList<>(),outline=new ArrayList<>();
  private int score=0,lastSequence=-1;
  private double aspect=1;
  public synchronized void setAspect(double value){if(Double.isFinite(value)&&value>0)aspect=value;}
  public synchronized void reset(){score=0;lastSequence=-1;previous.clear();contacts.clear();outline.clear();bubbles.clear();for(int i=0;i<8;i++)bubbles.add(spawn());}
  private Bubble spawn(){return new Bubble(.15+random.nextDouble()*.7,.15+random.nextDouble()*.7,.055);}
  public synchronized State state(){return new State(new ArrayList<>(bubbles),new ArrayList<>(contacts),new ArrayList<>(outline),score);}
  public synchronized void clearContacts(){contacts.clear();outline.clear();previous.clear();}
  public synchronized void update(Frames frame,Frames surface,Mapping mapping,float maxHeight,float noise,int minSamples){
    update(frame,surface,mapping,maxHeight,noise,minSamples,true);
  }
  public synchronized void update(Frames frame,Frames surface,Mapping mapping,float maxHeight,float noise,int minSamples,boolean popBubbles){
    if(frame==null||surface==null||mapping==null||frame.depthMm==null||surface.depthMm==null||frame.width!=surface.width||frame.height!=surface.height){clearContacts();return;}
    if(frame.sequence==lastSequence)return;lastSequence=frame.sequence;
    int stride=4,w=(frame.width+stride-1)/stride,h=(frame.height+stride-1)/stride;
    boolean[] mask=new boolean[w*h];
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=Math.min(frame.height-1,y*stride)*frame.width+Math.min(frame.width-1,x*stride);float base=surface.depthMm[i],z=frame.depthMm[i],height=base-z;mask[y*w+x]=Float.isFinite(base)&&Float.isFinite(z)&&base>0&&z>0&&height>=noise&&height<=maxHeight;}
    boolean[] foreground=mask.clone();outline=new ArrayList<>();
    List<double[]> found=new ArrayList<>(),hitPoints=new ArrayList<>();int[] queue=new int[mask.length];
    for(int seed=0;seed<mask.length;seed++)if(mask[seed]){
      int head=0,tail=1;queue[0]=seed;mask[seed]=false;double sx=0,sy=0;
      while(head<tail){int at=queue[head++],x=at%w,y=at/w;sx+=x*stride;sy+=y*stride;
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;int n=ny*w+nx;if(mask[n]){mask[n]=false;queue[tail++]=n;}}
      }
      if(tail>=minSamples){double[] uv=mapping.apply(sx/tail/frame.width,sy/tail/frame.height);if(Double.isFinite(uv[0])&&Double.isFinite(uv[1])){found.add(uv);boolean stable=false;for(double[] old:previous)if(Math.hypot(uv[0]-old[0],uv[1]-old[1])<.12){stable=true;break;}if(stable)for(int j=0;j<tail;j++){int at=queue[j],px=at%w,py=at/w;boolean edge=px==0||py==0||px==w-1||py==h-1||!foreground[at-1]||!foreground[at+1]||!foreground[at-w]||!foreground[at+w];if(!popBubbles&&!edge)continue;double[] point=mapping.apply((at%w)*stride/(double)frame.width,(at/w)*stride/(double)frame.height);if(Double.isFinite(point[0])&&Double.isFinite(point[1])&&point[0]>=0&&point[0]<=1&&point[1]>=0&&point[1]<=1){if(popBubbles)hitPoints.add(point);if(edge)outline.add(point);}}}}
    }
    contacts=new ArrayList<>();for(double[] point:found)for(double[] old:previous)if(Math.hypot(point[0]-old[0],point[1]-old[1])<.12){contacts.add(point);break;}
    previous=found;
    if(popBubbles)for(int i=0;i<bubbles.size();i++){Bubble b=bubbles.get(i);for(double[] point:hitPoints)if(Math.hypot((b.x-point[0])*aspect,b.y-point[1])<=b.r+.025){score++;bubbles.set(i,spawn());break;}}
  }
}
