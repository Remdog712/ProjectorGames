import java.util.*;
public final class ExperienceChecks {
  static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
  public static void main(String[] args){
    check(AudioInput.rms(new byte[128],128)==0,"Silent microphone input");
    byte[] full={0,(byte)128,(byte)255,127};check(AudioInput.rms(full,full.length)>.99,"Signed little endian RMS");
    byte[] half={0,64,0,(byte)192};check(Math.abs(AudioInput.rms(half,4)-.5)<.001,"Half amplitude input");
    check(AudioInput.rms(half,0)==0,"Empty input");
    TouchPads pads=new TouchPads();BubbleGame game=new BubbleGame();game.reset();
    pads.update(game.state(),1000);check(pads.score()==0,"No mouse/absent contact cannot score");
    List<double[]> contact=new ArrayList<>();contact.add(TouchPads.CENTERS[0]);
    BubbleGame.State touch=new BubbleGame.State(new ArrayList<>(),contact,new ArrayList<>(),0);
    pads.update(touch,1000);check(pads.score()==1&&pads.target()==1,"Touch illuminated pad advances sequence");
    contact.set(0,TouchPads.CENTERS[1]);pads.update(touch,1200);check(pads.score()==1,"Debounce repeated contacts");
    pads.update(touch,1700);check(pads.score()==2,"Next pad after cooldown");pads.reset();check(pads.score()==0&&pads.target()==0,"Restart resets pad game");
    System.out.println("PASS: "+checks+" audio-level and touch-pad checks.");
  }
}
