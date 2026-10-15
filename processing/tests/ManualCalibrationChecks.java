import java.util.*;
import com.sun.jna.*;
import org.opencv.core.*;

public final class ManualCalibrationChecks {
  static int checks=0;
  static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    nu.pattern.OpenCV.loadLocally();
    for(int count:new int[]{3,4,5}){
      List<double[]> source=Mapping.grid(count),target=new ArrayList<>();
      Mapping truth=new Mapping(new double[]{.9,.08,.04,-.03,.95,.03,.06,-.02,1});
      for(double[] p:source)target.add(truth.apply(p[0],p[1]));
      Mapping fit=Mapping.fit(source,target);double[] expected=truth.apply(.37,.62),actual=fit.apply(.37,.62);
      check(source.size()==count*count,"Grid count");check(Math.hypot(actual[0]-expected[0],actual[1]-expected[1])<1e-5,"Projective mapping with "+count*count+" pairs");
      check(fit.rms(source,target,1920,1080)<.01,"Reprojection error");
    }
    try{Mapping.fit(Mapping.grid(3).subList(0,4),Mapping.grid(3).subList(0,4));throw new AssertionError("Four points were accepted");}catch(IllegalArgumentException expected){checks++;}
    List<double[]> line=new ArrayList<>();for(int i=0;i<9;i++)line.add(new double[]{i*.1,i*.1});
    try{Mapping.fit(line,Mapping.grid(3));throw new AssertionError("Collinear points accepted");}catch(IllegalArgumentException expected){checks++;}
    int[] pixels=new int[25];float[] depth=new float[25];Arrays.fill(depth,2000);depth[12]=0;
    Frames frame=new Frames(5,5,pixels,depth,1);check(frame.sample(2,2)==2000,"Median excludes missing depth");check(Float.isNaN(frame.sample(-1,0)),"Outside image rejected");
    check(Float.isNaN(new Frames(5,5,pixels,new float[25],2).sample(2,2)),"Empty depth rejected");
    for(int y=0;y<5;y++)for(int x=0;x<5;x++)depth[y*5+x]=1000+x*100;
    Frames original=new Frames(5,5,pixels,depth,3),mirrored=original.mirrored();
    check(mirrored.depthMm[0]==1400,"Mirrored image and depth coordinates agree");check(original.depthMm[0]==1000,"Mirror does not modify original frame");
    check(new AstraDepth.Frame().size()==80,"64-bit OpenNI frame ABI size");
    if(args.length>0){
      interface Fake extends Library {void fakeSetFormat(int format);int fakeReleasedCount();int fakeClosedCount();int fakeLastSensor();}
      Fake fake=Native.load(args[0],Fake.class);
      for(int format:new int[]{100,101}){
        fake.fakeSetFormat(format);AstraDepth camera=new AstraDepth();camera.start(args[0]);long deadline=System.nanoTime()+3_000_000_000L;
        while(camera.latest==null&&System.nanoTime()<deadline)Thread.sleep(10);
        check(fake.fakeLastSensor()==3,"OpenNI SDK depth sensor must be 3, not color sensor 2");
        check(camera.latest!=null,"Synthetic native depth stream: "+camera.status);
        check(Math.abs(camera.latest.sample(1,1)-1250)<.01,"Depth units and padded row stride for format "+format);
        camera.stop();
      }
      check(fake.fakeReleasedCount()>=2,"Native frames released");check(fake.fakeClosedCount()==2,"Native devices closed");
    }
    System.out.println("PASS: "+checks+" homography, depth-sampling and native ABI checks.");
  }
}
