import processing.core.PApplet;
/** Standalone equivalent of the .pde entry point, also used for compile/UI validation. */
public class DesktopLauncher extends PApplet {
  Calibrator tool;
  public void settings(){size(1240,850);}
  public void setup(){surface.setTitle("ProjectorGames — manual calibration");tool=new Calibrator(this);}
  public void draw(){tool.draw();}
  public void mousePressed(){tool.mousePressed(mouseX,mouseY);}
  public void keyPressed(){tool.keyPressed(key,keyCode);}
  public static void main(String[] args){PApplet.main(DesktopLauncher.class.getName());}
}
