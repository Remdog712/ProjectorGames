/* Synthetic OpenNI ABI fixture only; not a camera driver. */
#include <stdint.h>
#include <unistd.h>
typedef struct {int pixelFormat,resolutionX,resolutionY,fps;} Mode;
typedef struct {int dataSize;void* data;int sensorType;uint64_t timestamp;int frameIndex,width,height;Mode videoMode;int croppingEnabled,cropOriginX,cropOriginY,stride;} Frame;
/* Independent SDK contract: OpenNI OniCEnums.h ONI_SENSOR_COLOR=2, ONI_SENSOR_DEPTH=3. */
static int format=100,released=0,closed=0,lastSensor=0;static uint16_t data[8];static Frame frame;
int fakeLastSensor(void){return lastSensor;}
void fakeSetFormat(int f){format=f;}
int fakeReleasedCount(void){return released;}int fakeClosedCount(void){return closed;}
int oniInitialize(int version){return version==2002?0:1;}void oniShutdown(void){}const char* oniGetExtendedError(void){return "Synthetic ABI test";}
int oniDeviceOpen(const char* uri,void** out){*out=(void*)1;return 0;}int oniDeviceClose(void* p){closed++;return 0;}
int oniDeviceCreateStream(void* device,int sensor,void** out){lastSensor=sensor;if(sensor!=3){*out=0;return 3;}*out=(void*)2;return 0;}
int oniStreamStart(void* p){return 0;}void oniStreamStop(void* p){}void oniStreamDestroy(void* p){}
int oniWaitForAnyStream(void** streams,int count,int* ready,int timeout){usleep(10000);*ready=0;return 0;}
int oniStreamReadFrame(void* stream,Frame** out){
 int mul=format==101?10:1;data[0]=1000*mul;data[1]=1100*mul;data[2]=1200*mul;data[3]=0;data[4]=1300*mul;data[5]=1400*mul;data[6]=1500*mul;data[7]=0;
 frame=(Frame){sizeof(data),data,3,1234,1,3,2,{format,3,2,30},0,0,0,8};*out=&frame;return 0;
}
void oniFrameRelease(Frame* p){released++;}
