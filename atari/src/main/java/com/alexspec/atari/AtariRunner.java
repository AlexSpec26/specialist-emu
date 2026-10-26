package com.alexspec.atari;

import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.*;

/** Owns the native engine, frame clock, audio and state I/O outside the UI thread. GPL-2.0-or-later. */
final class AtariRunner extends Thread {
    interface Listener { void frame(); void message(String text); }
    interface Work { void run() throws Exception; }
    final AtomicInteger buttons=new AtomicInteger();
    final AtomicIntegerArray keys=new AtomicIntegerArray(512);
    final int[] screen=new int[120000];
    int width=336,height=240;
    private final int[] back=new int[120000],geometry=new int[2];
    private final short[] audio=new short[8192];
    private final byte[] held=new byte[512];
    private final ConcurrentLinkedQueue<Work> queue=new ConcurrentLinkedQueue<>();
    private final Object wake=new Object();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final File root;
    private final Listener listener;
    private boolean opened;
    private String content="",profile="";
    volatile boolean running=true,paused=true,sound=true;
    private double fps=49.86076;

    AtariRunner(File root,Listener listener) {super("Atari emulation");this.root=root;this.listener=listener;}
    void post(Work work) {queue.add(work);synchronized(wake){wake.notifyAll();}}
    void pause(boolean value) {paused=value;clearInput();synchronized(wake){wake.notifyAll();}}
    void clearInput(){buttons.set(0);for(int i=0;i<512;i++)keys.set(i,0);}
    void quit(){running=false;clearInput();synchronized(wake){wake.notifyAll();}}
    private void message(String text){ui.post(()->listener.message(text));}
    static String digest(byte[] data) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format(java.util.Locale.US,"%02x",b&255));return s.toString();
    }
    private String profile(String path,boolean basic) throws Exception {
        StringBuilder id=new StringBuilder("Atari800-4e7fbc7-PAL-64K-").append(basic);
        if(!path.isEmpty()) {
            File file=new File(path);
            // Imported disks can be written by games: keep their original content-ID filename.
            id.append(file.getParentFile().getName().equals("games")?file.getName():digest(read(file,32*1024*1024)));
        }
        for(String name:new String[]{"ATARIXL.ROM","ATARIBAS.ROM"}) {File f=new File(root,name);id.append(f.exists()?digest(read(f,16384)):"Altirra");}
        return digest(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void open(String path,boolean basic,boolean auto) throws Exception {
        if(opened)save("auto");
        String nextProfile=profile(path,basic);
        if(!NativeAtari.open(root.getAbsolutePath(),path,basic)){opened=false;throw new IOException("Не удалось открыть программу. Выберите другую или Atari BASIC в меню.");}
        opened=true;content=path;profile=nextProfile;fps=NativeAtari.fps();clearInput();
        if(auto && stateFile("auto").exists())try{load("auto");}catch(IOException e){message("Автосохранение пропущено: "+e.getMessage());}
    }
    void reset(){if(opened){NativeAtari.reset();clearInput();}}
    private File stateFile(String slot){File dir=new File(root,"states");dir.mkdirs();return new File(dir,profile+"-"+slot+".state");}
    void save(String slot) throws Exception {
        if(!opened)throw new IOException("Машина ещё не запущена");
        byte[] data=NativeAtari.save();if(data==null)throw new IOException("Ядро не создало снимок");
        AtomicFile file=new AtomicFile(stateFile(slot));FileOutputStream out=null;
        try{out=file.startWrite();DataOutputStream d=new DataOutputStream(out);d.writeUTF("ATARI-STATE-1");d.writeUTF(profile);d.writeUTF(digest(data));d.writeInt(data.length);d.write(data);d.flush();file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    void load(String slot) throws Exception {
        if(!opened)throw new IOException("Машина ещё не запущена");
        try(DataInputStream in=new DataInputStream(new FileInputStream(stateFile(slot)))) {
            if(!"ATARI-STATE-1".equals(in.readUTF()) || !profile.equals(in.readUTF()))throw new IOException("Снимок от другой программы/ПЗУ");
            String hash=in.readUTF();int size=in.readInt();if(size<16 || size>8*1024*1024)throw new IOException("Повреждённый снимок");
            byte[] data=new byte[size];in.readFully(data);if(in.read()!=-1 || !hash.equals(digest(data)))throw new IOException("Повреждённый снимок");
            if(!NativeAtari.load(data))throw new IOException("Ядро отклонило снимок");clearInput();
        }
    }
    static byte[] read(File file,int limit) throws IOException {
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Слишком большой файл");out.write(buffer,0,n);}return out.toByteArray();
        }
    }
    void memory() throws IOException {
        byte[] data=NativeAtari.memory();if(data==null)throw new IOException("Машина не запущена");
        FileOutputStream out=new FileOutputStream(new File(root,"memory.bin"));try{out.write(data);}finally{out.close();}
        message("Память записана в memory.bin (64 КБ)");
    }
    @Override public void run() {
        AudioTrack track=null;boolean playing=false;
        try {
            synchronized(NativeAtari.class) {
                if(NativeAtari.owner instanceof AtariRunner && NativeAtari.owner!=this)((AtariRunner)NativeAtari.owner).quit();
                NativeAtari.owner=this;
            }
            try {
                int size=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                track=new AudioTrack(AudioManager.STREAM_MUSIC,44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT,Math.max(16384,size),AudioTrack.MODE_STREAM);
                if(track.getState()!=AudioTrack.STATE_INITIALIZED){track.release();track=null;}
            }catch(RuntimeException e){message("Звук недоступен: эмуляция продолжится без него");}
            long next=System.nanoTime();
            while(running || !queue.isEmpty()) {
                synchronized(NativeAtari.class) {
                    if(NativeAtari.owner!=null && NativeAtari.owner!=this)break;
                    NativeAtari.owner=this;
                    Work work;while((work=queue.poll())!=null)try{work.run();}catch(Exception e){message(e.getMessage()==null?e.toString():e.getMessage());}
                }
                if(!running)break;
                if(paused || !opened) {
                    if(track!=null && playing){track.pause();track.flush();playing=false;}
                    synchronized(wake){if(running && queue.isEmpty())try{wake.wait();}catch(InterruptedException ignored){}}
                    next=System.nanoTime();continue;
                }
                int samples;
                synchronized(NativeAtari.class) {
                    if(NativeAtari.owner!=this)break;
                    for(int i=0;i<512;i++)held[i]=(byte)keys.get(i);
                    samples=NativeAtari.frame(back,audio,geometry,buttons.get(),held);
                }
                if(samples<0){paused=true;message("Ошибка выполнения ядра");continue;}
                synchronized(screen){width=geometry[0];height=geometry[1];System.arraycopy(back,0,screen,0,width*height);}
                listener.frame();
                if(sound && track!=null && samples>0) {
                    if(!playing){track.play();playing=true;}track.write(audio,0,samples);next=System.nanoTime();
                } else {
                    if(track!=null && playing){track.pause();track.flush();playing=false;}
                    next+=(long)(1e9/fps);long wait=next-System.nanoTime();if(wait>0)try{Thread.sleep(wait/1000000,(int)(wait%1000000));}catch(InterruptedException ignored){}else if(wait< -100000000)next=System.nanoTime();
                }
            }
        }catch(Throwable e){message("Ошибка эмуляции: "+e.getMessage());}
        finally {
            if(track!=null){if(playing)track.pause();track.release();}
            synchronized(NativeAtari.class){if(NativeAtari.owner==this){NativeAtari.close();NativeAtari.owner=null;}}
        }
    }
}
