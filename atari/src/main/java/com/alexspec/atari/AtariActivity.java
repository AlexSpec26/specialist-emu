package com.alexspec.atari;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Playable Atari XL/XE frontend. GPL-2.0-or-later; native source and notices bundled in repo. */
public final class AtariActivity extends Activity {
    private static final int GAME=1,OS=2,BASIC=3;
    private AtariRunner runner;
    private PlayView view;
    private TextView title;
    private Button pauseButton;
    private android.content.SharedPreferences prefs;
    private File root;
    private boolean resumed,userPaused;
    private int dialogs;
    private volatile String current="",currentTitle="Atari BASIC";
    private final android.util.SparseIntArray physical=new android.util.SparseIntArray();
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);root=getFilesDir();prefs=getSharedPreferences("atari",MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runner=new AtariRunner(root,new AtariRunner.Listener(){public void frame(){if(view!=null)view.postInvalidateOnAnimation();}public void message(String text){if(!isDestroyed())toast(text);}});
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(0xff101018);
        layout.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout bar=new LinearLayout(this);Button menu=new Button(this);menu.setText("☰");menu.setOnClickListener(v->menu());bar.addView(menu,new LinearLayout.LayoutParams(-2,-2));
        title=new TextView(this);title.setTextColor(0xffe0e7ff);title.setTextSize(15);title.setGravity(Gravity.CENTER);title.setMaxLines(2);bar.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        pauseButton=new Button(this);pauseButton.setText("❚❚");pauseButton.setOnClickListener(v->{userPaused=!userPaused;pause();});bar.addView(pauseButton);
        Button keyboard=new Button(this);keyboard.setText("⌨");keyboard.setOnClickListener(v->{view.releaseAll();view.keyboard=!view.keyboard;view.invalidate();});bar.addView(keyboard);
        layout.addView(bar);view=new PlayView(this,runner);layout.addView(view,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
        runner.sound=prefs.getBoolean("sound",true);
        runner.start();
        runner.post(()->{
            File oldOs=new File(root,"os.rom"),newOs=new File(root,"ATARIXL.ROM");if(oldOs.exists()&&!newOs.exists())copy(new FileInputStream(oldOs),newOs,16384);
            File oldBasic=new File(root,"basic.rom"),newBasic=new File(root,"ATARIBAS.ROM");if(oldBasic.exists()&&!newBasic.exists())copy(new FileInputStream(oldBasic),newBasic,8192);
            copy(getAssets().open("demos/catch-the-star.xex"),new File(root,"demo.xex"),65536);
            String last=prefs.getString("path","");if(!last.isEmpty()&&!new File(last).exists())last="";
            String name=last.isEmpty()?"Atari BASIC":prefs.getString("title","Atari");
            open(last,name,true);
        });
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void pause(){boolean p=!resumed || userPaused || dialogs>0;runner.pause(p);view.paused=p;pauseButton.setText(userPaused?"▶":"❚❚");view.invalidate();}
    private AlertDialog show(AlertDialog.Builder builder){dialogs++;pause();AlertDialog d=builder.create();d.setOnDismissListener(v->{dialogs--;pause();});d.show();return d;}
    private void menu() {
        String[] items={"Открыть программу / диск / картридж…","Поймай звезду — встроенная игра","Мои программы…","Atari BASIC","Сохранить состояние","Загрузить состояние","Сброс Atari",runner.sound?"Звук: включён":"Звук: выключен","Память текущей машины…","Учебный инспектор Java 6502","Установить своё OS ROM (16 КБ)…","Установить своё BASIC ROM (8 КБ)…",prefs.getBoolean("gameBasic",false)?"BASIC при загрузке игр: включён":"BASIC при загрузке игр: выключен","Свободные OS/BASIC по умолчанию","О программе / лицензии"};
        show(new AlertDialog.Builder(this).setTitle("Atari 65XE").setItems(items,(d,n)->{
            switch(n){
                case 0:pick(GAME);break;
                case 1:runner.post(()->open(new File(root,"demo.xex").getAbsolutePath(),"Поймай звезду",false));break;
                case 2:library();break;
                case 3:runner.post(()->open("","Atari BASIC",false));break;
                case 4:runner.post(()->{runner.save("quick");runOnUiThread(()->toast("Сохранено: "+currentTitle));});break;
                case 5:runner.post(()->{runner.load("quick");runOnUiThread(()->toast("Состояние восстановлено"));});break;
                case 6:runner.post(runner::reset);break;
                case 7:runner.sound=!runner.sound;prefs.edit().putBoolean("sound",runner.sound).apply();break;
                case 8:memory(0);break;
                case 9:startActivity(new Intent(this,InspectorActivity.class));break;
                case 10:pick(OS);break;
                case 11:pick(BASIC);break;
                case 12:prefs.edit().putBoolean("gameBasic",!prefs.getBoolean("gameBasic",false)).apply();runner.post(()->open(current,currentTitle,false));break;
                case 13:runner.post(()->{for(String f:new String[]{"ATARIXL.ROM","ATARIBAS.ROM","os.rom","basic.rom"}){File file=new File(root,f);if(file.exists()&&!file.delete())throw new IOException("Не удалось сбросить ПЗУ");}open(current,currentTitle,false);});break;
                default:about();
            }
        }));
    }
    private void open(String path,String name,boolean auto) throws Exception {
        runner.open(path,path.isEmpty() || prefs.getBoolean("gameBasic",false),auto);
        current=path;currentTitle=name;prefs.edit().putString("path",path).putString("title",name).apply();
        runOnUiThread(()->{if(!isDestroyed())title.setText(name+" · PAL");});
    }
    private void pick(int type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,type);}
    private String name(Uri uri){String name=null;try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(RuntimeException ignored){}return name==null?"game.xex":name;}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();String name=name(uri);
        runner.post(()->{
            if(request==OS||request==BASIC){int size=request==OS?16384:8192;File temp=new File(root,"rom-import.tmp");copy(getContentResolver().openInputStream(uri),temp,size);if(temp.length()!=size){temp.delete();throw new IOException("Нужно ровно "+size+" байт");}File target=new File(root,request==OS?"ATARIXL.ROM":"ATARIBAS.ROM");if(!temp.renameTo(target))throw new IOException("Не удалось сохранить ПЗУ");open(current,currentTitle,false);return;}
            if(request!=GAME)return;
            String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
            if(!Arrays.asList("xex","com","exe","atr","xfd","car","cas","atx","dcm").contains(ext))throw new IOException("Откройте XEX, ATR, CAR или CAS (также XFD/ATX/DCM)");
            File dir=new File(root,"games");dir.mkdirs();File temp=new File(dir,"import.tmp");copy(getContentResolver().openInputStream(uri),temp,32*1024*1024);
            String id=AtariRunner.digest(AtariRunner.read(temp,32*1024*1024));File game=new File(dir,id+"."+ext);if(game.exists())temp.delete();else if(!temp.renameTo(game))throw new IOException("Не удалось сохранить программу");
            open(game.getAbsolutePath(),name,false);prefs.edit().putString("name:"+game.getName(),name).apply();
        });
    }
    private static void copy(InputStream source,File file,int limit) throws IOException {
        if(source==null)throw new IOException("Не удалось открыть файл");
        try(InputStream in=source;OutputStream out=new FileOutputStream(file)){
            byte[] buffer=new byte[8192];int n,total=0;while((n=in.read(buffer))!=-1){total+=n;if(total>limit)throw new IOException("Слишком большой файл (максимум "+limit+" байт)");out.write(buffer,0,n);}
        }
    }
    private void library(){
        File[] files=new File(root,"games").listFiles((dir,name)->!name.endsWith(".tmp"));if(files==null||files.length==0){toast("Пока нет программ. Откройте свой файл или встроенную игру.");return;}
        Arrays.sort(files,Comparator.comparing(File::getName));String[] names=new String[files.length];for(int i=0;i<files.length;i++)names[i]=prefs.getString("name:"+files[i].getName(),files[i].getName());
        show(new AlertDialog.Builder(this).setTitle("Мои программы").setItems(names,(d,i)->runner.post(()->open(files[i].getAbsolutePath(),names[i],true))));
    }
    private void memory(int page){
        runner.post(()->{byte[] ram=NativeAtari.memory();if(ram==null)return;StringBuilder dump=new StringBuilder();for(int i=0;i<256;i+=16){dump.append(String.format(Locale.US,"%04X ",page+i));for(int j=0;j<16;j++)dump.append(String.format(Locale.US,"%02X ",ram[(page+i+j)&65535]&255));dump.append('\n');}
            runOnUiThread(()->{if(isDestroyed())return;TextView text=new TextView(this);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setTextSize(11);text.setText(dump);ScrollView scroll=new ScrollView(this);scroll.addView(text);show(new AlertDialog.Builder(this).setTitle(String.format(Locale.US,"Память $%04X",page)).setView(scroll).setNegativeButton("◀",(d,w)->memory((page-256)&0xff00)).setPositiveButton("▶",(d,w)->memory((page+256)&0xff00)).setNeutralButton("Закрыть",null));});
        });
    }
    private void about(){show(new AlertDialog.Builder(this).setTitle("Atari 65XE").setMessage("Видео ANTIC/GTIA, звук POKEY, XEX/ATR/CAR/CAS, экранные клавиатура и джойстик.\n\nИгровой движок: Atari800 7.0.0 / libretro, GPL-2.0-or-later. Свободные замены Altirra OS и BASIC © Avery Lee. Учебное Java-ядро доступно отдельно.\n\nПоймай звезду — оригинальная демонстрационная игра, CC0. На физическом устройстве сборку проверяет владелец.\n\nИсходники и лицензии: GitHub AlexSpec26/specialist-emu, ветка feature/atari-65xe, папка atari; архив исходников приложен к релизу.")
        .setPositiveButton("Лицензии",(d,w)->runner.post(()->{byte[] bytes;try(InputStream in=getAssets().open("licenses.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);bytes=out.toByteArray();}String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);runOnUiThread(()->{if(!isDestroyed())show(new AlertDialog.Builder(this).setTitle("Licenses").setMessage(text).setPositiveButton("OK",null));});})).setNegativeButton("OK",null));}
    @Override protected void onResume(){super.onResume();resumed=true;if(runner!=null)pause();}
    @Override protected void onPause(){resumed=false;if(view!=null)view.releaseAll();physical.clear();if(runner!=null){runner.post(()->{try{runner.save("auto");}catch(IOException ignored){}});pause();}super.onPause();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&view!=null){view.releaseAll();physical.clear();}}
    @Override protected void onDestroy(){if(runner!=null)runner.quit();super.onDestroy();}
    @Override public boolean onGenericMotionEvent(MotionEvent event){
        if(view!=null && dialogs==0 && (event.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK && event.getAction()==MotionEvent.ACTION_MOVE){
            float x=event.getAxisValue(MotionEvent.AXIS_X),y=event.getAxisValue(MotionEvent.AXIS_Y);
            float hatX=event.getAxisValue(MotionEvent.AXIS_HAT_X),hatY=event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            view.analog(Math.abs(hatX)>.3f?hatX:x,Math.abs(hatY)>.3f?hatY:y);return true;
        }return super.onGenericMotionEvent(event);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        if(view==null||dialogs>0)return super.dispatchKeyEvent(e);int action=e.getAction();if(action!=KeyEvent.ACTION_DOWN&&action!=KeyEvent.ACTION_UP)return super.dispatchKeyEvent(e);
        int code=e.getKeyCode(),bit=-1,key=-1;
        switch(code){case KeyEvent.KEYCODE_DPAD_UP:bit=4;break;case KeyEvent.KEYCODE_DPAD_DOWN:bit=5;break;case KeyEvent.KEYCODE_DPAD_LEFT:bit=6;break;case KeyEvent.KEYCODE_DPAD_RIGHT:bit=7;break;case KeyEvent.KEYCODE_BUTTON_A:case KeyEvent.KEYCODE_BUTTON_B:bit=0;break;case KeyEvent.KEYCODE_F1:bit=3;break;case KeyEvent.KEYCODE_F2:bit=2;break;case KeyEvent.KEYCODE_F3:bit=10;break;case KeyEvent.KEYCODE_ENTER:key=13;break;case KeyEvent.KEYCODE_DEL:key=8;break;case KeyEvent.KEYCODE_ESCAPE:key=27;break;case KeyEvent.KEYCODE_TAB:key=9;break;case KeyEvent.KEYCODE_SHIFT_LEFT:case KeyEvent.KEYCODE_SHIFT_RIGHT:key=304;break;case KeyEvent.KEYCODE_CTRL_LEFT:case KeyEvent.KEYCODE_CTRL_RIGHT:key=306;break;default:if(code>=KeyEvent.KEYCODE_A&&code<=KeyEvent.KEYCODE_Z)key='a'+code-KeyEvent.KEYCODE_A;else if(code>=KeyEvent.KEYCODE_0&&code<=KeyEvent.KEYCODE_9)key='0'+code-KeyEvent.KEYCODE_0;else {int c=e.getUnicodeChar(0);if(c>0&&c<128)key=c;}}
        boolean down=action==KeyEvent.ACTION_DOWN;if(bit>=0){view.physicalButton(bit,down);return true;}if(key>=0){if(down)physical.put(code,key);else{key=physical.get(code,key);physical.delete(code);}view.physicalKey(key,down);return true;}return super.dispatchKeyEvent(e);
    }
}
