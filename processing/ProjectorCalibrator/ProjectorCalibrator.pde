// Processing 4, Java mode. Run scripts/setup_processing.py once before opening.
Calibrator tool;
void settings() { DesktopLauncher.configureWindow(this); }
void setup() { surface.setTitle("ProjectorGames — manual calibration"); tool = new Calibrator(this); }
void draw() { tool.draw(); }
void mousePressed() { tool.mousePressed(mouseX, mouseY); }
void keyPressed() { tool.keyPressed(key, keyCode); }
