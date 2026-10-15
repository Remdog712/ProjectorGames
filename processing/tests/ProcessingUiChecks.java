import processing.core.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class ProcessingUiChecks {
  static void waitFor(java.util.function.BooleanSupplier condition,String message)throws Exception{long end=System.currentTimeMillis()+8000;while(!condition.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(30);if(!condition.getAsBoolean())throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    System.setProperty("projectorgames.demo","true");
    DesktopLauncher app=new DesktopLauncher();
    Path sketch=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(sketch);
    PApplet.runSketch(new String[]{"--sketch-path="+sketch,"Processing UI check"},app);
    int result=0;
    try{
      waitFor(()->app.tool!=null&&app.tool.ready&&app.tool.displayFrame!=null,"Processing did not start: "+(app.tool==null?"no tool":app.tool.message));
      Robot robot=new Robot();robot.setAutoDelay(60);
      Component canvas=(Component)app.getSurface().getNative();
      Runnable front=()->{Window w=SwingUtilities.getWindowAncestor(canvas);if(w!=null)w.toFront();};
      java.util.function.BiConsumer<Integer,Integer> click=(x,y)->{try{EventQueue.invokeAndWait(front);Thread.sleep(60);Point o=canvas.getLocationOnScreen();robot.mouseMove(o.x+x,o.y+y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);}catch(Exception e){throw new RuntimeException(e);}};
      click.accept(180,113);waitFor(()->app.tool.calibrating,"Calibrate button failed");
      for(double[] uv:Mapping.grid(3)){click.accept(Math.round(app.tool.imageX+(float)uv[0]*app.tool.imageW),Math.round(app.tool.imageY+(float)uv[1]*app.tool.imageH));}
      waitFor(()->app.tool.clicks.size()==9,"Nine camera clicks not recorded");
      click.accept(345,113);waitFor(()->app.tool.mapping!=null,"Fit failed: "+app.tool.message);
      double[] mapped=app.tool.mapping.apply(.37,.62);if(Math.hypot(mapped[0]-.37,mapped[1]-.62)>.003)throw new AssertionError("UI homography not identity");
      click.accept(415,113);waitFor(()->Files.exists(sketch.resolve("data/calibration.json")),"Save failed");
      click.accept(600,113);waitFor(()->!app.tool.colorView&&app.tool.displayFrame!=null,"Depth tab failed");
      int x=Math.round(app.tool.imageX+.4f*app.tool.imageW),y=Math.round(app.tool.imageY+.6f*app.tool.imageH);
      click.accept(x,y);waitFor(()->Float.isFinite(app.tool.measurement),"Depth click failed");
      if(Math.abs(app.tool.measurement-2000)>2)throw new AssertionError("Unexpected measured depth: "+app.tool.measurement);
      click.accept(70,113);waitFor(()->app.tool.grid==4&&app.tool.clicks.isEmpty(),"Grid change must reset active calibration");
      click.accept(490,113);waitFor(()->app.tool.grid==3&&app.tool.mapping!=null&&app.tool.clicks.size()==9,"Saved mapping did not reload");
      app.getSurface().setSize(930,640);waitFor(()->app.width==930,"Resize failed");
      float scale=app.tool.uiScale();click.accept(Math.round(600*scale),Math.round(113*scale));
      waitFor(()->!app.tool.colorView&&app.tool.displayFrame!=null,"Scaled Depth mode failed");
      click.accept(Math.round((app.tool.imageX+.4f*app.tool.imageW)*scale),Math.round((app.tool.imageY+.6f*app.tool.imageH)*scale));
      waitFor(()->Float.isFinite(app.tool.measurement),"Scaled measurement failed");
      if(Math.abs(app.tool.measurement-2000)>3)throw new AssertionError("Scaled click did not map to depth pixel");
      EventQueue.invokeAndWait(front);Thread.sleep(100);
      Point o=canvas.getLocationOnScreen();ImageIO.write(robot.createScreenCapture(new Rectangle(o.x,o.y,app.width,app.height)),"png",sketch.resolve("processing-ui.png").toFile());
      System.out.println("PASS: real Processing window, nine Robot clicks, fit, save, depth toggle and measurement, grid reset, reload.");
    } catch(Throwable error){error.printStackTrace();result=1;} finally {if(app.tool!=null)app.tool.close();System.exit(result);}
  }
}
