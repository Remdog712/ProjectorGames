import java.util.*;
public final class BubbleGameChecks {
  static int checks=0;
  static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
  static Frames frame(int w,int h,float[] z,int sequence){return new Frames(w,h,new int[w*h],z,sequence);}
  static float[] plane(int n,float z){float[] out=new float[n];Arrays.fill(out,z);return out;}
  static float[] touch(int w,int h,BubbleGame.Bubble b,float z){float[] out=plane(w*h,2000);int cx=(int)(b.x*w),cy=(int)(b.y*h);for(int y=Math.max(0,cy-12);y<Math.min(h,cy+12);y++)for(int x=Math.max(0,cx-12);x<Math.min(w,cx+12);x++)out[y*w+x]=z;return out;}
  public static void main(String[] args){
    int w=160,h=120;Frames surface=frame(w,h,plane(w*h,2000),0);Mapping identity=new Mapping(new double[]{1,0,0,0,1,0,0,0,1});
    BubbleGame game=new BubbleGame();game.reset();BubbleGame.Bubble bubble=game.state().bubbles.get(0);
    float[] z=touch(w,h,bubble,1900);
    game.update(frame(w,h,z,1),surface,identity,500,20,12);check(game.state().score==0,"Single-frame noise must not pop");
    game.update(frame(w,h,z,2),surface,identity,500,20,12);check(game.state().score>0,"Stable body pixels must pop overlapping bubbles");check(!game.state().contacts.isEmpty(),"Stable contact visualization");
    int score=game.state().score;game.update(frame(w,h,z,2),surface,identity,500,20,12);check(game.state().score==score,"Do not process same capture twice");
    for(float depth:new float[]{1990,1400,0,Float.NaN}){game.reset();bubble=game.state().bubbles.get(0);z=touch(w,h,bubble,depth);game.update(frame(w,h,z,1),surface,identity,500,20,12);game.update(frame(w,h,z,2),surface,identity,500,20,12);check(game.state().score==0,"Reject noise/outside layer/invalid depth: "+depth);}
    game.reset();float[] speck=plane(w*h,2000);speck[60*w+80]=1900;game.update(frame(w,h,speck,1),surface,identity,500,20,12);game.update(frame(w,h,speck,2),surface,identity,500,20,12);check(game.state().score==0,"Reject small blob");
    game.reset();bubble=game.state().bubbles.get(0);z=touch(w,h,bubble,1900);Frames missing=frame(w,h,plane(w*h,0),0);game.update(frame(w,h,z,1),missing,identity,500,20,12);game.update(frame(w,h,z,2),missing,identity,500,20,12);check(game.state().score==0,"Missing baseline never triggers");
    game.reset();Mapping outside=new Mapping(new double[]{1,0,2,0,1,2,0,0,1});game.update(frame(w,h,z,1),surface,outside,500,20,12);game.update(frame(w,h,z,2),surface,outside,500,20,12);check(game.state().score==0,"Ignore interaction outside projected area");
    game.reset();bubble=game.state().bubbles.get(0);BubbleGame.Bubble raw=new BubbleGame.Bubble(1-bubble.x,bubble.y,bubble.r);z=touch(w,h,raw,1900);
    Mapping flip=new Mapping(new double[]{-1,0,1,0,1,0,0,0,1});game.update(frame(w,h,z,1),surface,flip,500,20,12);game.update(frame(w,h,z,2),surface,flip,500,20,12);check(!game.state().bubbles.contains(bubble),"Use the fitted depth homography for collisions");
    Mapping projective=new Mapping(new double[]{.9,.1,.03,-.1,.95,.02,.05,.02,1});double[] original=projective.apply(.2,.7),flipped=projective.flippedHorizontally().apply(.2,.7);
    check(Math.abs(flipped[0]-(1-original[0]))<1e-10&&Math.abs(flipped[1]-original[1])<1e-10,"Flip projector X after projective mapping without flipping Y");
    game.reset();bubble=game.state().bubbles.get(0);z=touch(w,h,bubble,1900);
    game.update(frame(w,h,z,10),surface,identity,500,20,12,false);check(game.state().outline.isEmpty(),"Halo rejects single-frame noise");
    game.update(frame(w,h,z,11),surface,identity,500,20,12,false);check(!game.state().outline.isEmpty(),"Stable silhouette produces halo outline");check(game.state().score==0,"Halo mode never pops bubbles");
    double[] center=game.state().contacts.get(0);boolean interior=false;for(double[] edge:game.state().outline)if(Math.hypot(edge[0]-center[0],edge[1]-center[1])<.02)interior=true;
    check(!interior,"Halo draws boundary rather than filling the silhouette");
    game.clearContacts();check(game.state().outline.isEmpty(),"Pause/stale frames clear halo");
    game.clearContacts();check(game.state().contacts.isEmpty(),"Clear contacts on pause/stale frame");
    System.out.println("PASS: "+checks+" bubble interaction checks.");
  }
}
