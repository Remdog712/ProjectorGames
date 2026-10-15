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
      if(app.pixelDensity!=1)throw new AssertionError("Calibration window must use pixelDensity(1)");
      Robot robot=new Robot();robot.setAutoDelay(60);
      Component canvas=(Component)app.getSurface().getNative();
      Runnable front=()->{Window w=SwingUtilities.getWindowAncestor(canvas);if(w!=null)w.toFront();};
      java.util.function.BiConsumer<Integer,Integer> click=(x,y)->{try{EventQueue.invokeAndWait(front);Thread.sleep(60);Point o=canvas.getLocationOnScreen();robot.mouseMove(o.x+x,o.y+y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);}catch(Exception e){throw new RuntimeException(e);}};
      click.accept(550,67);waitFor(()->!app.tool.demo,"Could not leave demo");
      click.accept(1120,752);if(app.tool.step!=0)throw new AssertionError("No-camera step allowed advancing");
      click.accept(550,67);waitFor(()->app.tool.demo,"Could not enter demo");
      click.accept(1120,752);waitFor(()->app.tool.step==1,"Demo camera step failed");
      click.accept(160,67);waitFor(()->!app.tool.colorView,"Depth preview toggle failed");
      click.accept(1120,752);waitFor(()->app.tool.step==2&&app.tool.colorView,"Preview step must return to Color");
      click.accept(1120,752);waitFor(()->app.tool.step==3&&app.tool.calibrating&&app.tool.displayFrame!=null,"Target step failed");
      click.accept(1120,752);if(app.tool.step!=3||app.tool.mapping!=null)throw new AssertionError("Incomplete targets allowed advancing");
      for(double[] uv:Mapping.grid(3)){click.accept(Math.round(app.tool.imageX+(float)uv[0]*app.tool.imageW),Math.round(app.tool.imageY+(float)uv[1]*app.tool.imageH));}
      waitFor(()->app.tool.clicks.size()==9,"Nine camera clicks not recorded");
      click.accept(70,67);waitFor(()->app.tool.clicks.size()==8,"Undo point failed");
      click.accept(Math.round(app.tool.imageX+.9f*app.tool.imageW),Math.round(app.tool.imageY+.9f*app.tool.imageH));
      click.accept(1120,752);waitFor(()->app.tool.step==4&&app.tool.mapping!=null,"Fit failed: "+app.tool.message);
      double[] mapped=app.tool.mapping.apply(.37,.62);if(Math.hypot(mapped[0]-.37,mapped[1]-.62)>.003)throw new AssertionError("UI homography not identity");
      click.accept(1120,752);if(app.tool.step!=4)throw new AssertionError("Verification was skipped");
      click.accept(990,752);waitFor(()->app.tool.step==3&&app.tool.calibrating,"Back to points failed");
      click.accept(1120,752);waitFor(()->app.tool.step==4,"Refit failed");
      click.accept(Math.round(app.tool.imageX+.4f*app.tool.imageW),Math.round(app.tool.imageY+.6f*app.tool.imageH));
      waitFor(()->app.tool.probeChecked&&app.tool.probe!=null,"Mapping probe failed");
      click.accept(1120,752);waitFor(()->app.tool.step==5,"Color test step failed");
      click.accept(70,67);waitFor(()->app.tool.testColor==1,"Projection color control failed");
      Component projection=(Component)app.tool.projector.getSurface().getNative();
      EventQueue.invokeAndWait(()->SwingUtilities.getWindowAncestor(projection).toFront());Thread.sleep(180);
      Point projectionOrigin=projection.getLocationOnScreen();
      java.awt.image.BufferedImage colorTest=robot.createScreenCapture(new Rectangle(projectionOrigin.x,projectionOrigin.y,app.tool.projector.width,app.tool.projector.height));
      if((colorTest.getRGB(colorTest.getWidth()/4,colorTest.getHeight()/4)&0xffffff)!=0xff3333)throw new AssertionError("Red color test was not projected");
      ImageIO.write(colorTest,"png",sketch.resolve("projector-color.png").toFile());
      if(!Files.exists(sketch.resolve("data/calibration.json")))throw new AssertionError("Calibration not saved");
      click.accept(1120,752);waitFor(()->app.tool.step==6&&!app.tool.colorView&&app.tool.displayFrame!=null,"Surface step failed");
      click.accept(1120,752);if(app.tool.step!=6)throw new AssertionError("Missing surface allowed advancing");
      int x=Math.round(app.tool.imageX+.4f*app.tool.imageW),y=Math.round(app.tool.imageY+.6f*app.tool.imageH);
      click.accept(x,y);waitFor(()->Float.isFinite(app.tool.lowMm),"Surface capture failed");
      if(Math.abs(app.tool.lowMm-2000)>2||app.tool.surfaceFrame==null)throw new AssertionError("Invalid low bound/background");
      click.accept(1120,752);waitFor(()->app.tool.step==7,"High point step failed");
      click.accept(1120,752);if(app.tool.step!=7)throw new AssertionError("Missing high allowed advancing");
      click.accept(70,67);waitFor(()->app.tool.frozen,"Delayed high capture failed");
      click.accept(Math.round(app.tool.imageX+.1f*app.tool.imageW),Math.round(app.tool.imageY+.1f*app.tool.imageH));
      waitFor(()->Float.isFinite(app.tool.highMm),"High point click failed");
      click.accept(1120,752);waitFor(()->app.tool.step==8&&!app.tool.frozen,"Depth alignment step failed");
      click.accept(1120,752);if(app.tool.step!=8)throw new AssertionError("Missing depth mapping allowed game");
      click.accept(70,67);waitFor(()->app.tool.referenceView,"Color reference failed");
      click.accept(Math.round(app.tool.imageX+.5f*app.tool.imageW),Math.round(app.tool.imageY+.5f*app.tool.imageH));
      if(app.tool.depthClicks.size()!=1)throw new AssertionError("Color selection did not accept a point");
      click.accept(230,67);waitFor(()->app.tool.depthClicks.isEmpty(),"Undo color-assisted point failed");
      click.accept(70,67);waitFor(()->!app.tool.referenceView&&app.tool.displayFrame!=null,"Return to Depth failed");
      for(double[] uv:Mapping.grid(3))click.accept(Math.round(app.tool.imageX+(float)uv[0]*app.tool.imageW),Math.round(app.tool.imageY+(float)uv[1]*app.tool.imageH));
      click.accept(400,67);waitFor(()->app.tool.referenceView&&app.tool.depthClicks.size()==9,"Reuse color grid shortcut failed");
      click.accept(1120,752);waitFor(()->app.tool.step==9&&app.tool.gameRunning,"Bubble game failed to start");
      if(!Files.exists(sketch.resolve("data/interaction.json")))throw new AssertionError("Interaction settings not saved");
      int score=app.tool.game.state().score;
      click.accept(Math.round(app.tool.imageX+.5f*app.tool.imageW),Math.round(app.tool.imageY+.5f*app.tool.imageH));
      if(app.tool.game.state().score!=score)throw new AssertionError("Mouse popped bubbles");
      click.accept(70,67);waitFor(()->!app.tool.gameRunning,"Pause failed");
      click.accept(70,67);waitFor(()->app.tool.gameRunning,"Play failed");
      app.getSurface().setSize(930,640);waitFor(()->app.width==930,"Resize failed");
      float scale=app.tool.uiScale();click.accept(Math.round(70*scale),Math.round(67*scale));waitFor(()->!app.tool.gameRunning,"Scaled pause failed");
      EventQueue.invokeAndWait(front);Thread.sleep(100);
      Point o=canvas.getLocationOnScreen();ImageIO.write(robot.createScreenCapture(new Rectangle(o.x,o.y,app.width,app.height)),"png",sketch.resolve("processing-ui.png").toFile());
      app.tool.openProjector();
      waitFor(()->app.tool.projector.frameCount>5,"Projector window did not render");
      if(app.tool.projector.pixelDensity!=1)throw new AssertionError("Projector must use pixelDensity(1)");
      EventQueue.invokeAndWait(()->SwingUtilities.getWindowAncestor(projection).toFront());Thread.sleep(180);
      projectionOrigin=projection.getLocationOnScreen();
      java.awt.image.BufferedImage gameImage=robot.createScreenCapture(new Rectangle(projectionOrigin.x,projectionOrigin.y,app.tool.projector.width,app.tool.projector.height));
      int bubblePixels=0;for(int yy=0;yy<gameImage.getHeight();yy++)for(int xx=0;xx<gameImage.getWidth();xx++)if((gameImage.getRGB(xx,yy)&0xffffff)==0x174d79)bubblePixels++;
      if(bubblePixels<1000)throw new AssertionError("Bubble game did not render visible targets");
      ImageIO.write(gameImage,"png",sketch.resolve("projector-bubbles.png").toFile());
      float controlScale=app.tool.uiScale();
      Frames baseline=app.tool.surfaceFrame;Mapping fitted=app.tool.depthMapping;
      click.accept(Math.round(1020*controlScale),Math.round(67*controlScale));waitFor(()->!app.tool.flipGame,"Flip X toggle failed");
      if(app.tool.surfaceFrame!=baseline||app.tool.depthMapping!=fitted)throw new AssertionError("Game flip altered calibration/baseline");
      click.accept(Math.round(1020*controlScale),Math.round(67*controlScale));waitFor(()->app.tool.flipGame,"Flip X restore failed");
      click.accept(Math.round(860*controlScale),Math.round(67*controlScale));waitFor(()->app.tool.gameMode==1,"Halo selector failed");
      float[] body=baseline.depthMm.clone();for(int yy=100;yy<350;yy++)for(int xx=150;xx<350;xx++)body[yy*baseline.width+xx]-=80;
      for(int seq=10001;seq<=10002;seq++)app.tool.game.update(new Frames(baseline.width,baseline.height,new int[body.length],body,seq),baseline,fitted.flippedHorizontally(),app.tool.lowMm-app.tool.highMm,20,12,false);
      if(app.tool.game.state().outline.isEmpty())throw new AssertionError("Halo silhouette missing");
      app.tool.demoCache=new Frames(baseline.width,baseline.height,new int[body.length],body,32);app.tool.gameRunning=true;
      EventQueue.invokeAndWait(()->SwingUtilities.getWindowAncestor(projection).toFront());Thread.sleep(180);
      java.awt.image.BufferedImage haloImage=robot.createScreenCapture(new Rectangle(projectionOrigin.x,projectionOrigin.y,app.tool.projector.width,app.tool.projector.height));
      int haloPixels=0;for(int yy=70;yy<haloImage.getHeight();yy++)for(int xx=0;xx<haloImage.getWidth();xx++){int rgb=haloImage.getRGB(xx,yy);if(((rgb>>16)&255)>20||((rgb>>8)&255)>20||(rgb&255)>20)haloPixels++;}
      if(haloPixels<1500)throw new AssertionError("Halo did not render a colored outline");
      ImageIO.write(haloImage,"png",sketch.resolve("projector-halo.png").toFile());
      app.tool.gameRunning=false;Thread.sleep(1600);
      java.awt.image.BufferedImage faded=robot.createScreenCapture(new Rectangle(projectionOrigin.x,projectionOrigin.y,app.tool.projector.width,app.tool.projector.height));
      long originalLight=0,fadedLight=0;for(int yy=80;yy<haloImage.getHeight();yy++)for(int xx=0;xx<haloImage.getWidth();xx++){int a=haloImage.getRGB(xx,yy),b=faded.getRGB(xx,yy);originalLight+=(a&255)+((a>>8)&255)+((a>>16)&255);fadedLight+=(b&255)+((b>>8)&255)+((b>>16)&255);}
      if(fadedLight>=originalLight*.6)throw new AssertionError("Halo trails did not fade");
      click.accept(Math.round(860*controlScale),Math.round(67*controlScale));waitFor(()->app.tool.gameMode==2,"Light pads selector failed");
      click.accept(Math.round(860*controlScale),Math.round(67*controlScale));waitFor(()->app.tool.gameMode==3,"Aurora selector failed");
      EventQueue.invokeAndWait(()->SwingUtilities.getWindowAncestor(projection).toFront());Thread.sleep(150);
      ImageIO.write(robot.createScreenCapture(new Rectangle(projectionOrigin.x,projectionOrigin.y,app.tool.projector.width,app.tool.projector.height)),"png",sketch.resolve("projector-aurora.png").toFile());
      app.tool.projector.exit();
      if(args.length>1){
        Mapping saved=app.tool.mapping;
        app.tool.runtime=args[1];app.tool.demo=false;app.tool.colorView=false;app.tool.cameraEnabled=true;
        app.tool.switchCamera();
        waitFor(()->app.tool.depth.latest!=null&&!app.tool.connecting,"Depth-only switch failed: "+app.tool.message+" / "+app.tool.depth.status);
        if(!app.tool.color.status.equals("Color disconnected."))throw new AssertionError("Color was not stopped before depth");
        app.tool.setView(true);
        waitFor(()->app.tool.depth.latest==null&&!app.tool.connecting,"Switch back did not close depth");
        if(app.tool.mapping!=saved)throw new AssertionError("Switching streams erased calibration");
        System.out.println("PASS: native fixture depth-only capture, color pause, depth shutdown on Color selection and preserved calibration.");
      }
      System.out.println("PASS: ten guided steps, color test, surface/low, timed high capture, independent depth alignment, bubble controls, mouse exclusion, guards, resize and projector rendering, horizontal flip, Halo selection and silhouette rendering, fading trails, light pads, Aurora and color-grid reuse.");
    } catch(Throwable error){error.printStackTrace();result=1;} finally {if(app.tool!=null)app.tool.close();System.exit(result);}
  }
}
