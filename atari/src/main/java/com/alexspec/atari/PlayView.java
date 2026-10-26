package com.alexspec.atari;

import android.content.Context;
import android.graphics.*;
import android.util.SparseArray;
import android.view.*;
import java.util.*;

/** Screen, multi-touch joystick/fire/console and Atari keyboard. GPL-2.0-or-later. */
final class PlayView extends View {
    private final AtariRunner runner;
    private final float dp;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),pixelPaint=new Paint();
    private Bitmap bitmap;
    private final int[] pixels=new int[120000];
    private final RectF screenRect=new RectF(),joy=new RectF(),fire=new RectF();
    private final List<Key> controls=new ArrayList<>();
    private final SparseArray<Touch> touches=new SparseArray<>();
    boolean keyboard,paused,shift,ctrl;
    private int touchButtons,physicalButtons,analogButtons;
    private final int[] physicalKeys=new int[512];
    private static final int JOY=-1,FIRE=0;
    private static final class Key {RectF rect;String label;int code;Key(RectF r,String s,int c){rect=r;label=s;code=c;}}
    private static final class Touch {int code;float x,y;Touch(int c,float x,float y){code=c;this.x=x;this.y=y;}}
    PlayView(Context context,AtariRunner runner){super(context);this.runner=runner;dp=getResources().getDisplayMetrics().density;setFocusableInTouchMode(true);pixelPaint.setFilterBitmap(false);}
    private void layoutControls() {
        controls.clear();float w=getWidth(),h=getHeight();
        float console=36*dp,keyboardHeight=keyboard?Math.min(h*0.47f,250*dp):0;
        boolean landscape=w>h*1.2f;
        float controlHeight=keyboard?keyboardHeight:landscape?Math.min(h*0.45f,150*dp):Math.min(h*0.38f,200*dp);
        float available=landscape&&!keyboard?h-console:h-console-controlHeight;
        float sh=Math.min(available,w*0.75f),sw=sh*4/3;
        screenRect.set((w-sw)/2,0,(w+sw)/2,sh);
        float cy=landscape&&!keyboard?h-console:h-controlHeight-console;
        String[] labels={"START","SELECT","OPTION","ESC","RETURN"};int[] codes={3,2,10,1027,1013};
        for(int i=0;i<labels.length;i++){float x=i*w/5;controls.add(new Key(new RectF(x+2*dp,cy+2*dp,x+w/5-2*dp,cy+console-2*dp),labels[i],codes[i]));}
        if(keyboard) {
            String[][] rows={{"1","2","3","4","5","6","7","8","9","0"},{"Q","W","E","R","T","Y","U","I","O","P"},{"A","S","D","F","G","H","J","K","L","⌫"},{"SHIFT","Z","X","C","V","B","N","M",",","."},{"CTRL","TAB","SPACE",";","=","+","←","→","↑","↓"}};
            float top=h-keyboardHeight,kh=keyboardHeight/5;
            for(int row=0;row<5;row++)for(int col=0;col<rows[row].length;col++){
                String label=rows[row][col];int key;
                switch(label){case "SHIFT":key=304;break;case "CTRL":key=306;break;case "⌫":key=8;break;case "TAB":key=9;break;case "SPACE":key=32;break;case "←":key=276;break;case "→":key=275;break;case "↑":key=273;break;case "↓":key=274;break;default:key=Character.toLowerCase(label.charAt(0));}
                float kw=w/rows[row].length;
                controls.add(new Key(new RectF(col*kw+dp,top+row*kh+dp,(col+1)*kw-dp,top+(row+1)*kh-dp),label,1000+key));
            }
            joy.setEmpty();fire.setEmpty();
        }else {
            float size=Math.min(controlHeight*0.92f,180*dp),bottom=landscape?h-console-4*dp:h-4*dp,y=bottom-size;
            joy.set(8*dp,y,8*dp+size,y+size);float fs=size*0.62f;
            fire.set(w-fs-16*dp,bottom-fs-8*dp,w-16*dp,bottom-8*dp);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff101018);layoutControls();
        int w,h;synchronized(runner.screen){w=runner.width;h=runner.height;System.arraycopy(runner.screen,0,pixels,0,w*h);}
        if(bitmap==null || bitmap.getWidth()!=w || bitmap.getHeight()!=h)bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels,0,w,0,0,w,h);canvas.drawBitmap(bitmap,null,screenRect,pixelPaint);
        paint.setTextAlign(Paint.Align.CENTER);
        for(Key key:controls){paint.setColor((key.code==1304&&shift)||(key.code==1306&&ctrl)?0xff5277df:0xee293348);canvas.drawRoundRect(key.rect,5*dp,5*dp,paint);paint.setColor(Color.WHITE);paint.setTextSize(Math.min(13*dp,key.rect.width()/Math.max(1,key.label.length())*1.4f));canvas.drawText(key.label,key.rect.centerX(),key.rect.centerY()+paint.getTextSize()*0.35f,paint);}
        if(!keyboard){paint.setColor(0xcc293348);canvas.drawOval(joy,paint);canvas.drawOval(fire,paint);paint.setColor(0xffdbe4ff);paint.setTextSize(34*dp);canvas.drawText("✚",joy.centerX(),joy.centerY()+12*dp,paint);paint.setTextSize(18*dp);canvas.drawText("FIRE",fire.centerX(),fire.centerY()+6*dp,paint);}
        if(paused){paint.setColor(0x99000000);canvas.drawRect(screenRect,paint);paint.setColor(Color.WHITE);paint.setTextSize(24*dp);canvas.drawText("ПАУЗА",screenRect.centerX(),screenRect.centerY(),paint);}
    }
    private int hit(float x,float y) {for(Key key:controls)if(key.rect.contains(x,y))return key.code;if(joy.contains(x,y))return JOY;if(fire.contains(x,y))return FIRE;return -99;}
    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked(),idx=e.getActionIndex();
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN){float x=e.getX(idx),y=e.getY(idx);int code=hit(x,y);if(code==1304)shift=!shift;else if(code==1306)ctrl=!ctrl;else touches.put(e.getPointerId(idx),new Touch(code,x,y));}
        else if(action==MotionEvent.ACTION_MOVE){for(int i=0;i<e.getPointerCount();i++){Touch t=touches.get(e.getPointerId(i));if(t!=null){t.x=e.getX(i);t.y=e.getY(i);if(t.code!=JOY)t.code=hit(t.x,t.y);}}}
        else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP){Touch t=touches.get(e.getPointerId(idx));if(t!=null && t.code>=1000){shift=false;ctrl=false;}touches.remove(e.getPointerId(idx));}
        else if(action==MotionEvent.ACTION_CANCEL){touches.clear();shift=ctrl=false;}
        updateInput();invalidate();return true;
    }
    private void updateInput(){
        int mask=physicalButtons|analogButtons;for(int i=0;i<512;i++)runner.keys.set(i,physicalKeys[i]);
        if(shift)runner.keys.set(304,1);if(ctrl)runner.keys.set(306,1);
        for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(t.code==JOY){float dx=(t.x-joy.centerX())/(joy.width()/2),dy=(t.y-joy.centerY())/(joy.height()/2);if(dx<-.25f)mask|=1<<6;if(dx>.25f)mask|=1<<7;if(dy<-.25f)mask|=1<<4;if(dy>.25f)mask|=1<<5;}else if(t.code>=1000&&t.code<1512)runner.keys.set(t.code-1000,1);else if(t.code>=0&&t.code<16)mask|=1<<t.code;}
        touchButtons=mask;runner.buttons.set(mask);
    }
    void analog(float x,float y){analogButtons=(x<-.3f?1<<6:0)|(x>.3f?1<<7:0)|(y<-.3f?1<<4:0)|(y>.3f?1<<5:0);updateInput();}
    void physicalButton(int bit,boolean down){if(down)physicalButtons|=1<<bit;else physicalButtons&=~(1<<bit);updateInput();}
    void physicalKey(int key,boolean down){if(key>=0&&key<512){physicalKeys[key]=down?1:0;updateInput();}}
    void releaseAll(){touches.clear();Arrays.fill(physicalKeys,0);physicalButtons=touchButtons=analogButtons=0;shift=ctrl=false;updateInput();invalidate();}
}
