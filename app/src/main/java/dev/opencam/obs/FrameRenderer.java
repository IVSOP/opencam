package dev.opencam.obs;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Applies the camera buffer transform to pixels, identically for OBS and the phone preview. */
final class FrameRenderer implements AutoCloseable {
    private final HandlerThread thread = new HandlerThread("opencam-render");
    private final Handler handler;
    private final int width, height;
    private final Consumer<String> problem;
    private final FloatBuffer vertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    private final FloatBuffer coordinates = floats(0,0, 1,0, 0,1, 1,1);
    private final float[] transform = new float[16];
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface output = EGL14.EGL_NO_SURFACE, previewWindow = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private SurfaceTexture cameraTexture;
    private Surface cameraSurface;
    private int texture, program, positionLocation, uvLocation, transformLocation;
    private volatile boolean closing;

    FrameRenderer(Surface encoder, int width, int height, Consumer<String> problem) throws Exception {
        this.width = width; this.height = height; this.problem = problem;
        thread.start(); handler = new Handler(thread.getLooper());
        FutureTask<Void> init = new FutureTask<>(() -> { initialize(encoder); return null; });
        handler.post(init);
        try { init.get(8, TimeUnit.SECONDS); }
        catch (Exception e) { close(); throw e; }
    }
    Surface input() { return cameraSurface; }
    void stopDrawing() { closing = true; }
    private static FloatBuffer floats(float... values) {
        FloatBuffer b = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(values).position(0); return b;
    }
    private void initialize(Surface encoder) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        require(EGL14.eglInitialize(display, version, 0, version, 1), "Initialize EGL");
        EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
            EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE}, 0, configs,0,1,count,0)
            && count[0] > 0, "Choose recordable EGL configuration");
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        require(context != EGL14.EGL_NO_CONTEXT, "Create EGL context");
        output = window(encoder); current(output);
        int[] names = new int[1]; GLES20.glGenTextures(1,names,0); texture = names[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        int vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec2 position; attribute vec2 uv; uniform mat4 camera; varying vec2 tex;"
            + "void main(){gl_Position=vec4(position,0.,1.);tex=(camera*vec4(uv,0.,1.)).xy;}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;"
            + "uniform samplerExternalOES image; varying vec2 tex; void main(){gl_FragColor=texture2D(image,tex);}");
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment);
        GLES20.glLinkProgram(program); int[] status = new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0);
        require(status[0] != 0, "Link camera shader: " + GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        positionLocation=GLES20.glGetAttribLocation(program,"position"); uvLocation=GLES20.glGetAttribLocation(program,"uv");
        transformLocation=GLES20.glGetUniformLocation(program,"camera");
        cameraTexture = new SurfaceTexture(texture);
        cameraTexture.setDefaultBufferSize(width,height);
        cameraSurface = new Surface(cameraTexture);
        cameraTexture.setOnFrameAvailableListener(ignored -> render(),handler);
    }
    private static int shader(int type, String source) {
        int shader=GLES20.glCreateShader(type); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] status=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0);
        require(status[0]!=0,"Compile camera shader: "+GLES20.glGetShaderInfoLog(shader)); return shader;
    }
    private EGLSurface window(Surface surface) {
        EGLSurface w=EGL14.eglCreateWindowSurface(display,config,surface,new int[]{EGL14.EGL_NONE},0);
        require(w!=EGL14.EGL_NO_SURFACE,"Create EGL window"); return w;
    }
    private void current(EGLSurface surface) { require(EGL14.eglMakeCurrent(display,surface,surface,context),"Bind EGL window"); }
    private static void require(boolean ok,String message) { if(!ok) throw new IllegalStateException(message); }

    void setPreview(Surface surface) {
        if (closing) return;
        handler.post(() -> {
            if (closing) return;
            dropPreview();
            if(surface!=null && surface.isValid()) {
                try { previewWindow=window(surface); }
                catch(RuntimeException ignored) { previewWindow=EGL14.EGL_NO_SURFACE; }
            }
        });
    }
    private void render() {
        if(closing) return;
        try {
            current(output);
            cameraTexture.updateTexImage();
            // Preserve the full landscape buffer, including the producer's origin correction.
            // Do not fill a landscape output by zooming into a portrait-oriented texture.
            cameraTexture.getTransformMatrix(transform);
            FrameGeometry.landscape(transform);
            draw(width,height);
            EGLExt.eglPresentationTimeANDROID(display,output,cameraTexture.getTimestamp());
            require(EGL14.eglSwapBuffers(display,output),"Submit encoded frame");
            if(previewWindow!=EGL14.EGL_NO_SURFACE) {
                try {
                    current(previewWindow);
                    int[] w=new int[1],h=new int[1];
                    require(EGL14.eglQuerySurface(display,previewWindow,EGL14.EGL_WIDTH,w,0),"Preview width");
                    require(EGL14.eglQuerySurface(display,previewWindow,EGL14.EGL_HEIGHT,h,0),"Preview height");
                    draw(w[0],h[0]);
                    if(!EGL14.eglSwapBuffers(display,previewWindow)) dropPreview();
                } catch(RuntimeException ignored) { dropPreview(); }
            }
        } catch(RuntimeException e) { if(!closing) problem.accept("Camera rendering failed: "+e.getMessage()); }
    }
    private void draw(int w,int h) {
        GLES20.glViewport(0,0,w,h); GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
        GLES20.glUniformMatrix4fv(transformLocation,1,false,transform,0);
        GLES20.glEnableVertexAttribArray(positionLocation); GLES20.glEnableVertexAttribArray(uvLocation);
        GLES20.glVertexAttribPointer(positionLocation,2,GLES20.GL_FLOAT,false,0,vertices);
        GLES20.glVertexAttribPointer(uvLocation,2,GLES20.GL_FLOAT,false,0,coordinates);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }
    private void dropPreview() {
        if(previewWindow!=EGL14.EGL_NO_SURFACE) {
            current(output); EGL14.eglDestroySurface(display,previewWindow); previewWindow=EGL14.EGL_NO_SURFACE;
        }
    }
    @Override public void close() {
        closing=true;
        FutureTask<Void> release=new FutureTask<>(() -> {
            if(cameraTexture!=null) cameraTexture.setOnFrameAvailableListener(null);
            if(cameraSurface!=null) cameraSurface.release();
            if(cameraTexture!=null) cameraTexture.release();
            if(display!=EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(previewWindow!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,previewWindow);
                if(output!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,output);
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
                EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
            }
            return null;
        });
        handler.post(release);
        try { release.get(3,TimeUnit.SECONDS); } catch(Exception ignored) {}
        thread.quitSafely();
    }
}
