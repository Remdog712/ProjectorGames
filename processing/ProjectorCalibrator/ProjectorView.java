import processing.core.*;
import java.util.*;

/** A separate native Processing window; Escape hides it without quitting the control window. */
public final class ProjectorView extends PApplet {
  final Calibrator owner;final int screen;
  ProjectorView(Calibrator tool,int display){owner=tool;screen=display;}
  public void settings(){if(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length>1)fullScreen(screen);else size(1024,768);pixelDensity(1);}
  public void setup(){surface.setTitle("Projector calibration grid");frameRate(30);}
  public void draw(){
    background(0);List<double[]> points=Mapping.grid(owner.grid);
    if(owner.calibrating||owner.mapping==null){
      for(int i=0;i<points.size();i++){
        double[] pt=points.get(i);float x=(float)pt[0]*width,y=(float)pt[1]*height;
        stroke(i==owner.selected?0xff62ffe0:0xffeeeeee);strokeWeight(i==owner.selected?4:2);noFill();ellipse(x,y,40,40);line(x-28,y,x+28,y);line(x,y-28,x,y+28);
        noStroke();fill(255);textAlign(CENTER,BOTTOM);textSize(22);text(i+1,x,y-32);
      }
    }
    double[] probe=owner.probe;
    if(probe!=null&&Double.isFinite(probe[0])&&Double.isFinite(probe[1])){float x=(float)probe[0]*width,y=(float)probe[1]*height;stroke(0xff62ffe0);strokeWeight(3);noFill();ellipse(x,y,45,45);line(x-32,y,x+32,y);line(x,y-32,x,y+32);}
  }
  public void keyPressed(){if(key==ESC){key=0;exit();}}
  public void exit(){surface.setVisible(false);noLoop();}
}
