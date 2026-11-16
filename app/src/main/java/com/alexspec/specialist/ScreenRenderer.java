package com.alexspec.specialist;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the Specialist's picture with the video chip of the phone, as in DOS 286
 * (assets/screen.frag): sharp pixels of one width, a colour or black-and-white TV, a green or
 * amber monitor. It only changes how a finished frame looks — no waiting for the next one, so no
 * delay in the controls. Sits under MainView (which leaves the picture's place transparent) and
 * draws into the rectangle MainView gives it; the pause magnifier is a zoom and a centre passed
 * to the shader.
 */
final class ScreenRenderer implements GLSurfaceView.Renderer {
    static final String[] MODE_NAMES = {"Чёткие пиксели", "Цветной телевизор", "Чёрно-белый телевизор",
            "Зелёный монитор", "Янтарный монитор"};
    /** the shader's mode for each of MODE_NAMES (its VGA mode does not fit the Specialist) */
    private static final int[] SHADER_MODE = {0, 2, 3, 4, 5};

    private final Context context;
    private final EmuThread emu;
    private final int background;

    // set from the UI thread
    private volatile float rectL, rectT, rectW, rectH;
    /** index in MODE_NAMES */
    volatile int mode;
    volatile float zoom = 1, centerX = 0.5f, centerY = 0.5f;

    private int program, texture, texW, texH;
    private int aPos, uTex, uTexSize, uOutSize, uMode, uZoom, uCenter;
    private FloatBuffer quad;
    private IntBuffer pixels;
    private int surfaceW, surfaceH;
    /** the shader did not build: GameView draws the picture itself */
    volatile boolean failed;

    ScreenRenderer(Context context, EmuThread emu, int background) {
        this.context = context;
        this.emu = emu;
        this.background = background;
    }

    /** where the picture is, in the view's pixels (the GL surface covers the same view) */
    void setRect(float l, float t, float w, float h) {
        rectL = l;
        rectT = t;
        rectW = w;
        rectH = h;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        String vs = "attribute vec2 a_pos;\nvarying vec2 v_uv;\n"
                + "void main() { v_uv = vec2(a_pos.x * 0.5 + 0.5, 0.5 - a_pos.y * 0.5); gl_Position = vec4(a_pos, 0.0, 1.0); }\n";
        program = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, asset("screen.frag")));
        if (program == 0) {
            failed = true;
            return;
        }
        aPos = GLES20.glGetAttribLocation(program, "a_pos");
        uTex = GLES20.glGetUniformLocation(program, "u_tex");
        uTexSize = GLES20.glGetUniformLocation(program, "u_texSize");
        uOutSize = GLES20.glGetUniformLocation(program, "u_outSize");
        uMode = GLES20.glGetUniformLocation(program, "u_mode");
        uZoom = GLES20.glGetUniformLocation(program, "u_zoom");
        uCenter = GLES20.glGetUniformLocation(program, "u_center");
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texW = texH = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        surfaceW = w;
        surfaceH = h;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glViewport(0, 0, surfaceW, surfaceH);
        GLES20.glClearColor(((background >> 16) & 0xFF) / 255f, ((background >> 8) & 0xFF) / 255f, (background & 0xFF) / 255f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (program == 0 || rectW < 1 || rectH < 1) return;

        int w = com.alexspec.specialist.core.Specialist.WIDTH, h = com.alexspec.specialist.core.Specialist.HEIGHT;
        synchronized (emu.frame) {
            if (pixels == null) pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            pixels.position(0);
            pixels.put(emu.frame, 0, w * h);
            pixels.position(0);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        // ARGB ints go up as bytes B, G, R, A (little-endian); the shader swaps them back
        if (w != texW || h != texH) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            texW = w;
            texH = h;
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        }

        int vx = Math.round(rectL), vw = Math.round(rectW), vh = Math.round(rectH);
        int vy = surfaceH - Math.round(rectT) - vh; // GL counts from the bottom
        GLES20.glViewport(vx, vy, vw, vh);
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform2f(uTexSize, w, h);
        GLES20.glUniform2f(uOutSize, vw, vh);
        GLES20.glUniform1f(uMode, SHADER_MODE[Math.max(0, Math.min(SHADER_MODE.length - 1, mode))]);
        GLES20.glUniform1f(uZoom, zoom);
        GLES20.glUniform2f(uCenter, centerX, centerY);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private String asset(String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) out.write(b, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) android.util.Log.e("Specialist", "shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            android.util.Log.e("Specialist", "program: " + GLES20.glGetProgramInfoLog(p));
            return 0;
        }
        return p;
    }
}
