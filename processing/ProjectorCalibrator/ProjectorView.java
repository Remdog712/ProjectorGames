import processing.core.*;
import java.util.*;

/** A separate native Processing window; Escape hides it without quitting the control window. */
public final class ProjectorView extends PApplet {
  PGraphics haloTrail;int trailReset=-1;long trailTime;
  final Calibrator owner;final int screen;
  ProjectorView(Calibrator tool,int display){owner=tool;screen=display;}
  public void settings(){if(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length>1)fullScreen(screen);else size(1024,768);pixelDensity(1);}
  public void setup(){surface.setTitle("Projector calibration grid");frameRate(30);}
  public void draw(){
    background(0);
    if(owner.step==5){drawColorTest();return;}
    if(owner.step==9){if(owner.gameMode==1)drawHalo();else if(owner.gameMode==2)drawPads();else if(owner.gameMode==3)drawAurora();else drawBubbles();return;}
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
    if(haloTrail==null||haloTrail.width!=width||haloTrail.height!=height||trailReset!=owner.visualReset){haloTrail=createGraphics(width,height);haloTrail.beginDraw();haloTrail.background(0);haloTrail.endDraw();trailReset=owner.visualReset;trailTime=System.currentTimeMillis();}
    long now=System.currentTimeMillis();float dt=Math.max(.001f,(now-trailTime)/1000f);trailTime=now;
    haloTrail.beginDraw();haloTrail.noStroke();haloTrail.fill(0,Math.max(1,255*(1-(float)Math.pow(.01,dt/owner.trailSeconds))));haloTrail.rect(0,0,width,height);
    if(owner.gameRunning){float time=millis()/12000f;for(int layer=0;layer<3;layer++){float diameter=layer==0?48:layer==1?28:12;int alpha=layer==0?28:layer==1?85:230;
      for(double[] uv:owner.game.state().outline){int rgb=java.awt.Color.HSBtoRGB((time+(float)uv[1]*.4f)%1f,.85f,1f);haloTrail.fill((alpha<<24)|(rgb&0xffffff));haloTrail.ellipse((float)uv[0]*width,(float)uv[1]*height,diameter,diameter);}
    }}haloTrail.endDraw();image(haloTrail,0,0);
    fill(255);textAlign(LEFT,TOP);textSize(24);text("HALO TRAILS",25,20);paused();
  }
  void drawPads(){
    background(0xff091329);int active=owner.pads.target();int[] colors={0xffff637e,0xff6ee7ff,0xffffdb70,0xffa991ff};
    for(int i=0;i<4;i++){double[] uv=TouchPads.CENTERS[i];float x=(float)uv[0]*width,y=(float)uv[1]*height,r=height*.12f;
      stroke(colors[i]);strokeWeight(i==active?6:2);fill(i==active?colors[i]:0xff17233a);ellipse(x,y,r*2,r*2);fill(i==active?0xff101322:colors[i]);textSize(30);textAlign(CENTER,CENTER);text(i==active?"TOUCH":Integer.toString(i+1),x,y);
    }fill(255);textAlign(LEFT,TOP);textSize(25);text("LIGHT PADS   Score: "+owner.pads.score(),25,20);paused();
  }
  void drawAurora(){
    background(0xff020812);float t=owner.gameRunning?millis()/1000f:0,energy=owner.gameRunning?owner.audio.level:0;
    noFill();for(int glow=0;glow<2;glow++)for(int band=0;band<18;band++){int rgb=java.awt.Color.HSBtoRGB((band/24f+t*.015f)%1,.8f,1);stroke(((glow==0?25:185)<<24)|(rgb&0xffffff));strokeWeight(glow==0?10+energy*22:2+energy*7);beginShape();
      for(int x=0;x<=width;x+=12){float u=x/(float)width;float y=height*(.15f+band*.035f)+PApplet.sin(u*7+t*(.4f+energy)+band*.22f)*height*(.05f+energy*.13f);vertex(x,y);}endShape();}
    noStroke();for(int i=0;i<65;i++){float u=(i*.618f+t*(.018f+energy*.06f))%1f,y=height*(.15f+(i%18)*.035f)+PApplet.sin(u*7+t*.4f+(i%18)*.22f)*height*(.05f+energy*.13f);fill(130,220,255,100+energy*150);float r=2+energy*5;ellipse(u*width,y,r,r);}
    noFill();if(owner.gameRunning)for(double[] uv:owner.game.state().contacts){float x=(float)uv[0]*width,y=(float)uv[1]*height;for(int ring=0;ring<5;ring++){stroke(100,210,255,180-ring*30);strokeWeight(2);float r=40+ring*30+energy*90+PApplet.sin(t*2)*15;ellipse(x,y,r,r);}}
    fill(255);textAlign(LEFT,TOP);textSize(24);text("AURORA   "+(owner.audio.enabled()?"MIC LIVE":"Enable Mic for music response"),25,20);paused();
  }
  void paused(){if(!owner.gameRunning){fill(255);textAlign(CENTER,CENTER);textSize(25);text("PAUSED",width/2f,height/2f);}}
  public void keyPressed(){if(key==ESC){key=0;exit();}}
  public void exit(){surface.setVisible(false);noLoop();}
}
