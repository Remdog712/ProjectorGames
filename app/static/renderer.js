/* GPU point sprites: each ring is one vertex, with a native Canvas fallback. */
window.StageRenderer=class {
 constructor(canvas){
  this.canvas=canvas;this.gl=canvas.getContext('webgl',{alpha:false,antialias:false,powerPreference:'high-performance'});
  if(!this.gl){this.ctx=canvas.getContext('2d');this.label='Canvas 2D fallback';return}
  const gl=this.gl;
  function shader(type,source){const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s}
  this.program=gl.createProgram();
  gl.attachShader(this.program,shader(gl.VERTEX_SHADER,'attribute vec2 p;attribute float size;attribute vec3 color;varying vec3 tint;void main(){gl_Position=vec4(p.x*2.0-1.0,1.0-p.y*2.0,0.,1.);gl_PointSize=size;tint=color;}'));
  gl.attachShader(this.program,shader(gl.FRAGMENT_SHADER,'precision mediump float;varying vec3 tint;void main(){float d=length(gl_PointCoord-vec2(.5))*2.;float a=(1.-smoothstep(.92,1.,d))*smoothstep(.70,.80,d);if(a<.01)discard;gl_FragColor=vec4(tint,a);}'));
  gl.linkProgram(this.program);if(!gl.getProgramParameter(this.program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(this.program));
  gl.useProgram(this.program);this.buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,this.buffer);
  for(const [name,n,offset]of[['p',2,0],['size',1,8],['color',3,12]]){const a=gl.getAttribLocation(this.program,name);gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,n,gl.FLOAT,false,24,offset)}
  gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);this.maxSize=gl.getParameter(gl.ALIASED_POINT_SIZE_RANGE)[1];
  this.label='WebGL GPU-capable renderer';canvas.addEventListener('webglcontextlost',e=>{e.preventDefault();this.lost=true});canvas.addEventListener('webglcontextrestored',()=>location.reload());
 }
 draw(points,color,radius,bubbles=[]){
  const r=this.canvas.getBoundingClientRect(),d=Math.min(devicePixelRatio||1,1.5);const w=Math.round(r.width*d),h=Math.round(r.height*d);
  if(this.canvas.width!==w||this.canvas.height!==h){this.canvas.width=w;this.canvas.height=h}
  const rgb=[1,3,5].map(i=>parseInt(color.slice(i,i+2),16)/255);
  if(this.gl&&!this.lost){const gl=this.gl;gl.viewport(0,0,w,h);gl.clearColor(.012,.027,.047,1);gl.clear(gl.COLOR_BUFFER_BIT);const vertices=[];
   for(const p of bubbles)vertices.push(p.x,p.y,Math.min(this.maxSize,p.r*w*2),.20,.45,.55);
   for(const p of points)vertices.push(p.x,p.y,Math.min(this.maxSize,radius*2*d),...rgb);
   gl.bindBuffer(gl.ARRAY_BUFFER,this.buffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(vertices),gl.DYNAMIC_DRAW);gl.drawArrays(gl.POINTS,0,vertices.length/6);
  }else if(this.ctx){const c=this.ctx;c.fillStyle='#03070c';c.fillRect(0,0,w,h);c.lineWidth=3*d;c.strokeStyle='#345f78';for(const b of bubbles){c.beginPath();c.arc(b.x*w,b.y*h,b.r*w,0,Math.PI*2);c.stroke()}c.strokeStyle=color;for(const p of points){c.beginPath();c.arc(p.x*w,p.y*h,radius*d,0,Math.PI*2);c.stroke()}}
 }
};
