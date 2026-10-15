import java.util.*;
import org.opencv.core.*;
import org.opencv.calib3d.Calib3d;

/** All coordinates are normalized to [0,1]. Fit uses ALL clicked correspondences. */
public final class Mapping {
  public final double[] h;
  public Mapping(double[] values) {
    if (values.length != 9) throw new IllegalArgumentException("A homography needs nine coefficients.");
    h = values.clone();
    for (double x : h) if (!Double.isFinite(x)) throw new IllegalArgumentException("Non-finite homography.");
  }
  public static Mapping fit(List<double[]> source, List<double[]> target) {
    if (source.size() < 9 || source.size() != target.size()) throw new IllegalArgumentException("Click at least 9 matching points first.");
    Point[] a = new Point[source.size()], b = new Point[target.size()];
    double mx=0,my=0;
    for (int i=0;i<a.length;i++) { a[i]=new Point(source.get(i)[0],source.get(i)[1]); b[i]=new Point(target.get(i)[0],target.get(i)[1]); mx+=a[i].x;my+=a[i].y; }
    mx/=a.length;my/=a.length;
    double xx=0,yy=0,xy=0;
    for (Point pt:a) { double x=pt.x-mx,y=pt.y-my;xx+=x*x;yy+=y*y;xy+=x*y; }
    if (xx*yy-xy*xy < 1e-6) throw new IllegalArgumentException("Points must cover an area, not a line.");
    MatOfPoint2f aa=new MatOfPoint2f(a),bb=new MatOfPoint2f(b); Mat matrix=null;
    try {
      matrix=Calib3d.findHomography(aa,bb,0);
      if (matrix.empty() || Math.abs(Core.determinant(matrix))<1e-9) throw new IllegalArgumentException("Degenerate points. Reset and click the numbered targets in order.");
      double[] h=new double[9];matrix.get(0,0,h);
      Mapping result=new Mapping(h);
      if (result.rms(source,target,1000,1000)>80) throw new IllegalArgumentException("Large fitting error. Check point order and undo incorrect clicks.");
      return result;
    } finally { aa.release();bb.release();if(matrix!=null)matrix.release(); }
  }
  /** Flip projector X after calibration; raw frames and baseline stay in the same space. */
  public Mapping flippedHorizontally(){return new Mapping(new double[]{h[6]-h[0],h[7]-h[1],h[8]-h[2],h[3],h[4],h[5],h[6],h[7],h[8]});}
  public double[] apply(double x,double y) {
    double d=h[6]*x+h[7]*y+h[8];
    if (Math.abs(d)<1e-10) return new double[]{Double.NaN,Double.NaN};
    return new double[]{(h[0]*x+h[1]*y+h[2])/d,(h[3]*x+h[4]*y+h[5])/d};
  }
  public double rms(List<double[]> source,List<double[]> target,int width,int height) {
    double sum=0;
    for(int i=0;i<source.size();i++){double[] p=apply(source.get(i)[0],source.get(i)[1]);double dx=(p[0]-target.get(i)[0])*width,dy=(p[1]-target.get(i)[1])*height;sum+=dx*dx+dy*dy;}
    return Math.sqrt(sum/source.size());
  }
  public static List<double[]> grid(int count) {
    if(count<3||count>5)throw new IllegalArgumentException("Use a 3x3, 4x4 or 5x5 grid.");
    List<double[]> points=new ArrayList<>();
    for(int y=0;y<count;y++)for(int x=0;x<count;x++)points.add(new double[]{.1+.8*x/(count-1),.1+.8*y/(count-1)});
    return points;
  }
}
