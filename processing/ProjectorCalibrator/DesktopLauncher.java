import processing.core.PApplet;
/** Standalone equivalent of the .pde entry point, also used for compile/UI validation. */
public class DesktopLauncher extends PApplet {
  Calibrator tool;
  public void settings(){configureWindow(this);}
  /** Set dimensions before AWT creates its buffers; never resize during setup. */
  public static void configureWindow(PApplet app){
    java.awt.Rectangle bounds=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    app.size(Math.max(320,Math.min(1240,bounds.width-50)),Math.max(240,Math.min(850,bounds.height-90)));
    app.pixelDensity(1);
  }
  public void setup(){surface.setTitle("ProjectorGames — manual calibration");tool=new Calibrator(this);}
  public void draw(){tool.draw();}
  public void mousePressed(){tool.mousePressed(mouseX,mouseY);}
  public void keyPressed(){tool.keyPressed(key,keyCode);}
  public static void main(String[] args){PApplet.main(DesktopLauncher.class.getName());}
}
