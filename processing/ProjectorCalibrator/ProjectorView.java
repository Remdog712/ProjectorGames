import processing.core.*;
import java.util.*;

/** A separate native Processing window; Escape hides it without quitting the control window. */
public final class ProjectorView extends PApplet {
  final Calibrator owner;final int screen;
  ProjectorView(Calibrator tool,int display){owner=tool;screen=display;}
  public void settings(){if(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length>1)fullScreen(screen);else size(1024,768);pixelDensity(1);}
  public void setup(){surface.setTitle("Projector calibration grid");frameRate(30);}
  public void draw(){
    background(0);
    if(owner.step==5){drawColorTest();return;}
    if(owner.step==9){if(owner.gameMode==1)drawHalo();else drawBubbles();return;}
    List<double[]> points=Mapping.grid(owner.grid);
    if(owner.step==2||owner.step==8||owner.calibrating||owner.mapping==null){
      for(int i=0;i<points.size();i++){
        double[] pt=points.get(i);float x=(float)pt[0]*width,y=(float)pt[1]*height;
        stroke(i==owner.selected?0xff62ffe0:0xffeeeeee);strokeWeight(i==owner.selected?4:2);noFill();ellipse(x,y,40,40);line(x-28,y,x+28,y);line(x,y-28,x,y+28);
        noStroke();fill(255);textAlign(CENTER,BOTTOM);textSize(22);text(i+1,x,y-32);
      }
    }
    double[] probe=owner.probe;
    if(probe!=null&&Double.isFinite(probe[0])&&Double.isFinite(probe[1])){float x=(float)probe[0]*width,y=(float)probe[1]*height;stroke(0xff62ffe0);strokeWeight(3);noFill();ellipse(x,y,45,45);line(x-32,y,x+32,y);line(x,y-32,x,y+32);}
  }
  void drawColorTest(){
    int[] colors={0xffff3333,0xff33ff66,0xff3377ff,0xffffffff};
    if(owner.testColor==0){for(int i=0;i<4;i++){noStroke();fill(colors[i]);rect(i*width/4f,0,width/4f,height);}}
    else background(colors[owner.testColor-1]);
    noFill();stroke(0xff111111);strokeWeight(8);rect(8,8,width-16,height-16);
    fill(0xff111111);textAlign(CENTER,CENTER);textSize(28);text("PROJECTOR COLOR TEST",width/2f,height/2f);
  }
  void drawBubbles(){
    background(0xff08152a);BubbleGame.State state=owner.game.state();
    for(int i=0;i<state.bubbles.size();i++){BubbleGame.Bubble b=state.bubbles.get(i);float x=(float)b.x*width,y=(float)b.y*height,rx=(float)b.r*height,ry=(float)b.r*height;
      stroke(0xff75f5ff);strokeWeight(3);fill(0xff174d79);ellipse(x,y,rx*2,ry*2);noStroke();fill(0xffc5ffff);ellipse(x-rx*.3f,y-ry*.3f,rx*.35f,ry*.35f);
    }
    for(double[] uv:state.contacts){noFill();stroke(0xffffdb70);strokeWeight(3);ellipse((float)uv[0]*width,(float)uv[1]*height,36,36);}
    fill(255);textSize(28);textAlign(LEFT,TOP);text("Bubbles popped: "+state.score,25,20);
    if(!owner.gameRunning){textAlign(CENTER,CENTER);text("PAUSED",width/2f,height/2f);}
  }
  void drawHalo(){
    background(0);BubbleGame.State state=owner.game.state();noStroke();
    float time=millis()/12000f;
    for(int layer=0;layer<3;layer++){
      float diameter=layer==0?48:layer==1?28:12;int alpha=layer==0?28:layer==1?85:230;
      for(double[] uv:state.outline){int rgb=java.awt.Color.HSBtoRGB((time+(float)uv[1]*.4f)%1f,.85f,1f);fill((alpha<<24)|(rgb&0xffffff));ellipse((float)uv[0]*width,(float)uv[1]*height,diameter,diameter);}
    }
    fill(255);textAlign(LEFT,TOP);textSize(24);text("COLOR HALO",25,20);
    if(!owner.gameRunning){textAlign(CENTER,CENTER);text("PAUSED",width/2f,height/2f);}
  }
  public void keyPressed(){if(key==ESC){key=0;exit();}}
  public void exit(){surface.setVisible(false);noLoop();}
}
